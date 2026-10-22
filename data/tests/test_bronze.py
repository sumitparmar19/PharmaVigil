"""Bronze conversion of a whole quarter: Parquet per table plus a manifest with counts."""

import json

from pharmavigil_data.faers.bronze import convert_zip, ingest_quarter, manifest_path, table_path
from pharmavigil_data.quarters import Quarter


def test_convert_zip_writes_tables_and_manifest(lake, quarter_zip) -> None:
    q = Quarter.parse("2019Q1")
    manifest = convert_zip(quarter_zip("2019Q1"), q)
    assert manifest["layout"] == "FAERS"
    assert manifest["tables"]["demo"]["rows"] == 4
    assert manifest["tables"]["drug"]["repaired_rows"] == 1
    assert manifest["tables"]["ther"] == {"member": None, "rows": 0}
    assert table_path("demo", q).exists()
    assert json.loads(manifest_path(q).read_text())["quarter"] == "2019Q1"


def test_ingest_quarter_uses_existing_zip_and_deletes_it(lake, quarter_zip, tmp_path) -> None:
    q = Quarter.parse("2012Q3")
    raw = tmp_path / "raw" / "faers" / q.zip_name()
    raw.parent.mkdir(parents=True)
    quarter_zip("2012Q3").rename(raw)
    manifest = ingest_quarter(q)
    assert manifest["layout"] == "AERS"
    assert not raw.exists()
    # Second call is a no-op that returns the stored manifest.
    assert ingest_quarter(q)["zip_sha256"] == manifest["zip_sha256"]
