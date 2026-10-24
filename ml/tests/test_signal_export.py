"""Signal export for signal-service: joins baseline scores with ingredient names, writes the trend for the top
pairs and a meta file with the scope and the causation disclaimer (synthetic gold data only)."""

import json

import pandas as pd
from synthetic import make_gold, make_refsets, write_outcome_map

from pharmavigil_ml.signals.baselines import BaselineInputs, run
from pharmavigil_ml.signals.export import COLUMNS, DISCLAIMER, export_signals


def test_export_signals(tmp_path) -> None:
    gold = make_gold(tmp_path / "gold", n_reports=1500)
    signals = tmp_path / "signals"
    run(BaselineInputs(gold_dir=gold, refsets_dir=make_refsets(tmp_path / "ref"),
                       outcome_map=write_outcome_map(tmp_path / "map.csv"), out_dir=tmp_path / "m3",
                       signals_dir=signals, scope="synthetic", before=None, n_boot=20))
    meta = export_signals(gold, signals, tmp_path / "out", "synthetic test data",
                          baselines_json=tmp_path / "m3" / "baselines.json", trend_pairs=5)
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
