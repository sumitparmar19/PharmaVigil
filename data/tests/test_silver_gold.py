"""End-to-end Spark test on the fixtures: bronze → silver de-duplication → gold tables and
normalization coverage. Checks the counts promised in fixtures/README.md."""

import pytest

from pharmavigil_data import paths
from pharmavigil_data.faers.bronze import convert_zip
from pharmavigil_data.faers.gold import build_gold
from pharmavigil_data.faers.silver import build_silver
from pharmavigil_data.quarters import Quarter

pytestmark = pytest.mark.spark


@pytest.fixture
def built(lake, quarter_zip, spark):
    for q in ("2012Q3", "2019Q1", "2019Q2"):
        convert_zip(quarter_zip(q), Quarter.parse(q))
    silver = build_silver(spark)
    rxmap = spark.createDataFrame(
        [
            ("ATORVASTATIN CALCIUM", "exact", "83367", "atorvastatin"),
            ("ACETAMINOPHEN", "exact", "161", "acetaminophen"),
            ("HYDROCODONE BITARTRATE", "exact", "5489", "hydrocodone"),
            ("AMOXICILLIN", "exact", "723", "amoxicillin"),
            ("AMOXIL", "exact", "723", "amoxicillin"),
            ("ROSIGLITAZONE", "exact", "84108", "rosiglitazone"),
        ],
        ["name_key", "method", "rxcui", "ingredient"],
    )
    gold = build_gold(spark, rxmap)
    return silver, gold


def _gold(spark, name):
    return spark.read.format("delta").load(str(paths.gold("faers", name)))


def test_dedup_counts(built) -> None:
    silver, _ = built
    assert silver["dedup"] == {
        "report_rows": 8,
        "unique_report_keys": 8,
        "after_deleted_cases": 7,
        "unique_cases": 4,
    }


def test_latest_version_kept_and_faers_beats_aers(built, spark) -> None:
    rows = {r["caseid"]: r for r in _gold(spark, "report").collect()}
    assert set(rows) == {"100", "200", "400", "500"}
    assert rows["100"]["primaryid"] == "FAERS:1003"
    assert rows["200"]["primaryid"] == "FAERS:2001"
    assert rows["500"]["primaryid"] == "AERS:8000001"


def test_derived_fields(built, spark) -> None:
    rows = {r["caseid"]: r for r in _gold(spark, "report").collect()}
    assert rows["100"]["serious"] is True
    assert rows["100"]["outcomes"] == ["HO", "LT"]
    assert rows["100"]["age_band"] == "45-64"
    assert rows["200"]["age_band"] == "0-1"  # 8 months
    assert rows["400"]["serious"] is False and rows["400"]["sex"] == "UNK"
    assert rows["500"]["age_band"] == "45-64"  # 6 decades
    assert str(rows["200"]["event_dt"]) == "2018-01-01"  # year-only date


def test_gold_drug_and_reaction(built, spark) -> None:
    drugs = {(r["primaryid"], r["rxcui"], r["role"]) for r in _gold(spark, "report_drug").collect()}
    assert ("FAERS:1003", "83367", "PS") in drugs
    assert ("AERS:8000001", "84108", "PS") in drugs
    assert not any(d[0] == "FAERS:4001" for d in drugs)  # unknown brand stays unmapped
    pts = {(r["primaryid"], r["pt"]) for r in _gold(spark, "report_reaction").collect()}
    assert ("FAERS:1003", "acute kidney injury") in pts
    assert not any(p[0].startswith("FAERS:300") for p in pts)  # deleted case


def test_normalization_coverage(built) -> None:
    _, gold = built
    cov = gold["normalization_coverage"]
    # Mentions on kept reports: 1003 (lipitor), 2001 (amoxicillin), 4001 (unknown), 8000001 (rosiglitazone)
    assert cov["mentions"] == 4
    assert cov["mapped_mentions"] == 3
    assert cov["ps_mentions"] == 4


def test_versions_keep_every_case_version(built, spark) -> None:
    from pharmavigil_data.faers.versions import build_versions

    rows = {r["report_key"]: r for r in build_versions(spark, write=False).collect()}
    # 8 DEMO rows minus the deleted case 300.
    assert len(rows) == 7
    case100 = sorted(k for k, r in rows.items() if r["caseid"] == "100")
    assert case100 == ["FAERS:1001", "FAERS:1002", "FAERS:1003"]
    # No RxNorm map table was saved in this fixture, so drugs fall back to their name keys.
    assert rows["FAERS:1003"]["drugs"] == ["name:ATORVASTATIN CALCIUM"]
    assert rows["FAERS:1002"]["pts"] == ["myalgia", "rhabdomyolysis"]


def test_demo_slice_and_p1_counts(built, spark, tmp_path) -> None:
    """Phase 2: the replay sample keeps only the listed fields of real de-duplicated cases from the chosen
    quarters (one line per case, with drugs and reactions); the P1 counts agree with the de-duplication."""
    import json

    from pharmavigil_data.demo_slice import build_demo_slice, data_counts

    meta = build_demo_slice(spark, tmp_path / "demo", ["2019Q2"], 50)
    rows = [json.loads(line) for line in (tmp_path / "demo" / "replay_reports.jsonl").read_text().splitlines()]
    assert meta["written"] == len(rows) > 0 and meta["requested"] == 50
    assert {r["quarter"] for r in rows} == {"2019Q2"}
    assert len({r["faers_caseid"] for r in rows}) == len(rows)
    for r in rows:
        assert set(r) == {"faers_primaryid", "faers_caseid", "quarter", "fda_date", "event_date", "age_years", "sex",
                          "reporter_type", "country", "outcomes", "drugs", "reactions"}
        assert r["reporter_type"] in {"PHYSICIAN", "PHARMACIST", "OTHER_HCP", "LAWYER", "CONSUMER"}
        assert r["drugs"] and all(d["role"] in {"PS", "SS", "C", "I"} for d in r["drugs"])
        assert r["reactions"] and all(pt == pt.lower() for pt in r["reactions"])
    assert len(meta["sha256"]) == 64

    counts = data_counts(spark, "fixture scope")
    assert counts["quarters"] == ["2012Q3", "2019Q1", "2019Q2"]
    assert counts["unique_cases_after_dedup"] == 4
    assert counts["faers_report_rows"] == sum(b["demo_rows"] for b in counts["bronze_quarters_with_zip_sha256"])
    assert 0 < counts["drug_mentions_mapped_to_rxnorm"] <= counts["gold_drug_ingredient_rows"]
    assert counts["scope"] == "fixture scope"
