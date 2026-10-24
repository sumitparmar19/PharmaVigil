"""Phase 2 (P1) outputs built from the gold and silver FAERS tables: the data-count summary committed as
analysis/p1/data_counts.json, and the demo slice that the live demo replays.

The demo slice is a sample of real FAERS reports from the newest quarters, written as JSON lines in the shape
intake-gateway's replay reads (one report per line: age, sex, reporter type, country, dates, drugs with role,
route and dose text, reaction terms as FDA publishes them, outcome codes). FAERS is public and already
de-identified by FDA; the slice keeps only these fields and no free text, so nothing in it can identify a person.
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

from pyspark.sql import DataFrame, SparkSession
from pyspark.sql import functions as F

from .quality import manifest_summary
from .tables import read_table

# FAERS occupation code → the reporter types intake understands (E2B(R3) reporter qualification).
REPORTER_TYPE = {"MD": "PHYSICIAN", "PH": "PHARMACIST", "OT": "OTHER_HCP", "HP": "OTHER_HCP", "LW": "LAWYER",
                 "CN": "CONSUMER"}
ROLE = {"PS": "PS", "SS": "SS", "C": "C", "I": "I"}
MAX_DRUGS, MAX_REACTIONS = 20, 20


def _gold(spark: SparkSession) -> tuple[DataFrame, DataFrame, DataFrame]:
    return (read_table(spark, "gold", "faers", "report"), read_table(spark, "gold", "faers", "report_drug"),
            read_table(spark, "gold", "faers", "report_reaction"))


def data_counts(spark: SparkSession, scope: str) -> dict[str, object]:
    """Counts at every layer, with the quarters loaded and each source zip's SHA-256."""
    report, drug, reaction = _gold(spark)
    bronze = manifest_summary()
    ps = drug.where((F.col("role") == "PS") & F.col("rxcui").isNotNull()).select("primaryid", "ingredient")
    pairs_ps = ps.join(reaction, on="primaryid").select("ingredient", "pt").distinct().count()
    any_role = drug.where(F.col("rxcui").isNotNull()).select("primaryid", "ingredient")
    pairs_any = any_role.join(reaction, on="primaryid").select("ingredient", "pt").distinct().count()
    return {
        "scope": scope,
        "quarters": [b["quarter"] for b in bronze],
        "faers_report_rows": sum(int(b["demo_rows"]) for b in bronze),
        "unique_cases_after_dedup": report.count(),
        "drug_mentions": drug.count(),
        "drug_mentions_mapped_to_rxnorm": drug.where(F.col("rxcui").isNotNull()).count(),
        "distinct_ingredients": drug.where(F.col("rxcui").isNotNull()).select("rxcui").distinct().count(),
        "reaction_rows": reaction.count(),
        "distinct_pts": reaction.select("pt").distinct().count(),
        "drug_event_pairs_primary_suspect": pairs_ps,
        "drug_event_pairs_any_role": pairs_any,
        "serious_share": report.agg(F.avg(F.col("serious").cast("double"))).first()[0],
        "bronze_quarters_with_zip_sha256": bronze,
    }


def build_demo_slice(spark: SparkSession, out: Path, quarters: list[str], n_reports: int,
                     seed: int = 20260) -> dict[str, object]:
    """Sample `n_reports` de-duplicated FAERS cases from `quarters` and write replay_reports.jsonl."""
    # Only reports whose reporter occupation FAERS records, so the E2B(R3) reporter qualification is known.
    silver = (read_table(spark, "silver", "faers", "report").where(F.col("quarter").isin(quarters))
              .where(F.upper(F.trim("occp_cod")).isin(list(REPORTER_TYPE))))
    sample = silver.orderBy(F.rand(seed)).limit(n_reports).cache()
    keys = sample.select("report_key")
    drugs = (
        read_table(spark, "silver", "faers", "drug").join(keys, on="report_key", how="left_semi")
        .withColumn("name", F.coalesce(F.trim("prod_ai"), F.trim("drugname")))
        .where(F.col("name").isNotNull() & (F.length("name") > 1))
        .groupBy("report_key")
        .agg(F.slice(F.sort_array(F.collect_list(F.struct(
            F.col("drug_seq").cast("int").alias("seq"), F.upper("name").alias("name"),
            F.upper(F.trim("role_cod")).alias("role"), F.trim("route").alias("route"),
            F.trim("dose_vbm").alias("dose")))), 1, MAX_DRUGS).alias("drugs"))
    )
    reacs = (
        read_table(spark, "silver", "faers", "reac").join(keys, on="report_key", how="left_semi")
        .groupBy("report_key").agg(F.slice(F.sort_array(F.collect_set("pt")), 1, MAX_REACTIONS).alias("pts"))
    )
    rows = sample.join(drugs, on="report_key").join(reacs, on="report_key").collect()
    out.mkdir(parents=True, exist_ok=True)
    target = out / "replay_reports.jsonl"
    with target.open("w") as f:
        for r in rows:
            f.write(json.dumps({
                "faers_primaryid": r["primaryid"], "faers_caseid": r["caseid"], "quarter": r["quarter"],
                "fda_date": str(r["fda_date"]) if r["fda_date"] else None,
                "event_date": str(r["event_date"]) if r["event_date"] else None,
                "age_years": round(float(r["age_years"]), 1) if r["age_years"] is not None else None,
                "sex": r["sex"], "reporter_type": REPORTER_TYPE[r["occp_cod"].strip().upper()],
                "country": (r["reporter_country"] or r["occr_country"] or "")[:2].upper() or None,
                "outcomes": list(r["outcomes"] or []),
                "drugs": [{"name": d["name"][:200], "role": ROLE.get(d["role"], "C"),
                           "route": (d["route"] or None) and d["route"][:60],
                           "dose": (d["dose"] or None) and d["dose"][:200]} for d in r["drugs"]],
                "reactions": list(r["pts"]),
            }, ensure_ascii=False) + "\n")
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    meta = {"quarters": quarters, "requested": n_reports, "written": len(rows), "file": target.name,
            "sha256": digest, "seed": seed,
            "fields": ["age_years", "sex", "reporter_type", "country", "fda_date", "event_date", "outcomes",
                       "drugs(name, role, route, dose)", "reactions"]}
    (out / "replay_meta.json").write_text(json.dumps(meta, indent=1))
    return meta
