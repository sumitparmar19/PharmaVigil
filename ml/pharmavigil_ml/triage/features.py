"""Report features for the seriousness and duplicate models.

Seriousness must be predicted from what an intake desk knows *before* anyone codes the outcome, so the
feature builder never reads the outcome fields (`serious`, `outcomes`) and drops every reaction term
that reveals an outcome (death, hospitalisation, life-threatening, disability, congenital anomaly...).
A test checks both rules (Amendment 1, item 5).

The feature layout is a `FeatureSpec` (JSON) so the Java case-service builds exactly the same vector
before calling the ONNX model.
"""

from __future__ import annotations

import json
import re
from collections.abc import Iterable, Sequence
from dataclasses import asdict, dataclass, field
from pathlib import Path

import numpy as np
import pandas as pd

# Reaction terms that state or imply a seriousness outcome. Matched case-insensitively anywhere in the PT.
OUTCOME_REVEALING = re.compile(
    r"death|died|dying|fatal|autopsy|euthanasia|hospitali[sz]|life[ -]?threatening|disabilit|"
    r"congenital|resuscitat|intensive care|coma\b",
    re.IGNORECASE,
)
# Columns the feature builder is never allowed to read.
FORBIDDEN_INPUTS = frozenset({"serious", "outcomes", "outc_cod"})

AGE_BANDS = ("0-1", "2-11", "12-17", "18-44", "45-64", "65-74", "75+", "unknown")
SEXES = ("M", "F", "UNK")


def is_outcome_revealing(pt: str) -> bool:
    return bool(OUTCOME_REVEALING.search(pt))


@dataclass
class FeatureSpec:
    drugs: list[str] = field(default_factory=list)
    pts: list[str] = field(default_factory=list)
    countries: list[str] = field(default_factory=list)
    occupations: list[str] = field(default_factory=list)

    def columns(self) -> list[str]:
        return (
            [f"age_band={a}" for a in AGE_BANDS] + [f"sex={s}" for s in SEXES]
            + [f"occp={o}" for o in self.occupations] + ["occp=other"]
            + [f"country={c}" for c in self.countries] + ["country=other"]
            + ["n_drugs", "n_primary_suspect", "n_pts"]
            + [f"drug={d}" for d in self.drugs] + [f"pt={p}" for p in self.pts]
        )

    def save(self, path: Path) -> None:
        path.write_text(json.dumps({**asdict(self), "columns": self.columns(),
                                    "outcome_revealing_pattern": OUTCOME_REVEALING.pattern}, indent=1))

    @classmethod
    def load(cls, path: Path) -> FeatureSpec:
        d = json.loads(path.read_text())
        return cls(d["drugs"], d["pts"], d["countries"], d["occupations"])


def _top(values: Iterable[str], k: int) -> list[str]:
    s = pd.Series(list(values)).dropna()
    return list(s.value_counts().head(k).index.astype(str))


def build_spec(reports: pd.DataFrame, drugs: pd.DataFrame, pts: pd.DataFrame, top_drugs: int = 300,
               top_pts: int = 500, top_countries: int = 30, top_occupations: int = 10) -> FeatureSpec:
    """Choose the most frequent drugs, non-revealing PTs, countries and reporter types."""
    safe_pts = pts.loc[~pts["pt"].map(is_outcome_revealing), "pt"]
    return FeatureSpec(
        drugs=_top(drugs["rxcui"], top_drugs),
        pts=_top(safe_pts, top_pts),
        countries=_top(reports["reporter_country"], top_countries),
        occupations=_top(reports["occp_cod"], top_occupations),
    )


def vectorize(reports: pd.DataFrame, drugs: pd.DataFrame, pts: pd.DataFrame, spec: FeatureSpec) -> np.ndarray:
    """Feature matrix (float32, one row per report in `reports` order).

    Reads only: primaryid, age_band, sex, occp_cod, reporter_country (reports); primaryid, rxcui, role
    (drugs); primaryid, pt (pts). Outcome columns are never touched even if present.
    """
    allowed = reports[["primaryid", "age_band", "sex", "occp_cod", "reporter_country"]]
    n = len(allowed)
    cols = spec.columns()
    pos = {c: i for i, c in enumerate(cols)}
    x = np.zeros((n, len(cols)), dtype=np.float32)
    row = {pid: i for i, pid in enumerate(allowed["primaryid"])}
    for i, r in enumerate(allowed.itertuples(index=False)):
        x[i, pos[f"age_band={r.age_band if r.age_band in AGE_BANDS else 'unknown'}"]] = 1
        x[i, pos[f"sex={r.sex if r.sex in SEXES else 'UNK'}"]] = 1
        occ = f"occp={r.occp_cod}"
        x[i, pos[occ if occ in pos else "occp=other"]] = 1
        ctry = f"country={r.reporter_country}"
        x[i, pos[ctry if ctry in pos else "country=other"]] = 1
    d = drugs[drugs["primaryid"].isin(row.keys())]
    for pid, rx, role in d[["primaryid", "rxcui", "role"]].itertuples(index=False):
        i = row[pid]
        x[i, pos["n_drugs"]] += 1
        if role == "PS":
            x[i, pos["n_primary_suspect"]] += 1
        c = f"drug={rx}"
        if c in pos:
            x[i, pos[c]] = 1
    p = pts[pts["primaryid"].isin(row.keys())]
    for pid, pt in p[["primaryid", "pt"]].itertuples(index=False):
        if is_outcome_revealing(pt):
            continue
        i = row[pid]
        x[i, pos["n_pts"]] += 1
        c = f"pt={pt}"
        if c in pos:
            x[i, pos[c]] = 1
    return x


def vectorize_one(age_band: str, sex: str, occp_cod: str | None, country: str | None,
                  drugs: Sequence[tuple[str, str]], pts: Sequence[str], spec: FeatureSpec) -> np.ndarray:
    """Single-report helper (also the reference the Java implementation is tested against)."""
    reports = pd.DataFrame([{"primaryid": "q", "age_band": age_band, "sex": sex, "occp_cod": occp_cod,
                             "reporter_country": country}])
    d = pd.DataFrame([{"primaryid": "q", "rxcui": r, "role": role} for r, role in drugs],
                     columns=["primaryid", "rxcui", "role"])
    p = pd.DataFrame([{"primaryid": "q", "pt": pt} for pt in pts], columns=["primaryid", "pt"])
    return vectorize(reports, d, p, spec)[0]
