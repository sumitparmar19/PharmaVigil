"""Every version of every case (not just the latest), summarized for the duplicate-detection model.

Gold keeps one report per case. The duplicate model needs earlier versions too: two versions of the
same case are the training proxy for "two reports of the same event" (the model card says it is a
proxy). One row per report version: case id, dates, demographics, reporter, the set of drug keys
(RxNorm ingredient when mapped, otherwise the normalized name) and the set of PTs.
"""

from __future__ import annotations

from pyspark.sql import DataFrame, SparkSession
from pyspark.sql import functions as F

from .. import paths
from ..tables import read_table, table_exists, write_table
from .gold import drug_candidates
from .silver import age_band, age_years, normalize_sex, parse_faers_date, read_bronze


def build_versions(spark: SparkSession, write: bool = True) -> DataFrame:
    demo = read_bronze(spark, "demo")
    deleted = spark.read.parquet(str(paths.bronze("faers", "deleted")))
    demo = demo.join(deleted.select(F.trim("caseid").alias("caseid")).distinct(), on="caseid", how="left_anti")
    demo = demo.dropDuplicates(["report_key"])
    years = age_years(F.col("age"), F.col("age_cod"))
    versions = demo.select(
        "report_key", "caseid", F.col("caseversion").cast("int").alias("caseversion"), "layout",
        parse_faers_date(F.col("fda_dt")).alias("fda_date"), parse_faers_date(F.col("event_dt")).alias("event_date"),
        age_band(years).alias("age_band"), normalize_sex(F.col("sex")).alias("sex"),
        "reporter_country", "occp_cod",
    )
    cands = drug_candidates(read_bronze(spark, "drug")).where(F.col("name_key").isNotNull())
    if table_exists(spark, "silver", "rxnorm_map"):
        rx = read_table(spark, "silver", "rxnorm_map").select("name_key", "rxcui")
        cands = cands.join(rx, on="name_key", how="left")
    else:
        cands = cands.withColumn("rxcui", F.lit(None).cast("string"))
    drug_keys = cands.select("report_key", F.coalesce(F.col("rxcui"), F.concat(F.lit("name:"), "name_key")).alias("k"))
    drug_sets = drug_keys.groupBy("report_key").agg(F.sort_array(F.collect_set("k")).alias("drugs"))
    pts = read_bronze(spark, "reac").select("report_key", F.lower(F.trim("pt")).alias("pt"))
    pt_sets = pts.groupBy("report_key").agg(F.sort_array(F.collect_set("pt")).alias("pts"))
    out = versions.join(drug_sets, "report_key", "left").join(pt_sets, "report_key", "left")
    if write:
        write_table(out, "silver", "faers", "versions")
    return out

