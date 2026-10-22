"""Synthetic gold tables for tests: random reports with a few planted drug–event associations.
Nothing here is real data; it only exercises counting, scoring and evaluation code."""

from __future__ import annotations

from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pandas as pd

DRUGS = {f"rx{i}": f"drug{i}" for i in range(20)}
PTS = [f"pt{i}" for i in range(30)] + ["acute kidney injury", "rhabdomyolysis", "hepatic failure"]
# (drug, pt, extra probability): planted true associations.
PLANTED = [("rx1", "acute kidney injury", 0.35), ("rx2", "rhabdomyolysis", 0.30), ("rx3", "hepatic failure", 0.25)]


def make_gold(root: Path, n_reports: int = 4000, seed: int = 7) -> Path:
    rng = np.random.default_rng(seed)
    reports, drugs, reactions = [], [], []
    start = date(2019, 1, 1)
    for i in range(n_reports):
        pid = f"FAERS:{i}"
        reports.append({"primaryid": pid, "caseid": str(i), "fda_dt": start + timedelta(days=int(i % 2000)),
                        "age_band": "45-64", "sex": "F", "serious": bool(rng.random() < 0.4)})
        chosen = rng.choice(list(DRUGS), size=rng.integers(1, 4), replace=False)
        for j, rx in enumerate(chosen):
            drugs.append({"primaryid": pid, "rxcui": rx, "ingredient": DRUGS[rx],
                          "role": "PS" if j == 0 else "C", "mention_id": f"{pid}#{j}"})
        pts = set(rng.choice(PTS[:30], size=rng.integers(1, 3), replace=False))
        for rx, pt, p in PLANTED:
            if rx in chosen and rng.random() < p:
                pts.add(pt)
        reactions.extend({"primaryid": pid, "pt": pt} for pt in pts)
    for name, rows in (("report", reports), ("report_drug", drugs), ("report_reaction", reactions)):
        (root / name).mkdir(parents=True, exist_ok=True)
        pd.DataFrame(rows).to_parquet(root / name / "part-0.parquet", index=False)
    return root


def make_refsets(root: Path) -> Path:
    root.mkdir(parents=True, exist_ok=True)
    rows = [
        ("drug1", "AKI", 1), ("drug2", "RHABDO", 1), ("drug3", "ALI", 1),
        ("drug4", "AKI", 0), ("drug5", "RHABDO", 0), ("drug6", "ALI", 0), ("drug7", "AKI", 0),
        ("drug8", "ALI", 0), ("Unknownium", "AKI", 0),
    ]
    df = pd.DataFrame(rows, columns=["exposure_name", "outcome_name", "ground_truth"])
    df.insert(0, "reference_set", "omop")
    df.to_parquet(root / "omop.parquet", index=False)
    df.assign(reference_set="euadr").to_parquet(root / "euadr.parquet", index=False)
    return root


def write_outcome_map(path: Path) -> Path:
    path.write_text("outcome_name,pt\nAKI,acute kidney injury\nRHABDO,rhabdomyolysis\nALI,hepatic failure\n")
    return path
