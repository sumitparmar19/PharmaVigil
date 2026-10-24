"""Duplicate detection (Model C, part 2): is a new report another report of an event already on file?

Training signal (a **proxy**, stated in the model card): consecutive versions of the same FAERS case are
positives, because they describe one event; pairs from different cases are negatives, half of them
"hard" (sharing at least one drug) and half random. True cross-case duplicates are not labelled in
FAERS, so precision/recall here measure how well the model recognizes same-event pairs of this kind.

Pair features are simple record-linkage comparisons that the Java case-service recomputes for a new
report against candidate cases (`PAIR_FEATURES` fixes their order for ONNX).
"""

from __future__ import annotations

import json
import zlib
from dataclasses import dataclass
from pathlib import Path

import lightgbm as lgb
import numpy as np
import pandas as pd
from sklearn.metrics import average_precision_score, precision_recall_curve, precision_score, recall_score

PAIR_FEATURES = (
    "drug_jaccard", "pt_jaccard", "shared_drugs", "shared_pts", "same_sex", "same_age_band",
    "same_country", "same_occupation", "event_date_diff_days", "fda_date_diff_days",
)
PARAMS = {"objective": "binary", "learning_rate": 0.05, "num_leaves": 31, "min_data_in_leaf": 30,
          "verbose": -1, "seed": 13, "deterministic": True, "num_threads": 4}


def _jaccard(a: set, b: set) -> float:
    return len(a & b) / len(a | b) if (a or b) else 0.0


def _same(a, b) -> float:
    if a is None or b is None or (isinstance(a, float) and np.isnan(a)) or a in ("UNK", "unknown") \
            or b in ("UNK", "unknown"):
        return -1.0
    return 1.0 if a == b else 0.0


def _days(a, b) -> float:
    if a is None or b is None or pd.isna(a) or pd.isna(b):
        return -1.0
    return float(abs((pd.Timestamp(a) - pd.Timestamp(b)).days))


def pair_features(r1: dict, r2: dict) -> list[float]:
    d1, d2 = set(r1.get("drugs") or []), set(r2.get("drugs") or [])
    p1, p2 = set(r1.get("pts") or []), set(r2.get("pts") or [])
    return [
        _jaccard(d1, d2), _jaccard(p1, p2), float(len(d1 & d2)), float(len(p1 & p2)),
        _same(r1.get("sex"), r2.get("sex")), _same(r1.get("age_band"), r2.get("age_band")),
        _same(r1.get("reporter_country"), r2.get("reporter_country")),
        _same(r1.get("occp_cod"), r2.get("occp_cod")),
        _days(r1.get("event_date"), r2.get("event_date")), _days(r1.get("fda_date"), r2.get("fda_date")),
    ]


def make_pairs(versions: pd.DataFrame, seed: int = 13, max_positives: int = 200_000) -> pd.DataFrame:
    """Positive version pairs plus the same number of hard and random negatives."""
    rng = np.random.default_rng(seed)
    v = versions.sort_values(["caseid", "caseversion", "fda_date"]).reset_index(drop=True)
    recs = v.to_dict("records")
    pos = [(i - 1, i) for i in range(1, len(recs)) if recs[i]["caseid"] == recs[i - 1]["caseid"]]
    if len(pos) > max_positives:
        pos = [pos[i] for i in rng.choice(len(pos), max_positives, replace=False)]
    by_drug: dict[str, list[int]] = {}
    for i, r in enumerate(recs):
        for d in r.get("drugs") or []:
            by_drug.setdefault(d, []).append(i)
    neg = []
    for i, _ in pos:
        drugs = list(recs[i].get("drugs") or [])
        if drugs:
            pool = by_drug[drugs[int(rng.integers(len(drugs)))]]
            j = pool[int(rng.integers(len(pool)))]
            if recs[j]["caseid"] != recs[i]["caseid"]:
                neg.append((i, j))
        j = int(rng.integers(len(recs)))
        if recs[j]["caseid"] != recs[i]["caseid"]:
            neg.append((i, j))
    rows = [(*pair_features(recs[a], recs[b]), 1, recs[a]["caseid"]) for a, b in pos]
    rows += [(*pair_features(recs[a], recs[b]), 0, recs[a]["caseid"]) for a, b in neg]
    return pd.DataFrame(rows, columns=[*PAIR_FEATURES, "label", "group_caseid"])


@dataclass
class DuplicateResult:
    metrics: dict[str, object]
    booster: lgb.Booster
    x_test: np.ndarray


def train_duplicates(versions: pd.DataFrame, out_dir: Path, scope: str = "", rounds: int = 300) -> DuplicateResult:
    pairs = make_pairs(versions)
    # Split by case so no case contributes to both sides.
    in_test = pairs["group_caseid"].map(lambda c: zlib.crc32(str(c).encode()) % 5 == 0)
    tr, te = pairs[~in_test], pairs[in_test]
    x_tr, x_te = tr[list(PAIR_FEATURES)].to_numpy(np.float32), te[list(PAIR_FEATURES)].to_numpy(np.float32)
    booster = lgb.train(PARAMS, lgb.Dataset(x_tr, tr["label"].to_numpy()), num_boost_round=rounds)
    p = booster.predict(x_te)
    y = te["label"].to_numpy()
    prec, rec, thr = precision_recall_curve(y, p)
    f1 = 2 * prec * rec / np.maximum(prec + rec, 1e-12)
    best = int(np.argmax(f1[:-1])) if len(thr) else 0
    best_thr = float(thr[best]) if len(thr) else 0.5
    metrics = {
        "scope": scope,
        "training_signal": "proxy: consecutive versions of the same FAERS case are positives",
        "pairs_train": int(len(tr)), "pairs_test": int(len(te)),
        "positives_test": int(y.sum()),
        "pr_auc": float(average_precision_score(y, p)),
        "at_0.5": {"precision": float(precision_score(y, p >= 0.5, zero_division=0)),
                   "recall": float(recall_score(y, p >= 0.5, zero_division=0))},
        "best_f1_threshold": best_thr,
        "at_best_f1": {"precision": float(prec[best]), "recall": float(rec[best]), "f1": float(f1[best])},
        "features": list(PAIR_FEATURES),
        "rounds": rounds,
    }
    out_dir.mkdir(parents=True, exist_ok=True)
    booster.save_model(str(out_dir / "duplicates.lgb.txt"))
    (out_dir / "duplicates_metrics.json").write_text(json.dumps(metrics, indent=2))
    return DuplicateResult(metrics, booster, x_te)


def load_versions(path: Path, sample_cases: int | None = None, seed: int = 13) -> pd.DataFrame:
    """Read the silver `versions` table (Delta folder or Parquet export). With `sample_cases`, only the versions
    of that many randomly chosen cases are read: the case ids are read first and the rest is filtered while
    reading, so the full table (12.8M versions with drug and PT lists) never has to fit in memory."""
    import pyarrow.compute as pc
    import pyarrow.dataset as ds

    if (path / "_delta_log").exists():
        from deltalake import DeltaTable

        dataset = DeltaTable(str(path)).to_pyarrow_dataset()
    else:
        dataset = ds.dataset(str(path), format="parquet")
    if not sample_cases:
        return dataset.to_table().to_pandas()
    ids = pd.Series(pc.unique(dataset.to_table(columns=["caseid"])["caseid"]).to_pylist())
    keep = ids.sample(min(sample_cases, len(ids)), random_state=seed)
    return dataset.to_table(filter=ds.field("caseid").isin(keep.tolist())).to_pandas()

