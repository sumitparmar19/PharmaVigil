"""Shared pytest fixtures: an isolated lake directory, quarterly zips built from the text fixtures,
and one local Spark session for the tests marked `spark`."""

from __future__ import annotations

import zipfile
from pathlib import Path

import pytest

FIXTURES = Path(__file__).parent / "fixtures"


@pytest.fixture
def lake(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    monkeypatch.setenv("PV_LAKE", str(tmp_path / "lake"))
    monkeypatch.setenv("PV_RAW", str(tmp_path / "raw"))
    return tmp_path / "lake"


def make_quarter_zip(quarter: str, dest_dir: Path) -> Path:
    """Zip a fixture quarter folder the way FDA packages it (sub-folders kept)."""
    src = FIXTURES / "faers" / quarter
    out = dest_dir / f"{quarter}.zip"
    with zipfile.ZipFile(out, "w") as zf:
        for path in sorted(src.rglob("*")):
            if path.is_file():
                zf.write(path, path.relative_to(src).as_posix())
    return out


@pytest.fixture
def quarter_zip(tmp_path: Path):
    return lambda quarter: make_quarter_zip(quarter, tmp_path)


@pytest.fixture(scope="session")
def spark():
    from pharmavigil_data.spark import get_spark

    session = get_spark("pharmavigil-tests", cores="2")
    yield session
    session.stop()
