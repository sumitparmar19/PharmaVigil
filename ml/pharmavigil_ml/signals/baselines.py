"""M3 runner: compute PRR, ROR, IC and EBGM on the FAERS gold tables, evaluate them on the OMOP
and EU-ADR reference sets, and write

- `analysis/m3/baselines.json`  AUCs with bootstrap CIs, per reference set and role variant, plus the
                                fitted EBGM priors and the data scope label
- `analysis/m3/roc_<set>_<variant>.png`  ROC curves
- `<lake>/gold/signals/baselines_<variant>.parquet`  every observed (rxcui, pt) pair with its scores,
                                which signal-service loads to show evidence at the pharmacy counter
"""

from __future__ import annotations

import csv
import json
from collections import defaultdict
from dataclasses import dataclass
from datetime import date
from pathlib import Path

import pandas as pd

from . import contingency as ct
from . import ebgm as eb
from .evaluate import evaluate, map_reference_drugs, roc_points, score_pairs

VARIANTS = {"primary_suspect": ct.PRIMARY_SUSPECT, "all_roles": ct.ALL_ROLES}
SIGNAL_TABLE_MIN_A = 3  # pairs with fewer reports are not stored for the pharmacy warning


@dataclass
class BaselineInputs:
    gold_dir: Path
    refsets_dir: Path
    outcome_map: Path
    out_dir: Path
    signals_dir: Path | None
    scope: str
    before: date | None = None
    n_boot: int = 1000


def load_outcome_map(path: Path) -> dict[str, list[str]]:
    groups: dict[str, list[str]] = defaultdict(list)
    with open(path, newline="") as fh:
        for row in csv.DictReader(fh):
            groups[row["outcome_name"]].append(row["pt"].strip().lower())
    return dict(groups)


def run_variant(con, name: str, roles, inputs: BaselineInputs, outcome_pts) -> dict[str, object]:
    pairs = ct.pt_pair_counts(con, roles=roles, before=inputs.before)
    stats = score_pairs(pairs, prior=None)
    prior = eb.fit_prior(stats["a"].to_numpy(), stats["expected"].to_numpy())
    ingredients = ct.ingredient_names(con)

    result: dict[str, object] = {
        "observed_pairs": int(len(pairs)),
        "background_reports": int(pairs["n_total"].iloc[0]) if len(pairs) else 0,
        "ebgm_prior": prior.as_dict(),
        "reference_sets": {},
    }
    if inputs.signals_dir is not None and len(stats):
        keep = stats[stats["a"] >= SIGNAL_TABLE_MIN_A].copy()
        for k, v in eb.score(keep["a"].to_numpy(), keep["expected"].to_numpy(), prior).items():
            keep[k] = v
        inputs.signals_dir.mkdir(parents=True, exist_ok=True)
        keep.to_parquet(inputs.signals_dir / f"baselines_{name}.parquet", index=False)
        result["signal_table_pairs"] = int(len(keep))

    for set_name in ("omop", "euadr"):
        ref = pd.read_parquet(inputs.refsets_dir / f"{set_name}.parquet")
        mapped = map_reference_drugs(ref, ingredients)
        missing_drugs = sorted(mapped.loc[mapped["rxcui"].isna(), "exposure_name"].unique())
        wanted = mapped.dropna(subset=["rxcui"])
        counts = ct.outcome_pair_counts(con, wanted, outcome_pts, roles=roles, before=inputs.before)
        scored = wanted.merge(counts, on=["rxcui", "outcome_name"], how="left")
        scored = score_pairs(scored, prior)
        unmapped = mapped[mapped["rxcui"].isna()].assign(a=0, n_drug=0, n_event=0, n_total=0)
        scored_all = pd.concat([scored, unmapped], ignore_index=True)
        summary = evaluate(scored_all, n_boot=inputs.n_boot)
        summary["drugs_not_found_in_gold"] = missing_drugs
        result["reference_sets"][set_name] = summary
        _plot_roc(scored, set_name, name, inputs.out_dir)
        scored_all.to_csv(inputs.out_dir / f"scores_{set_name}_{name}.csv", index=False)
    return result


def _plot_roc(scored: pd.DataFrame, set_name: str, variant: str, out_dir: Path) -> None:
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    if scored["ground_truth"].nunique() < 2 or (scored["n_drug"] > 0).sum() == 0:
        return
    fig, ax = plt.subplots(figsize=(5, 5))
    for col, label in (("prr", "PRR"), ("ror", "ROR"), ("ic", "IC"), ("ebgm", "EBGM")):
        fpr, tpr = roc_points(scored, col)
        ax.plot(fpr, tpr, label=label)
    ax.plot([0, 1], [0, 1], linestyle="--", color="grey", linewidth=1)
    ax.set_xlabel("False positive rate")
    ax.set_ylabel("True positive rate")
    ax.set_title(f"{set_name.upper()} reference set ({variant.replace('_', ' ')})")
    ax.legend(loc="lower right")
    fig.tight_layout()
    fig.savefig(out_dir / f"roc_{set_name}_{variant}.png", dpi=120)
    plt.close(fig)


def run(inputs: BaselineInputs) -> dict[str, object]:
    inputs.out_dir.mkdir(parents=True, exist_ok=True)
    outcome_pts = load_outcome_map(inputs.outcome_map)
    con = ct.connect(inputs.gold_dir)
    report = {
        "scope": inputs.scope,
        "before": inputs.before.isoformat() if inputs.before else None,
        "bootstrap_resamples": inputs.n_boot,
        "variants": {name: run_variant(con, name, roles, inputs, outcome_pts) for name, roles in VARIANTS.items()},
        "disclaimer": "Spontaneous reports do not prove that a drug caused a reaction.",
    }
    (inputs.out_dir / "baselines.json").write_text(json.dumps(report, indent=2))
    return report
