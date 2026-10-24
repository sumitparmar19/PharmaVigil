"""Gold layer for FAERS (Spark): the three analysis tables the models read.

- `report(primaryid, caseid, fda_dt, age_band, sex, serious, outcomes[], ...)`: one row per unique case
- `report_drug(primaryid, rxcui, role, ...)`: drug mentions normalized to RxNorm ingredients
- `report_reaction(primaryid, pt)`: MedDRA Preferred Terms as printed in FAERS (lower-cased)

`primaryid` in gold is the silver report key (`FAERS:<primaryid>` or `AERS:<isr>`), unique across
both layouts. Drug mentions use the active-ingredient field (prod_ai, split on backslashes) when
present, and the brand/drug name otherwise.
"""

from __future__ import annotations

from pyspark.sql import Column, DataFrame, SparkSession
from pyspark.sql import functions as F

from ..names import NAME_KEY_STEPS
from ..tables import read_table, write_table


def spark_name_key(col: Column) -> Column:
    """Spark twin of names.name_key (same regular expressions, applied in the same order)."""
    text = F.upper(col)
    for pattern, repl in NAME_KEY_STEPS:
        text = F.regexp_replace(text, pattern, repl)
    text = F.trim(text)
    return F.when(F.length(text) >= 2, text)


def drug_candidates(drug: DataFrame) -> DataFrame:
    """One row per (mention, candidate name): prod_ai ingredients, else the drug name."""
    names = F.when(
        F.col("prod_ai").isNotNull() & (F.trim("prod_ai") != ""),
        F.split(F.col("prod_ai"), r"\\"),
    ).otherwise(F.array(F.col("drugname")))
    return (
        drug.withColumn("mention_id", F.concat_ws("#", "report_key", F.coalesce("drug_seq", F.lit(""))))
        .withColumn("candidate", F.explode(names))
        .withColumn("name_key", spark_name_key(F.col("candidate")))
        .withColumn("role", F.upper(F.trim("role_cod")))
    )


def name_frequencies(spark: SparkSession) -> DataFrame:
    """Distinct drug name keys with how many mentions use them (drives which names to map first)."""
    drug = read_table(spark, "silver", "faers", "drug")
    return (
        drug_candidates(drug)
        .where(F.col("name_key").isNotNull())
        .groupBy("name_key")
        .agg(F.count("*").alias("mentions"), F.first("candidate").alias("example"))
        .orderBy(F.col("mentions").desc())
    )


def build_gold(spark: SparkSession, rxnorm_map: DataFrame, write: bool = True) -> dict[str, object]:
    silver_report = read_table(spark, "silver", "faers", "report")
    drug = read_table(spark, "silver", "faers", "drug")
    reac = read_table(spark, "silver", "faers", "reac")

    report = silver_report.select(
        F.col("report_key").alias("primaryid"), "caseid", "layout", "quarter",
        F.col("fda_date").alias("fda_dt"), F.col("init_fda_date").alias("init_fda_dt"),
        F.col("event_date").alias("event_dt"), "age_band", "sex", "serious", "outcomes",
        "reporter_country", "occp_cod",
    )

    candidates = drug_candidates(drug)
    mapped = candidates.join(rxnorm_map.select("name_key", "rxcui", "ingredient"), on="name_key", how="left")
    report_drug = (
        mapped.where(F.col("rxcui").isNotNull())
        .select(F.col("report_key").alias("primaryid"), "rxcui", "ingredient", "role", "mention_id")
        .dropDuplicates(["primaryid", "rxcui", "role", "mention_id"])
    )
    report_reaction = reac.select(F.col("report_key").alias("primaryid"), "pt").dropDuplicates()

    mention_mapped = mapped.groupBy("mention_id", "role").agg(
        F.max(F.col("rxcui").isNotNull().cast("int")).alias("mapped")
    )
    coverage = mention_mapped.agg(
        F.count("*").alias("mentions"),
        F.sum("mapped").alias("mapped_mentions"),
        F.sum(F.when(F.col("role") == "PS", 1).otherwise(0)).alias("ps_mentions"),
        F.sum(F.when(F.col("role") == "PS", F.col("mapped")).otherwise(0)).alias("ps_mapped_mentions"),
    ).first()

    if write:
        tables = {"report": report, "report_drug": report_drug, "report_reaction": report_reaction}
        for name, df in tables.items():
            write_table(df, "gold", "faers", name)
    mentions, mapped = int(coverage["mentions"] or 0), int(coverage["mapped_mentions"] or 0)
    ps_mentions, ps_mapped = int(coverage["ps_mentions"] or 0), int(coverage["ps_mapped_mentions"] or 0)
    return {
        "normalization_coverage": {
            "mentions": mentions,
            "mapped_mentions": mapped,
            "share_mapped": mapped / mentions if mentions else None,
            "ps_mentions": ps_mentions,
            "ps_mapped_mentions": ps_mapped,
            "ps_share_mapped": ps_mapped / ps_mentions if ps_mentions else None,
        }
    }
