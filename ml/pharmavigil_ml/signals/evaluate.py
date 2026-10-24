"""Reference-set evaluation: how well each method's score separates known positive drug–outcome
pairs from known negatives (OMOP 165+/234−, EU-ADR 43+/50−), as ROC AUC with a bootstrap 95% CI.

Reference drugs are matched to RxNorm ingredients by name against the ingredients present in the
gold tables; outcomes are expanded to their PT lists (ADR-005). Pairs whose drug never appears in the
analysed reports cannot be scored and are counted as `unscorable`, never silently dropped.
"""

from __future__ import annotations

import re
from collections.abc import Mapping, Sequence

import numpy as np
import pandas as pd
from sklearn.metrics import roc_auc_score, roc_curve

from . import disproportionality as dp
from . import ebgm as eb

# Score columns compared in the report: point estimates and lower confidence/credibility bounds.
SCORE_COLUMNS = ("prr", "prr_lo", "ror", "ror_lo", "ic", "ic025", "ebgm", "eb05")


def _norm(name: str) -> str:
    return re.sub(r"[^a-z0-9]+", " ", name.lower()).strip()


def map_reference_drugs(reference: pd.DataFrame, ingredients: pd.DataFrame,
                        overrides: Mapping[str, str] | None = None) -> pd.DataFrame:
    """Add an `rxcui` column by matching exposure names to gold ingredient names.

    Matching tries the exact normalized name, then a singular form ("amylases" → "amylase").
    `overrides` maps a normalized exposure name to an ingredient name for documented exceptions.
    """
    lookup = {_norm(i): r for r, i in zip(ingredients["rxcui"], ingredients["ingredient"], strict=True)}
    overrides = {_norm(k): _norm(v) for k, v in (overrides or {}).items()}

    def match(name: str) -> str | None:
        key = overrides.get(_norm(name), _norm(name))
        for candidate in (key, key[:-1] if key.endswith("s") else None):
            if candidate and candidate in lookup:
                return lookup[candidate]
        return None

    out = reference.copy()
    out["rxcui"] = out["exposure_name"].map(match)
    return out


def score_pairs(counts: pd.DataFrame, prior: eb.Prior | None) -> pd.DataFrame:
    """Add frequentist and (if a prior is given) EBGM scores to a counts frame."""
    stats = dp.all_frequentist(counts["a"], counts["n_drug"], counts["n_event"], counts["n_total"])
    out = pd.concat([counts.reset_index(drop=True), stats.reset_index(drop=True)], axis=1)
    if prior is not None:
        for k, v in eb.score(out["a"].to_numpy(), out["expected"].to_numpy(), prior).items():
            out[k] = v
    return out


def auc_with_ci(y: np.ndarray, s: np.ndarray, n_boot: int = 1000, seed: int = 0) -> dict[str, float]:
    """ROC AUC and a percentile bootstrap 95% CI (resampling positives and negatives separately)."""
    y = np.asarray(y)
    s = np.asarray(s, dtype=float)
    auc = float(roc_auc_score(y, s))
    rng = np.random.default_rng(seed)
    pos, neg = np.flatnonzero(y == 1), np.flatnonzero(y == 0)
    boots = []
    for _ in range(n_boot):
        idx = np.concatenate([rng.choice(pos, len(pos)), rng.choice(neg, len(neg))])
        boots.append(roc_auc_score(y[idx], s[idx]))
    lo, hi = np.percentile(boots, [2.5, 97.5])
    return {"auc": auc, "ci_low": float(lo), "ci_high": float(hi)}


def evaluate(scored: pd.DataFrame, columns: Sequence[str] = SCORE_COLUMNS, n_boot: int = 1000,
             seed: int = 0) -> dict[str, object]:
    """AUCs for every score column over the scorable pairs of one reference set."""
    scorable = scored[scored["n_drug"] > 0]
    y = scorable["ground_truth"].to_numpy()
    result: dict[str, object] = {
        "pairs": int(len(scored)),
        "scorable_pairs": int(len(scorable)),
        "scorable_positives": int((y == 1).sum()),
        "scorable_negatives": int((y == 0).sum()),
        "unscorable_pairs": int(len(scored) - len(scorable)),
        "methods": {},
    }
    if (y == 1).sum() == 0 or (y == 0).sum() == 0:
        result["note"] = "not enough positives and negatives to compute AUC"
        return result
    for col in columns:
        if col in scorable:
            result["methods"][col] = auc_with_ci(y, scorable[col].to_numpy(), n_boot=n_boot, seed=seed)
    return result


def roc_points(scored: pd.DataFrame, column: str) -> tuple[np.ndarray, np.ndarray]:
    scorable = scored[scored["n_drug"] > 0]
    fpr, tpr, _ = roc_curve(scorable["ground_truth"], scorable[column])
    return fpr, tpr
