"""Export the batch-computed signal scores for signal-service (M8).

signal-service does not read Delta or Parquet; it loads two CSV files and a JSON summary:

- `signals.csv`: one row per drug–PT pair with at least 3 primary-suspect reports: counts, PRR/ROR/IC with
  intervals, EBGM/EB05/EB95 and, when a SafetyFormer score table exists, the SafetyFormer score.
- `signal_trend.csv`: reports per year for the strongest pairs (by EB05), for the trend chart.
- `signals_meta.json`: data scope, row counts, the reference-set evaluation summary (AUCs from
  `baselines.json` when given) and the causation disclaimer.

Every number comes from the baseline and SafetyFormer runs that produced the input files; this module only
joins and reshapes them.
"""

from __future__ import annotations

import json
from pathlib import Path

import pandas as pd

from . import contingency as ct

DISCLAIMER = "Spontaneous reports do not prove that a drug caused a reaction."
COLUMNS = ["rxcui", "ingredient", "pt", "a", "n_drug", "n_event", "n_total", "prr", "prr_lo", "prr_hi", "ror",
           "ror_lo", "ror_hi", "ic", "ic025", "ic975", "ebgm", "eb05", "eb95", "safetyformer"]


def _evaluation(baselines_json: Path | None) -> dict[str, object] | None:
    if baselines_json is None or not baselines_json.exists():
        return None
    report = json.loads(baselines_json.read_text())
    out: dict[str, object] = {}
    for variant, v in report.get("variants", {}).items():
        for set_name, s in v.get("reference_sets", {}).items():
            out[f"{variant}/{set_name}"] = s
    return out


def export_signals(gold_dir: Path, signals_dir: Path, out_dir: Path, scope: str,
                   safetyformer_scores: Path | None = None, baselines_json: Path | None = None,
                   trend_pairs: int = 200) -> dict[str, object]:
    base = pd.read_parquet(signals_dir / "baselines_primary_suspect.parquet")
    con = ct.connect(gold_dir)
    base = base.merge(ct.ingredient_names(con), on="rxcui", how="left")
    if safetyformer_scores is not None and safetyformer_scores.exists():
        sf = pd.read_parquet(safetyformer_scores)[["rxcui", "pt", "safetyformer"]]
        base = base.merge(sf, on=["rxcui", "pt"], how="left")
    else:
        base["safetyformer"] = float("nan")
    for c in COLUMNS:
        if c not in base.columns:
            base[c] = float("nan")
    table = base[COLUMNS].sort_values(["eb05", "a"], ascending=False, kind="mergesort")
    out_dir.mkdir(parents=True, exist_ok=True)
    table.to_csv(out_dir / "signals.csv", index=False, float_format="%.6g")

    top = table.head(trend_pairs)[["rxcui", "pt"]]
    con.register("top_pairs", top)
    trend = con.execute("""
        SELECT d.rxcui, e.pt, year(r.fda_dt) AS year, count(DISTINCT d.primaryid) AS reports
        FROM src_report_drug d
        JOIN src_report_reaction e USING (primaryid)
        JOIN src_report r USING (primaryid)
        JOIN top_pairs t ON t.rxcui = d.rxcui AND t.pt = e.pt
        WHERE d.role = 'PS'
        GROUP BY 1, 2, 3 ORDER BY 1, 2, 3
    """).df()
    trend.to_csv(out_dir / "signal_trend.csv", index=False)

    meta = {
        "scope": scope,
        "pairs": int(len(table)),
        "trend_pairs": int(len(top)),
        "with_safetyformer": bool(table["safetyformer"].notna().any()),
        "evaluation": _evaluation(baselines_json),
        "disclaimer": DISCLAIMER,
    }
    (out_dir / "signals_meta.json").write_text(json.dumps(meta, indent=2))
    return meta
