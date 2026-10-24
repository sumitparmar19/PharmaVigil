"""Java model bundle: age bands match the data platform's, and a bundle records hashes and parity."""

import json

import lightgbm as lgb
import numpy as np

from pharmavigil_ml.export.java_bundle import _fixture_pairs, age_band, finish_bundle, write_duplicates
from pharmavigil_ml.triage.duplicates import PARAMS


def test_age_band_edges() -> None:
    assert age_band(None) == "unknown"
    assert age_band(-1) == "unknown" and age_band(130) == "unknown"
    assert age_band(0) == "0-1" and age_band(1.99) == "0-1" and age_band(2) == "2-11"
    assert age_band(17.9) == "12-17" and age_band(45) == "45-64" and age_band(75) == "75+"


def test_duplicates_part_and_bundle_metadata(tmp_path) -> None:
    x, y = _fixture_pairs(400)
    booster = lgb.train(PARAMS, lgb.Dataset(x, y), num_boost_round=5)
    part = write_duplicates(booster, 0.5, tmp_path, x[:3])
    assert part["onnx_max_abs_diff"] < 1e-5
    assert len(part["rows"]) == 3 and len(part["rows"][0]["features"]) == 10
    meta = finish_bundle(tmp_path, "fixture", "synthetic", {"duplicates": part})
    assert set(meta["files"]) == {"duplicates.onnx", "duplicates.json", "parity.json"}
    assert json.loads((tmp_path / "bundle.json").read_text())["kind"] == "fixture"
    assert np.isfinite(json.loads((tmp_path / "parity.json").read_text())["duplicates"]["rows"][0]["probability"])
