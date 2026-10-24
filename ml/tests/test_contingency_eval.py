"""DuckDB counts on a tiny hand-checkable gold set, the time cutoff, the role filter, reference-drug
name matching, AUC with CI, and an end-to-end baselines run on synthetic data with planted signals."""

import json
from datetime import date

import numpy as np
import pandas as pd
import pytest
from synthetic import make_gold, make_refsets, write_outcome_map

from pharmavigil_ml.signals import contingency as ct
from pharmavigil_ml.signals.evaluate import auc_with_ci, evaluate, map_reference_drugs


@pytest.fixture
def tiny_gold(tmp_path):
    reports = pd.DataFrame({"primaryid": ["r1", "r2", "r3", "r4"],
                            "fda_dt": [date(2020, 1, 1), date(2020, 6, 1), date(2021, 1, 1), date(2021, 6, 1)]})
    drugs = pd.DataFrame({"primaryid": ["r1", "r2", "r3", "r3", "r4"], "rxcui": ["A", "A", "B", "A", "B"],
                          "ingredient": ["alpha", "alpha", "beta", "alpha", "beta"],
                          "role": ["PS", "PS", "PS", "C", "PS"], "mention_id": list("12345")})
    reac = pd.DataFrame({"primaryid": ["r1", "r2", "r3", "r4"], "pt": ["x", "y", "x", "x"]})
    for name, df in (("report", reports), ("report_drug", drugs), ("report_reaction", reac)):
        (tmp_path / name).mkdir()
        df.to_parquet(tmp_path / name / "p.parquet", index=False)
    return ct.connect(tmp_path)


def test_pt_counts_primary_suspect(tiny_gold) -> None:
    df = ct.pt_pair_counts(tiny_gold).set_index(["rxcui", "pt"])
    assert df.loc[("A", "x"), "a"] == 1 and df.loc[("A", "x"), "n_drug"] == 2
    assert df.loc[("B", "x"), "a"] == 2 and df.loc[("B", "x"), "n_event"] == 3
    assert int(df["n_total"].iloc[0]) == 4


def test_all_roles_counts_concomitant(tiny_gold) -> None:
    df = ct.pt_pair_counts(tiny_gold, roles=ct.ALL_ROLES).set_index(["rxcui", "pt"])
    assert df.loc[("A", "x"), "a"] == 2  # r3 lists A as concomitant


def test_before_cutoff(tiny_gold) -> None:
    df = ct.pt_pair_counts(tiny_gold, before=date(2021, 1, 1))
    assert set(df["rxcui"]) == {"A"} and int(df["n_total"].iloc[0]) == 2


def test_outcome_counts_include_absent_pairs(tiny_gold) -> None:
    pairs = pd.DataFrame({"rxcui": ["A", "B", "Z"], "outcome_name": ["O", "O", "O"]})
    df = ct.outcome_pair_counts(tiny_gold, pairs, {"O": ["X", "y"]}).set_index("rxcui")
    assert df.loc["A", "a"] == 2 and df.loc["B", "a"] == 2
    assert df.loc["Z", "n_drug"] == 0 and df.loc["Z", "n_event"] == 4


def test_map_reference_drugs() -> None:
    ref = pd.DataFrame({"exposure_name": ["Amylases", "Warfarin", "Mystery"]})
    ing = pd.DataFrame({"rxcui": ["1", "2"], "ingredient": ["amylase", "warfarin"]})
    out = map_reference_drugs(ref, ing, overrides={"Mystery": "warfarin"})
    assert list(out["rxcui"]) == ["1", "2", "2"]


def test_auc_ci_perfect_separation() -> None:
    res = auc_with_ci(np.array([0, 0, 1, 1]), np.array([0.1, 0.2, 0.8, 0.9]), n_boot=50)
    assert res["auc"] == 1.0 and res["ci_low"] == 1.0


def test_evaluate_counts_unscorable() -> None:
    df = pd.DataFrame({"ground_truth": [1, 0, 0], "n_drug": [5, 5, 0], "prr": [3.0, 1.0, 1.0]})
    res = evaluate(df, columns=["prr"], n_boot=20)
    assert res["unscorable_pairs"] == 1 and res["methods"]["prr"]["auc"] == 1.0


def test_baselines_end_to_end(tmp_path) -> None:
    from pharmavigil_ml.signals.baselines import BaselineInputs, run

    gold = make_gold(tmp_path / "gold")
    refs = make_refsets(tmp_path / "refsets")
    omap = write_outcome_map(tmp_path / "map.csv")
    out = tmp_path / "out"
    report = run(BaselineInputs(gold, refs, omap, out, tmp_path / "signals", scope="synthetic test", n_boot=50))
    omop = report["variants"]["primary_suspect"]["reference_sets"]["omop"]
    assert omop["unscorable_pairs"] == 1 and omop["drugs_not_found_in_gold"] == ["Unknownium"]
    assert omop["methods"]["ebgm"]["auc"] > 0.9  # planted signals are recovered
    assert json.loads((out / "baselines.json").read_text())["scope"] == "synthetic test"
    assert (out / "roc_omop_primary_suspect.png").exists()
    assert (tmp_path / "signals" / "baselines_all_roles.parquet").exists()
