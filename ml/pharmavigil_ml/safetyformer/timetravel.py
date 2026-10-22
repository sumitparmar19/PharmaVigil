"""Time-travel test (ADR-006): would a method have flagged a drug–reaction pair before FDA added it
to the drug's label, and how many months earlier?

Method:
1. Snapshots at the start of each year (Amendment 1: one SafetyFormer snapshot per year). Snapshot c
   sees only reports FDA received before c: baseline counts are recomputed with that cutoff and a fresh
   SafetyFormer is trained on that window only.
2. At each snapshot every method ranks the reactions of each drug by its score. A pair is *flagged* at c
   if its reaction is in the drug's top N.
3. For a label change (drug, PT, label date), the lead time is the gap between the label date and the
   earliest snapshot before the label date at which the pair was flagged. Pairs never flagged before
   the label date count as missed.

Label changes come from FDA's Safety-related Labeling Changes (SrLC) database: the changed label text is
matched against the FAERS PT vocabulary (whole words, case-insensitive) and the drug name against RxNorm
ingredient names. No date is ever invented: a change without a date is skipped and counted.
"""

from __future__ import annotations

import re
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from datetime import date

import numpy as np
import pandas as pd

ScoreFn = Callable[[date], pd.DataFrame]  # cutoff → (rxcui, pt, <method columns>)


@dataclass(frozen=True)
class TimeTravelConfig:
    snapshots: tuple[date, ...]
    top_n: tuple[int, ...] = (10, 20, 50)
    methods: tuple[str, ...] = ("prr", "ror", "ic", "ebgm", "safetyformer")


def _norm(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", " ", text.lower()).strip()


def label_pairs_from_text(changes: pd.DataFrame, ingredients: pd.DataFrame, pt_vocab: Sequence[str],
                          min_pt_chars: int = 5) -> tuple[pd.DataFrame, dict[str, int]]:
    """Turn SrLC rows (drug_name, label_date, text) into (rxcui, pt, label_date) pairs."""
    lookup = {_norm(i): r for r, i in zip(ingredients["rxcui"], ingredients["ingredient"], strict=True)}
    patterns = [(pt, re.compile(rf"\b{re.escape(_norm(pt))}\b")) for pt in pt_vocab if len(pt) >= min_pt_chars]
    stats = {"changes": int(len(changes)), "no_date": 0, "drug_not_found": 0, "no_pt_match": 0}
    rows = []
    for _, ch in changes.iterrows():
        if pd.isna(ch["label_date"]):
            stats["no_date"] += 1
            continue
        rx = lookup.get(_norm(str(ch["drug_name"])))
        if rx is None:
            stats["drug_not_found"] += 1
            continue
        text = _norm(str(ch["text"]))
        found = [pt for pt, pat in patterns if pat.search(text)]
        if not found:
            stats["no_pt_match"] += 1
        rows.extend({"rxcui": rx, "pt": pt, "label_date": pd.Timestamp(ch["label_date"]).date()} for pt in found)
    pairs = pd.DataFrame(rows, columns=["rxcui", "pt", "label_date"])
    # One pair can be added in several changes; keep the earliest label date.
    pairs = pairs.sort_values("label_date").drop_duplicates(["rxcui", "pt"], keep="first")
    stats["pairs"] = int(len(pairs))
    return pairs.reset_index(drop=True), stats


def per_drug_ranks(scores: pd.DataFrame, method: str) -> pd.DataFrame:
    """Rank of each PT within its drug (1 = strongest) for one method's score column."""
    s = scores[["rxcui", "pt", method]].dropna()
    s = s.assign(rank=s.groupby("rxcui")[method].rank(ascending=False, method="first"))
    return s[["rxcui", "pt", "rank"]]


def months_between(a: date, b: date) -> float:
    return (b - a).days / 30.4375


def run_time_travel(pairs: pd.DataFrame, score_at: ScoreFn, cfg: TimeTravelConfig,
                    log=print) -> dict[str, object]:
    """Evaluate every method at every snapshot and summarize flag rates and lead times."""
    ranks: dict[tuple[date, str], pd.DataFrame] = {}
    for cutoff in cfg.snapshots:
        scores = score_at(cutoff)
        for m in cfg.methods:
            if m in scores:
                ranks[(cutoff, m)] = per_drug_ranks(scores, m).set_index(["rxcui", "pt"])["rank"]
        log({"snapshot": cutoff.isoformat(), "scored_pairs": int(len(scores))})

    summary: dict[str, object] = {"snapshots": [c.isoformat() for c in cfg.snapshots], "methods": {}}
    for m in cfg.methods:
        per_n = {}
        for n in cfg.top_n:
            leads, evaluable, flagged = [], 0, 0
            for row in pairs.itertuples(index=False):
                before = [c for c in cfg.snapshots if c < row.label_date and (c, m) in ranks]
                if not before:
                    continue
                seen = [c for c in before if (row.rxcui, row.pt) in ranks[(c, m)].index]
                if not seen:
                    continue  # the drug or reaction never appears before the label date
                evaluable += 1
                hit = [c for c in seen if ranks[(c, m)].loc[(row.rxcui, row.pt)] <= n]
                if hit:
                    flagged += 1
                    leads.append(months_between(min(hit), row.label_date))
            per_n[f"top_{n}"] = {
                "evaluable_pairs": evaluable,
                "flagged_pairs": flagged,
                "share_flagged": flagged / evaluable if evaluable else None,
                "median_lead_months": float(np.median(leads)) if leads else None,
            }
        summary["methods"][m] = per_n
    return summary
