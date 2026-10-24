"""Reader for FAERS/AERS quarterly ASCII files ($-delimited text inside the quarterly zip).

The files have a header row, use `$` as the delimiter, no quoting, and Latin-1 text. A small number
of rows are broken across two physical lines (an embedded newline in a free-text field); the reader
re-joins them and counts every repair and every row it had to drop, so data quality is reported
rather than hidden. Output is written straight to Parquet in batches to keep memory flat.
"""

from __future__ import annotations

import io
import re
import zipfile
from collections.abc import Iterable, Iterator
from dataclasses import dataclass, field
from pathlib import Path

import pyarrow as pa
import pyarrow.parquet as pq

from .schema import CANONICAL, TABLES, canonical_name

BATCH_ROWS = 200_000
MAX_JOINED_LINES = 4

_DELETED_RE = re.compile(r"(^|/)dele\w*\.txt$", re.IGNORECASE)


@dataclass
class ParseStats:
    rows: int = 0
    repaired_rows: int = 0
    dropped_rows: int = 0
    missing_columns: list[str] = field(default_factory=list)

    def as_dict(self) -> dict[str, object]:
        return {
            "rows": self.rows,
            "repaired_rows": self.repaired_rows,
            "dropped_rows": self.dropped_rows,
            "missing_columns": self.missing_columns,
        }


def find_member(names: Iterable[str], table: str, short_quarter: str) -> str | None:
    """Find the zip member for one table, e.g. ASCII/DEMO19Q1.txt (any case, any folder)."""
    pattern = re.compile(rf"(^|/){table}{short_quarter}\.txt$", re.IGNORECASE)
    matches = [n for n in names if pattern.search(n)]
    return sorted(matches)[0] if matches else None


def find_deleted_member(names: Iterable[str]) -> str | None:
    """The list of case IDs FDA deleted this quarter (present in newer releases only)."""
    matches = [n for n in names if _DELETED_RE.search(n)]
    return sorted(matches)[0] if matches else None


def _split(line: str) -> list[str]:
    return line.rstrip("\r\n").split("$")


def iter_records(lines: Iterator[str], n_fields: int, stats: ParseStats) -> Iterator[list[str]]:
    """Yield rows with exactly n_fields values, re-joining rows split by embedded newlines."""
    pending: list[str] | None = None
    joined = 0
    for line in lines:
        fields = _split(line)
        if pending is not None:
            # Continue a broken row: the first new fragment belongs to the last open field.
            pending[-1] = pending[-1] + " " + fields[0]
            pending.extend(fields[1:])
            fields = pending
            joined += 1
        # Many files end every row with a trailing "$", which yields one extra empty field.
        while len(fields) > n_fields and fields[-1] == "":
            fields.pop()
        if len(fields) == n_fields:
            if pending is not None:
                stats.repaired_rows += 1
            pending, joined = None, 0
            stats.rows += 1
            yield fields
        elif len(fields) < n_fields and joined < MAX_JOINED_LINES:
            pending = fields
        else:
            stats.dropped_rows += 1
            pending, joined = None, 0
    if pending is not None:
        stats.dropped_rows += 1


def convert_member(zf: zipfile.ZipFile, member: str, table: str, out_path: Path) -> ParseStats:
    """Convert one table file from the zip into a Parquet file with the canonical columns."""
    if table not in TABLES:
        raise ValueError(f"unknown FAERS table {table}")
    canonical = CANONICAL[table]
    stats = ParseStats()
    with zf.open(member) as raw:
        text = io.TextIOWrapper(raw, encoding="latin-1", newline="")
        header = [canonical_name(h) for h in _split(next(text))]
        while header and header[-1] == "":
            header.pop()
        positions = {name: i for i, name in enumerate(header)}
        stats.missing_columns = [c for c in canonical if c not in positions]
        schema = pa.schema([(c, pa.string()) for c in canonical])
        out_path.parent.mkdir(parents=True, exist_ok=True)
        with pq.ParquetWriter(out_path, schema, compression="zstd") as writer:
            columns: dict[str, list[str | None]] = {c: [] for c in canonical}
            count = 0
            for rec in iter_records(text, len(header), stats):
                for c in canonical:
                    i = positions.get(c)
                    value = rec[i].strip() if i is not None else ""
                    columns[c].append(value or None)
                count += 1
                if count == BATCH_ROWS:
                    writer.write_table(pa.table(columns, schema=schema))
                    columns = {c: [] for c in canonical}
                    count = 0
            if count or stats.rows == 0:
                writer.write_table(pa.table(columns, schema=schema))
    return stats


def read_deleted_caseids(zf: zipfile.ZipFile, member: str) -> list[str]:
    """Case IDs listed in a quarter's deletion file (one per line; non-numeric lines ignored)."""
    with zf.open(member) as raw:
        text = io.TextIOWrapper(raw, encoding="latin-1")
        ids = (line.strip().rstrip("$").strip() for line in text)
        return [i for i in ids if i.isdigit()]
