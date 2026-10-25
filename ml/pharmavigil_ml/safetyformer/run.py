"""M4 runners: train SafetyFormer on the gold tables, compare it with the baselines on the same
reference pairs, run the yearly time-travel test and the leakage check, and write evidence under
analysis/m4/ (every file carries its data-scope label)."""

from __future__ import annotations

import gc
import json
from dataclasses import dataclass
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

from ..signals import contingency as ct
from ..signals import ebgm as eb
from ..signals.baselines import load_outcome_map
from ..signals.evaluate import SCORE_COLUMNS, evaluate, map_reference_drugs, score_pairs
from .corpus import load_corpus
from .leakage import compare_splits
from .score import log_prob_table, outcome_scores, pt_scores
from .timetravel import TimeTravelConfig, label_pairs_from_text, run_time_travel
from .train import TrainConfig, train

DISCLAIMER = "Spontaneous reports do not prove that a drug caused a reaction."


@dataclass
class M4Inputs:
    gold_dir: Path
    refsets_dir: Path
    outcome_map: Path
    out_dir: Path
    models_dir: Path
    scope: str
    train: TrainConfig
    before: date | None = None
    tracking_uri: str | None = None
    n_boot: int = 1000


def _write(path: Path, payload: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, default=str))


def _time_split(corpus, share: float = 0.05):
    cut = np.quantile(corpus.fda_days, 1 - share) if len(corpus) else 0
    return corpus.subset(corpus.fda_days < cut), corpus.subset(corpus.fda_days >= cut)


def train_and_evaluate(inp: M4Inputs) -> dict[str, object]:
    inp.out_dir.mkdir(parents=True, exist_ok=True)
    con = ct.connect(inp.gold_dir)
    corpus = load_corpus(con, before=inp.before, min_count=inp.train.min_count)
    train_c, val_c = _time_split(corpus)
    res = train(train_c, inp.train, val=val_c, out_dir=inp.models_dir, tracking_uri=inp.tracking_uri)
    model = res.pop("model_obj")
    # The scoring below needs only the vocabulary; the corpus (the full report table) is released first.
    vocab, n_train, n_val = corpus.vocab, len(train_c), len(val_c)
    del corpus, train_c, val_c
    gc.collect()
    table, background = log_prob_table(model, vocab)
    outcome_pts = load_outcome_map(inp.outcome_map)

    # Baselines on exactly the same window and reference pairs.
    pairs = ct.pt_pair_counts(con, roles=ct.PRIMARY_SUSPECT, before=inp.before)
    stats = score_pairs(pairs, prior=None)
    prior = eb.fit_prior(stats["a"].to_numpy(), stats["expected"].to_numpy())
    sf_outcomes = outcome_scores(table, background, vocab, outcome_pts)
    ingredients = ct.ingredient_names(con)
    sets = {}
    for set_name in ("omop", "euadr"):
        ref = map_reference_drugs(pd.read_parquet(inp.refsets_dir / f"{set_name}.parquet"), ingredients)
        wanted = ref.dropna(subset=["rxcui"])
        counts = ct.outcome_pair_counts(con, wanted, outcome_pts, roles=ct.PRIMARY_SUSPECT, before=inp.before)
        scored = score_pairs(wanted.merge(counts, on=["rxcui", "outcome_name"], how="left"), prior)
        scored = scored.merge(sf_outcomes, on=["rxcui", "outcome_name"], how="left")
        # Same scorable set for every method: drug seen in the window AND in the model vocabulary.
        scored.loc[scored["safetyformer"].isna(), "n_drug"] = 0
        unmapped = ref[ref["rxcui"].isna()].assign(a=0, n_drug=0, n_event=0, n_total=0)
        all_pairs = pd.concat([scored, unmapped], ignore_index=True)
        sets[set_name] = evaluate(all_pairs, columns=(*SCORE_COLUMNS, "safetyformer"), n_boot=inp.n_boot)
        all_pairs.to_csv(inp.out_dir / f"scores_{set_name}.csv", index=False)

    if len(stats):
        observed = stats.loc[stats["a"] >= 3, ["rxcui", "pt", "a"]].reset_index(drop=True)
        pt_scores(table, background, vocab, pairs=observed).dropna(subset=["safetyformer"]).to_parquet(
            inp.models_dir / "safetyformer_pt_scores.parquet", index=False)

    report = {
        "scope": inp.scope,
        "before": inp.before.isoformat() if inp.before else None,
        "train_reports": n_train,
        "validation_reports": n_val,
        "vocabulary": {"drugs": len(vocab.drug_tokens), "pts": vocab.n_pt},
        "training": {k: v for k, v in res.items() if k not in ("config",)},
        "train_config": res["config"],
        "reference_sets": sets,
        "disclaimer": DISCLAIMER,
    }
    _write(inp.out_dir / "safetyformer.json", report)
    return report


def time_travel(inp: M4Inputs, label_changes: pd.DataFrame, snapshots: list[date],
                top_n: tuple[int, ...] = (10, 20, 50)) -> dict[str, object]:
    con = ct.connect(inp.gold_dir)
    ingredients = ct.ingredient_names(con)
    vocab_pts = [r[0] for r in con.execute("SELECT DISTINCT pt FROM src_report_reaction").fetchall()]
    pairs, match_stats = label_pairs_from_text(label_changes, ingredients, vocab_pts)

    def score_at(cutoff: date) -> pd.DataFrame:
        counts = ct.pt_pair_counts(con, roles=ct.PRIMARY_SUSPECT, before=cutoff)
        stats = score_pairs(counts, prior=None)
        if len(stats) == 0:
            return stats
        prior = eb.fit_prior(stats["a"].to_numpy(), stats["expected"].to_numpy())
        stats["ebgm"] = eb.ebgm(stats["a"].to_numpy(), stats["expected"].to_numpy(), prior)
        corpus = load_corpus(con, before=cutoff, min_count=inp.train.min_count)
        res = train(corpus, inp.train, run_name=f"snapshot-{cutoff.isoformat()}", tracking_uri=inp.tracking_uri)
        table, bg = log_prob_table(res["model_obj"], corpus.vocab)
        sf = pt_scores(table, bg, corpus.vocab)
        return stats.merge(sf, on=["rxcui", "pt"], how="left")

    summary = run_time_travel(pairs, score_at, TimeTravelConfig(snapshots=tuple(snapshots), top_n=top_n))
    report = {"scope": inp.scope, "label_change_matching": match_stats, **summary, "disclaimer": DISCLAIMER}
    _write(inp.out_dir / "time_travel.json", report)
    return report


def leakage(inp: M4Inputs, test_start: date) -> dict[str, object]:
    con = ct.connect(inp.gold_dir)
    corpus = load_corpus(con, min_count=inp.train.min_count)
    report = {"scope": inp.scope, **compare_splits(corpus, test_start, inp.train)}
    _write(inp.out_dir / "leakage.json", report)
    return report
