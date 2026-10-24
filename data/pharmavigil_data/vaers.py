"""VAERS narratives with the MedDRA PTs human coders assigned: the training data for the MedDRA
auto-coder (Model B). Each yearly VAERS release has three CSV files (Latin-1):
VAERSDATA (one row per report, free text in SYMPTOM_TEXT), VAERSSYMPTOMS (up to five PTs per row,
several rows per report) and VAERSVAX (vaccines). Output: vaers_narrative(vaers_id, recv_date,
year, text, pts[]).
"""

from __future__ import annotations

import io
import re
import zipfile
from pathlib import Path

import pandas as pd

VAERS_DOWNLOAD_URL = "https://vaers.hhs.gov/eSubDownload/index.jsp?fn={year}VAERSData.zip"
_SYMPTOM_COLS = [f"SYMPTOM{i}" for i in range(1, 6)]


def _read_csv(source: io.BufferedIOBase | Path, usecols: list[str]) -> pd.DataFrame:
    return pd.read_csv(source, encoding="latin-1", usecols=usecols, dtype=str, keep_default_na=False)


def _member(names: list[str], suffix: str) -> str:
    pattern = re.compile(rf"(^|/)\d{{4}}{suffix}\.csv$|(^|/)NonDomestic{suffix}\.csv$", re.IGNORECASE)
    for n in names:
        if pattern.search(n):
            return n
    raise FileNotFoundError(f"no *{suffix}.csv in {names}")


def build_narratives(data: pd.DataFrame, symptoms: pd.DataFrame) -> pd.DataFrame:
    """Join narratives to their coded PTs (lower-cased, de-duplicated, sorted)."""
    long = symptoms.melt(id_vars=["VAERS_ID"], value_vars=_SYMPTOM_COLS, value_name="pt")
    long["pt"] = long["pt"].str.strip().str.lower()
    long = long[long["pt"] != ""]
    pts = long.groupby("VAERS_ID")["pt"].agg(lambda s: sorted(set(s))).rename("pts")
    df = data.rename(columns={"VAERS_ID": "vaers_id", "RECVDATE": "recv_date", "SYMPTOM_TEXT": "text"})
    df["text"] = df["text"].str.strip()
    df = df[df["text"] != ""]
    df = df.join(pts, on="vaers_id", how="inner")
    df["recv_date"] = pd.to_datetime(df["recv_date"], format="%m/%d/%Y", errors="coerce")
    df["year"] = df["recv_date"].dt.year.astype("Int64")
    return df[["vaers_id", "recv_date", "year", "text", "pts"]].reset_index(drop=True)


def read_zip(zip_path: Path) -> pd.DataFrame:
    with zipfile.ZipFile(zip_path) as zf:
        names = zf.namelist()
        with zf.open(_member(names, "VAERSDATA")) as fh:
            data = _read_csv(fh, ["VAERS_ID", "RECVDATE", "SYMPTOM_TEXT"])
        with zf.open(_member(names, "VAERSSYMPTOMS")) as fh:
            symptoms = _read_csv(fh, ["VAERS_ID", *_SYMPTOM_COLS])
    return build_narratives(data, symptoms)


def read_dir(directory: Path, year: int) -> pd.DataFrame:
    data = _read_csv(directory / f"{year}VAERSDATA.csv", ["VAERS_ID", "RECVDATE", "SYMPTOM_TEXT"])
    symptoms = _read_csv(directory / f"{year}VAERSSYMPTOMS.csv", ["VAERS_ID", *_SYMPTOM_COLS])
    return build_narratives(data, symptoms)
