"""Quarter parsing, ordering, AERS/FAERS layout boundary and download URLs."""

import pytest

from pharmavigil_data.quarters import Quarter, quarter_range


def test_parse_and_format() -> None:
    q = Quarter.parse("2019q1")
    assert str(q) == "2019Q1"
    assert q.short == "19Q1"


def test_layout_boundary() -> None:
    assert Quarter.parse("2012Q3").is_legacy_aers
    assert not Quarter.parse("2012Q4").is_legacy_aers


def test_urls() -> None:
    assert Quarter.parse("2025Q1").url().endswith("/faers_ascii_2025q1.zip")
    assert Quarter.parse("2008Q2").url().endswith("/aers_ascii_2008q2.zip")


def test_range_crosses_years() -> None:
    assert [str(q) for q in quarter_range("2019Q3", "2020Q2")] == ["2019Q3", "2019Q4", "2020Q1", "2020Q2"]


@pytest.mark.parametrize("bad", ["2019", "2019Q5", "Q1-2019"])
def test_rejects_bad_quarters(bad: str) -> None:
    with pytest.raises(ValueError):
        Quarter.parse(bad)


def test_range_rejects_reversed() -> None:
    with pytest.raises(ValueError):
        quarter_range("2020Q1", "2019Q4")
