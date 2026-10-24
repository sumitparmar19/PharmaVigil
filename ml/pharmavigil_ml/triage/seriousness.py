"""Seriousness triage (Model C, part 1): a LightGBM model that predicts whether a report will turn out
serious, so serious cases reach a reviewer first (FDA's clock for serious unexpected reports is 15 days).

Labels come from FAERS outcome codes (any outcome = serious) and are used only as targets. Split is by
time (train on earlier reports, test on the latest window) to avoid the leakage a shuffled split hides.
Reported: PR-AUC (average precision, the main metric because serious reports are the minority class of
interest), ROC AUC, Brier score, a 10-bin calibration table, and the top features by mean |SHAP|
(LightGBM's native TreeSHAP contributions).
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import date
from pathlib import Path

import duckdb
import lightgbm as lgb
import numpy as np
import pandas as pd
from sklearn.metrics import average_precision_score, brier_score_loss, roc_auc_score

from .features import FeatureSpec, build_spec, vectorize

SHAP_ROWS = 50_000
PARAMS = {
    "objective": "binary",
    "learning_rate": 0.05,
    "num_leaves": 63,
    "min_data_in_leaf": 50,
    "feature_fraction": 0.8,
    "bagging_fraction": 0.8,
    "bagging_freq": 1,
    "lambda_l2": 1.0,
    "verbose": -1,
    "seed": 13,
    "deterministic": True,
    "num_threads": 4,
}


@dataclass
class Frames:
    reports: pd.DataFrame
    drugs: pd.DataFrame
    pts: pd.DataFrame


def load_frames(con: duckdb.DuckDBPyConnection, limit: int | None = None) -> Frames:
    """Reports (with the label), drugs and PTs from the gold views registered by `contingency.connect`."""
    lim = f"USING SAMPLE {int(limit)} ROWS (reservoir, 13)" if limit else ""
    reports = con.execute(f"""
        SELECT primaryid, fda_dt, age_band, sex, occp_cod, reporter_country, serious
        FROM src_report {lim}
    """).df()
    con.register("tri_ids", reports[["primaryid"]])
    drugs = con.execute(
        "SELECT d.primaryid, d.rxcui, d.role FROM src_report_drug d JOIN tri_ids USING (primaryid)"
    ).df()
    pts = con.execute("SELECT e.primaryid, e.pt FROM src_report_reaction e JOIN tri_ids USING (primaryid)").df()
    return Frames(reports.sort_values(["fda_dt", "primaryid"]).reset_index(drop=True), drugs, pts)


def calibration_table(y: np.ndarray, p: np.ndarray, bins: int = 10) -> list[dict[str, float]]:
    edges = np.linspace(0, 1, bins + 1)
    out = []
    for lo, hi in zip(edges[:-1], edges[1:], strict=True):
        m = (p >= lo) & (p < hi if hi < 1 else p <= hi)
        if m.any():
            out.append({"bin": f"{lo:.1f}-{hi:.1f}", "n": int(m.sum()), "mean_predicted": float(p[m].mean()),
                        "observed_rate": float(y[m].mean())})
    return out


def train_seriousness(frames: Frames, test_from: date, out_dir: Path, rounds: int = 400,
                      scope: str = "") -> dict[str, object]:
    r = frames.reports
    test_mask = pd.to_datetime(r["fda_dt"]).dt.date >= test_from
    train_r, test_r = r[~test_mask], r[test_mask]
    if len(train_r) == 0 or len(test_r) == 0:
        raise ValueError("time split left one side empty")
    spec = build_spec(train_r, frames.drugs[frames.drugs["primaryid"].isin(train_r["primaryid"])],
                      frames.pts[frames.pts["primaryid"].isin(train_r["primaryid"])])
    x_tr = vectorize(train_r, frames.drugs, frames.pts, spec)
    x_te = vectorize(test_r, frames.drugs, frames.pts, spec)
    y_tr, y_te = train_r["serious"].astype(int).to_numpy(), test_r["serious"].astype(int).to_numpy()

    booster = lgb.train(PARAMS, lgb.Dataset(x_tr, y_tr, feature_name=_safe_names(spec)), num_boost_round=rounds)
    p = booster.predict(x_te)
    # Mean |SHAP| on a fixed random subset of the test set: the full contribution matrix (rows x features,
    # float64) would not fit next to the training data on the build machine, and the ranking is stable well below.
    shap_idx = np.random.default_rng(0).choice(len(x_te), size=min(SHAP_ROWS, len(x_te)), replace=False)
    contrib = booster.predict(x_te[np.sort(shap_idx)], pred_contrib=True)[:, :-1]
    importance = np.abs(contrib).mean(axis=0)
    names = spec.columns()
    top = [{"feature": names[i], "mean_abs_shap": float(importance[i])} for i in np.argsort(-importance)[:20]]

    out_dir.mkdir(parents=True, exist_ok=True)
    booster.save_model(str(out_dir / "seriousness.lgb.txt"))
    spec.save(out_dir / "seriousness_features.json")
    metrics = {
        "scope": scope,
        "test_from": test_from.isoformat(),
        "train_reports": int(len(train_r)), "test_reports": int(len(test_r)),
        "test_serious_rate": float(y_te.mean()),
        "pr_auc": float(average_precision_score(y_te, p)) if y_te.any() else None,
        "roc_auc": float(roc_auc_score(y_te, p)) if 0 < y_te.mean() < 1 else None,
        "brier": float(brier_score_loss(y_te, p)),
        "calibration": calibration_table(y_te, p),
        "top_features_mean_abs_shap": top,
        "shap_rows": int(len(shap_idx)),
        "n_features": len(names),
        "params": {k: v for k, v in PARAMS.items() if k != "verbose"},
        "rounds": rounds,
        "inputs_never_read": ["serious (label only)", "outcomes", "outcome-revealing PTs"],
    }
    (out_dir / "seriousness_metrics.json").write_text(json.dumps(metrics, indent=2))
    return {"metrics": metrics, "booster": booster, "spec": spec, "x_test": x_te}


def _safe_names(spec: FeatureSpec) -> list[str]:
    # LightGBM forbids some characters in feature names; positions are what the ONNX graph uses anyway.
    return [f"f{i}" for i in range(len(spec.columns()))]
