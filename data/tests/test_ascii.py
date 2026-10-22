"""ASCII reader: broken-row repair, trailing delimiters, legacy headers, deletion lists."""

import zipfile

import pyarrow.parquet as pq

from pharmavigil_data.faers.ascii import (
    ParseStats,
    convert_member,
    find_deleted_member,
    find_member,
    iter_records,
    read_deleted_caseids,
)


def test_iter_records_repairs_and_drops() -> None:
    stats = ParseStats()
    lines = iter(["a$b$c$\n", "a$b\n", "continued$c\n", "x$y$z$w$v\n", "1$2$3\n"])
    rows = list(iter_records(lines, 3, stats))
    assert rows == [["a", "b", "c"], ["a", "b continued", "c"], ["1", "2", "3"]]
    assert stats.repaired_rows == 1
    assert stats.dropped_rows == 1


def test_iter_records_gives_up_after_too_many_joins() -> None:
    stats = ParseStats()
    rows = list(iter_records(iter(["a\n"] * 10 + ["a$b$c\n"]), 3, stats))
    assert rows == [["a", "b", "c"]]
    assert stats.dropped_rows >= 1


def test_find_members_any_case() -> None:
    names = ["ascii/DEMO12Q3.TXT", "ASCII/DRUG19Q1.txt", "Deleted/DELETE19Q2.txt", "README.pdf"]
    assert find_member(names, "demo", "12Q3") == "ascii/DEMO12Q3.TXT"
    assert find_member(names, "drug", "19Q1") == "ASCII/DRUG19Q1.txt"
    assert find_member(names, "reac", "19Q1") is None
    assert find_deleted_member(names) == "Deleted/DELETE19Q2.txt"


def test_convert_faers_drug_repairs_broken_row(quarter_zip, tmp_path) -> None:
    with zipfile.ZipFile(quarter_zip("2019Q1")) as zf:
        stats = convert_member(zf, "ASCII/DRUG19Q1.txt", "drug", tmp_path / "drug.parquet")
    table = pq.read_table(tmp_path / "drug.parquet").to_pylist()
    assert stats.rows == 5 and stats.repaired_rows == 1 and stats.dropped_rows == 0
    amox = next(r for r in table if r["primaryid"] == "2001")
    assert amox["dose_vbm"] == "5 ML TID, given with food"
    assert amox["prod_ai"] == "AMOXICILLIN"
    assert table[2]["prod_ai"] == "ACETAMINOPHEN\\HYDROCODONE BITARTRATE"


def test_convert_legacy_demo_renames_columns(quarter_zip, tmp_path) -> None:
    with zipfile.ZipFile(quarter_zip("2012Q3")) as zf:
        stats = convert_member(zf, "ascii/DEMO12Q3.TXT", "demo", tmp_path / "demo.parquet")
    rows = pq.read_table(tmp_path / "demo.parquet").to_pylist()
    assert rows[0]["primaryid"] == "8000001"
    assert rows[0]["caseid"] == "500"
    assert rows[0]["sex"] == "M"
    assert rows[0]["caseversion"] is None
    assert "caseversion" in stats.missing_columns


def test_deleted_caseids(quarter_zip) -> None:
    with zipfile.ZipFile(quarter_zip("2019Q2")) as zf:
        assert read_deleted_caseids(zf, "Deleted/DELETE19Q2.txt") == ["300"]
