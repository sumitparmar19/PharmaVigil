"""Duplicate detection on synthetic case versions: pair features, training and ONNX parity."""

from datetime import date, timedelta

import numpy as np
import pandas as pd

from pharmavigil_ml.export.onnx_export import export_lightgbm, lightgbm_parity
from pharmavigil_ml.triage.duplicates import PAIR_FEATURES, make_pairs, pair_features, train_duplicates


def _versions(n_cases: int = 800, seed: int = 2) -> pd.DataFrame:
    rng = np.random.default_rng(seed)
    rows = []
    for c in range(n_cases):
        drugs = sorted(set(rng.choice([f"rx{i}" for i in range(25)], size=rng.integers(1, 4))))
        pts = sorted(set(rng.choice([f"pt{i}" for i in range(40)], size=rng.integers(1, 4))))
        base = {"caseid": str(c), "age_band": rng.choice(["18-44", "45-64", "75+"]), "sex": rng.choice(["M", "F"]),
                "reporter_country": rng.choice(["US", "GB", "JP"]), "occp_cod": rng.choice(["MD", "CN"]),
                "event_date": date(2021, 1, 1) + timedelta(days=int(rng.integers(0, 700)))}
        first = date(2022, 1, 1) + timedelta(days=int(rng.integers(0, 300)))
        rows.append({**base, "report_key": f"FAERS:{c}1", "caseversion": 1, "fda_date": first,
                     "drugs": drugs, "pts": pts})
        if rng.random() < 0.6:  # follow-up adds a term
            rows.append({**base, "report_key": f"FAERS:{c}2", "caseversion": 2,
                         "fda_date": first + timedelta(days=int(rng.integers(5, 90))),
                         "drugs": drugs, "pts": sorted(set(pts) | {f"pt{rng.integers(0, 40)}"})})
    return pd.DataFrame(rows)


def test_pair_features_order_and_values() -> None:
    a = {"drugs": ["x", "y"], "pts": ["p"], "sex": "F", "age_band": "18-44", "reporter_country": "US",
         "occp_cod": "MD", "event_date": date(2022, 1, 1), "fda_date": date(2022, 2, 1)}
    b = {**a, "drugs": ["x"], "sex": "UNK", "fda_date": date(2022, 2, 11)}
    f = dict(zip(PAIR_FEATURES, pair_features(a, b), strict=True))
    assert f["drug_jaccard"] == 0.5 and f["pt_jaccard"] == 1.0 and f["shared_drugs"] == 1
    assert f["same_sex"] == -1.0 and f["same_country"] == 1.0
    assert f["event_date_diff_days"] == 0 and f["fda_date_diff_days"] == 10


def test_make_pairs_balances_labels() -> None:
    pairs = make_pairs(_versions())
    assert 0.2 < pairs["label"].mean() < 0.6
    assert list(pairs.columns[: len(PAIR_FEATURES)]) == list(PAIR_FEATURES)


def test_train_and_onnx_parity(tmp_path) -> None:
    res = train_duplicates(_versions(), tmp_path, scope="synthetic test", rounds=80)
    assert res.metrics["pr_auc"] > 0.9
    assert "proxy" in res.metrics["training_signal"]
    path = export_lightgbm(res.booster, len(PAIR_FEATURES), tmp_path / "duplicates.onnx")
    assert lightgbm_parity(res.booster, path, res.x_test[:300])["max_abs_diff"] < 1e-5
