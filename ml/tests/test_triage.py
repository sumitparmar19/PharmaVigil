"""Seriousness triage: the leakage guard (outcome fields and outcome-revealing PTs never reach the
features), training on synthetic reports, and ONNX parity."""

from datetime import date, timedelta

import numpy as np
import pandas as pd
import pytest

from pharmavigil_ml.export.onnx_export import export_lightgbm, lightgbm_parity
from pharmavigil_ml.triage.features import build_spec, is_outcome_revealing, vectorize, vectorize_one
from pharmavigil_ml.triage.seriousness import Frames, train_seriousness


@pytest.mark.parametrize("pt", ["death", "Sudden death", "hospitalisation", "Life threatening", "congenital anomaly",
                                "Completed suicide is not matched?"])
def test_outcome_revealing_terms(pt: str) -> None:
    expected = "suicide" not in pt.lower()
    assert is_outcome_revealing(pt) is expected


def _frames(n: int = 3000, seed: int = 5) -> Frames:
    rng = np.random.default_rng(seed)
    reports, drugs, pts = [], [], []
    for i in range(n):
        pid = f"r{i}"
        rx = f"rx{rng.integers(0, 15)}"
        pt = f"pt{rng.integers(0, 20)}"
        serious = rng.random() < (0.7 if rx == "rx1" or pt == "pt3" else 0.2)
        reports.append({"primaryid": pid, "fda_dt": date(2020, 1, 1) + timedelta(days=i // 3),
                        "age_band": rng.choice(["18-44", "65-74"]), "sex": rng.choice(["M", "F"]),
                        "occp_cod": rng.choice(["MD", "CN"]), "reporter_country": rng.choice(["US", "GB"]),
                        "serious": serious, "outcomes": ["DE"] if serious else []})
        drugs.append({"primaryid": pid, "rxcui": rx, "role": "PS"})
        pts.append({"primaryid": pid, "pt": pt})
        if serious and rng.random() < 0.5:  # outcome-revealing terms appear only on serious reports
            pts.append({"primaryid": pid, "pt": "death"})
    return Frames(pd.DataFrame(reports), pd.DataFrame(drugs), pd.DataFrame(pts))


def test_spec_never_contains_outcome_terms_or_fields() -> None:
    f = _frames()
    spec = build_spec(f.reports, f.drugs, f.pts)
    assert "death" not in spec.pts
    cols = " ".join(spec.columns()).lower()
    assert "serious" not in cols and "outcome" not in cols and "death" not in cols


def test_features_ignore_outcome_fields_and_revealing_terms() -> None:
    f = _frames(200)
    spec = build_spec(f.reports, f.drugs, f.pts)
    base = vectorize(f.reports, f.drugs, f.pts, spec)
    flipped = f.reports.assign(serious=~f.reports["serious"], outcomes=[["HO"]] * len(f.reports))
    extra_pts = pd.concat([f.pts, pd.DataFrame({"primaryid": f.reports["primaryid"], "pt": "Hospitalisation"})])
    assert np.array_equal(base, vectorize(flipped, f.drugs, f.pts, spec))
    assert np.array_equal(base, vectorize(f.reports, f.drugs, extra_pts, spec))


def test_vectorize_one_matches_batch() -> None:
    f = _frames(50)
    spec = build_spec(f.reports, f.drugs, f.pts)
    r = f.reports.iloc[7]
    one = vectorize_one(r.age_band, r.sex, r.occp_cod, r.reporter_country,
                        [("rx1", "PS")] if False else list(f.drugs[f.drugs.primaryid == r.primaryid][["rxcui", "role"]]
                                                           .itertuples(index=False, name=None)),
                        list(f.pts[f.pts.primaryid == r.primaryid]["pt"]), spec)
    assert np.array_equal(one, vectorize(f.reports, f.drugs, f.pts, spec)[7])


def test_train_and_onnx_parity(tmp_path) -> None:
    res = train_seriousness(_frames(), date(2022, 3, 1), tmp_path, rounds=60, scope="synthetic test")
    m = res["metrics"]
    assert m["pr_auc"] > m["test_serious_rate"]  # better than chance on the planted pattern
    assert all(not t["feature"].startswith("pt=death") for t in m["top_features_mean_abs_shap"])
    path = export_lightgbm(res["booster"], res["x_test"].shape[1], tmp_path / "seriousness.onnx")
    parity = lightgbm_parity(res["booster"], path, res["x_test"][:200])
    assert parity["max_abs_diff"] < 1e-5
