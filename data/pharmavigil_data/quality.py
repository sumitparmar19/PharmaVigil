"""Data-quality report for the FAERS gold tables: counts per quarter, parse repairs, null and
unknown rates, PT vocabulary size, date ranges, and whether every reference-set outcome PT exists in
the FAERS vocabulary. Written as JSON under analysis/ so every number has a committed source."""

from __future__ import annotations

import json
from pathlib import Path

from pyspark.sql import SparkSession
from pyspark.sql import functions as F

from . import paths
from .refsets import outcome_pt_map
from .tables import read_table


def manifest_summary() -> list[dict[str, object]]:
    rows = []
    for path in sorted(paths.bronze("faers", "_manifest").glob("*.json")):
        m = json.loads(path.read_text())
        tables = m["tables"]
        rows.append({
            "quarter": m["quarter"],
            "layout": m["layout"],
            "demo_rows": tables["demo"]["rows"],
            "drug_rows": tables["drug"]["rows"],
            "reac_rows": tables["reac"]["rows"],
            "repaired_rows": sum(int(t.get("repaired_rows", 0)) for t in tables.values()),
            "dropped_rows": sum(int(t.get("dropped_rows", 0)) for t in tables.values()),
            "deleted_caseids": m["deleted_caseids"],
            "zip_sha256": m["zip_sha256"],
        })
    return rows


def gold_quality(spark: SparkSession) -> dict[str, object]:
    report = read_table(spark, "gold", "faers", "report")
    reaction = read_table(spark, "gold", "faers", "report_reaction")
    drug = read_table(spark, "gold", "faers", "report_drug")
    n = report.count()
    agg = report.agg(
        F.min("fda_dt").alias("min_fda_dt"), F.max("fda_dt").alias("max_fda_dt"),
        F.avg((F.col("age_band") == "unknown").cast("double")).alias("age_unknown_rate"),
        F.avg((F.col("sex") == "UNK").cast("double")).alias("sex_unknown_rate"),
        F.avg(F.col("event_dt").isNull().cast("double")).alias("event_dt_null_rate"),
        F.avg(F.col("serious").cast("double")).alias("serious_rate"),
    ).first()
    per_quarter = {
        r["quarter"]: r["count"] for r in report.groupBy("quarter").count().orderBy("quarter").collect()
    }
    vocab = {r["pt"] for r in reaction.select("pt").distinct().collect()}
    outcome_pts = outcome_pt_map()
    missing = sorted({pt for pt in outcome_pts["pt"] if pt not in vocab})
    reports_with_drug = drug.select("primaryid").distinct().count()
    return {
        "unique_cases": n,
        "unique_cases_by_kept_version_quarter": per_quarter,
        "fda_dt_range": [str(agg["min_fda_dt"]), str(agg["max_fda_dt"])],
        "age_unknown_rate": agg["age_unknown_rate"],
        "sex_unknown_rate": agg["sex_unknown_rate"],
        "event_dt_null_rate": agg["event_dt_null_rate"],
        "serious_rate": agg["serious_rate"],
        "pt_vocabulary_size": len(vocab),
        "reaction_rows": reaction.count(),
        "drug_rows": drug.count(),
        "reports_with_mapped_drug": reports_with_drug,
        "reference_outcome_pts_missing_from_faers": missing,
    }


def write_report(report: dict[str, object], out: Path) -> None:
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, indent=2, default=str))
