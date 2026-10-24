"""Bronze layer for FAERS: one quarter at a time, download the zip, convert every table to
Parquet with canonical columns, record a manifest (hash, row counts, parse repairs), then delete
the raw zip so the container's disk holds only compact Parquet (Amendment 1, item 3)."""

from __future__ import annotations

import json
import zipfile
from datetime import UTC, datetime
from pathlib import Path

import pyarrow as pa
import pyarrow.parquet as pq

from .. import paths
from ..http import download, sha256_file
from ..quarters import Quarter
from .ascii import convert_member, find_deleted_member, find_member, read_deleted_caseids
from .schema import TABLES


def manifest_path(quarter: Quarter) -> Path:
    return paths.bronze("faers", "_manifest", f"{quarter}.json")


def table_path(table: str, quarter: Quarter) -> Path:
    return paths.bronze("faers", table, f"quarter={quarter}", "part-0.parquet")


def convert_zip(zip_path: Path, quarter: Quarter, source_url: str | None = None) -> dict[str, object]:
    """Convert an already-downloaded quarterly zip into bronze Parquet and write its manifest."""
    tables: dict[str, object] = {}
    with zipfile.ZipFile(zip_path) as zf:
        names = zf.namelist()
        for table in TABLES:
            member = find_member(names, table, quarter.short)
            if member is None:
                tables[table] = {"member": None, "rows": 0}
                continue
            stats = convert_member(zf, member, table, table_path(table, quarter))
            tables[table] = {"member": member, **stats.as_dict()}
        deleted_member = find_deleted_member(names)
        deleted = read_deleted_caseids(zf, deleted_member) if deleted_member else []
    deleted_path = paths.bronze("faers", "deleted", f"quarter={quarter}", "part-0.parquet")
    deleted_path.parent.mkdir(parents=True, exist_ok=True)
    pq.write_table(pa.table({"caseid": pa.array(deleted, pa.string())}), deleted_path)
    manifest = {
        "quarter": str(quarter),
        "layout": "AERS" if quarter.is_legacy_aers else "FAERS",
        "source_url": source_url,
        "zip_sha256": sha256_file(zip_path),
        "zip_bytes": zip_path.stat().st_size,
        "converted_at": datetime.now(UTC).isoformat(timespec="seconds"),
        "tables": tables,
        "deleted_caseids": len(deleted),
    }
    out = manifest_path(quarter)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(manifest, indent=2))
    return manifest


def ingest_quarter(quarter: Quarter, *, keep_raw: bool = False, force: bool = False) -> dict[str, object]:
    """Download one quarter, convert it to bronze, and delete the raw zip (unless keep_raw)."""
    if manifest_path(quarter).exists() and not force:
        return json.loads(manifest_path(quarter).read_text())
    zip_path = paths.raw_root() / "faers" / quarter.zip_name()
    if not zip_path.exists():
        download(quarter.url(), zip_path)
    try:
        return convert_zip(zip_path, quarter, source_url=quarter.url())
    finally:
        if not keep_raw:
            zip_path.unlink(missing_ok=True)
