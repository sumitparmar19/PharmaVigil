"""Signal export for signal-service: joins baseline scores with ingredient names, labels the pairs that belong to a
reference set, writes the trend for the top pairs and a meta file with the scope and the causation disclaimer
(synthetic gold data only)."""

import json

import pandas as pd
from synthetic import make_gold, make_refsets, write_outcome_map

from pharmavigil_ml.signals.baselines import BaselineInputs, load_outcome_map, run
from pharmavigil_ml.signals.export import COLUMNS, DISCLAIMER, export_signals, reference_labels


def test_export_signals(tmp_path) -> None:
    gold = make_gold(tmp_path / "gold", n_reports=1500)
    signals = tmp_path / "signals"
    run(BaselineInputs(gold_dir=gold, refsets_dir=make_refsets(tmp_path / "ref"),
                       outcome_map=write_outcome_map(tmp_path / "map.csv"), out_dir=tmp_path / "m3",
                       signals_dir=signals, scope="synthetic", before=None, n_boot=20))
    outcome_map = load_outcome_map(tmp_path / "map.csv")
    meta = export_signals(gold, signals, tmp_path / "out", "synthetic test data",
                          baselines_json=tmp_path / "m3" / "baselines.json", trend_pairs=5,
                          refsets_dir=tmp_path / "ref", outcome_pts=outcome_map)
    table = pd.read_csv(tmp_path / "out" / "signals.csv")
    assert list(table.columns) == COLUMNS
    assert meta["pairs"] == len(table) > 0 and (table["a"] >= 3).all()
    assert table["eb05"].is_monotonic_decreasing
    assert table["ingredient"].notna().all()
    top = table.iloc[0]
    assert (top["rxcui"], top["pt"]) in {("rx1", "acute kidney injury"), ("rx2", "rhabdomyolysis"),
                                         ("rx3", "hepatic failure")}
    trend = pd.read_csv(tmp_path / "out" / "signal_trend.csv")
    assert set(trend.columns) == {"rxcui", "pt", "year", "reports"}
    assert trend.groupby(["rxcui", "pt"]).ngroups <= 5
    first = trend[(trend.rxcui == top["rxcui"]) & (trend.pt == top["pt"])]["reports"].sum()
    assert first == top["a"]
    saved = json.loads((tmp_path / "out" / "signals_meta.json").read_text())
    assert saved["disclaimer"] == DISCLAIMER and saved["scope"] == "synthetic test data"
    assert "primary_suspect/omop" in saved["evaluation"]
    assert saved["with_safetyformer"] is False
    labelled = table.dropna(subset=["reference"])
    assert meta["pairs_in_a_reference_set"] == len(labelled) > 0
    assert set(labelled["reference"].str.split(", ").explode()) <= {"OMOP positive", "OMOP negative",
                                                                     "EU-ADR positive", "EU-ADR negative"}


def test_reference_labels_follow_the_outcome_map(tmp_path) -> None:
    ref = tmp_path / "ref"
    ref.mkdir()
    pd.DataFrame({"exposure_name": ["Amiodarone", "Aspirins", "Unknownium"],
                  "outcome_name": ["fibrosis", "bleeding", "fibrosis"],
                  "ground_truth": [1, 0, 1]}).to_parquet(ref / "omop.parquet")
    ingredients = pd.DataFrame({"rxcui": ["703", "1191"], "ingredient": ["amiodarone", "aspirin"]})
    labels = reference_labels(ref, ingredients, {"fibrosis": ["pulmonary fibrosis", "lung disorder"],
                                                  "bleeding": ["haemorrhage"]})
    got = {(r.rxcui, r.pt): r.reference for r in labels.itertuples()}
    assert got == {("703", "pulmonary fibrosis"): "OMOP positive", ("703", "lung disorder"): "OMOP positive",
                   ("1191", "haemorrhage"): "OMOP negative"}
