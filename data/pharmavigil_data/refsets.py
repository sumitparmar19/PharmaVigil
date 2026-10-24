"""OMOP and EU-ADR reference sets: known positive and negative drug-outcome pairs used to score
signal-detection methods (AUC). Downloaded from OHDSI's MethodEvaluation repository at a pinned
commit, parsed from R data files, and joined to:
- RxNorm ingredients, by ingredient name (the sets use OMOP concept IDs, not RxCUIs), and
- a hand-made MedDRA PT list per outcome (`resources/outcome_pt_map.csv`, documented in ADR-005).
"""

from __future__ import annotations

import csv
from importlib import resources
from pathlib import Path

import pandas as pd
import rdata

from . import paths
from .http import download, sha256_file

OHDSI_REPO = "OHDSI/MethodEvaluation"
OHDSI_COMMIT = "d7305910f6c6a390a9e423ffc1685a97c017183e"  # MethodEvaluation 2.4.2 (2026-09-18)
REFERENCE_SETS = {"omop": "omopReferenceSet", "euadr": "euadrReferenceSet"}


def source_url(name: str, commit: str = OHDSI_COMMIT) -> str:
    return f"https://raw.githubusercontent.com/{OHDSI_REPO}/{commit}/data/{REFERENCE_SETS[name]}.rda"


def read_rda(path: Path) -> pd.DataFrame:
    """Parse one MethodEvaluation .rda file into a tidy frame."""
    converted = rdata.conversion.convert(rdata.parser.parse_file(path))
    (frame,) = converted.values()
    df = pd.DataFrame(frame)
    out = pd.DataFrame({
        "exposure_id": df["exposureId"].astype("int64"),
        "exposure_name": df["exposureName"].astype(str).str.strip(),
        "outcome_id": df["outcomeId"].astype("int64"),
        "outcome_name": df["outcomeName"].astype(str),
        "ground_truth": df["groundTruth"].astype("int64"),
    })
    return out.reset_index(drop=True)


def outcome_pt_map() -> pd.DataFrame:
    text = resources.files("pharmavigil_data.resources").joinpath("outcome_pt_map.csv").read_text()
    rows = list(csv.DictReader(text.splitlines()))
    return pd.DataFrame(rows)


def fetch(name: str) -> tuple[pd.DataFrame, dict[str, str]]:
    """Download (or reuse) one reference set and return it with provenance."""
    raw = paths.raw_root() / "refsets" / f"{REFERENCE_SETS[name]}.rda"
    url = source_url(name)
    if not raw.exists():
        download(url, raw)
    df = read_rda(raw)
    df.insert(0, "reference_set", name)
    return df, {"url": url, "commit": OHDSI_COMMIT, "sha256": sha256_file(raw)}


def summarize(df: pd.DataFrame) -> dict[str, object]:
    return {
        "pairs": int(len(df)),
        "positives": int((df["ground_truth"] == 1).sum()),
        "negatives": int((df["ground_truth"] == 0).sum()),
        "outcomes": int(df["outcome_name"].nunique()),
        "drugs": int(df["exposure_name"].nunique()),
    }
