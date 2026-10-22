"""Silver layer for FAERS (Spark): typed, de-duplicated reports.

Steps, in order:
1. Read every bronze quarter; tag each row with its layout (AERS before 2012Q4, FAERS after) and a
   report key `<layout>:<primaryid>` so legacy ISR numbers can never collide with FAERS primaryids.
2. Drop exact repeats of the same report key (a report re-published in a later quarter).
3. Drop cases FDA deleted (the quarterly deletion lists).
4. Keep one report per case: the highest case version, then the latest FDA receipt date, then the
   highest report id (FDA's recommended rule). AERS rows have no case version and sort below FAERS.
5. Derive age in years, an age band, sex, seriousness and the outcome list.

Counts before and after each step are returned so reports can state both (Amendment 1, item 5).
"""

from __future__ import annotations

from dataclasses import dataclass

from pyspark.sql import Column, DataFrame, SparkSession, Window
from pyspark.sql import functions as F

from .. import paths
from ..quarters import FIRST_FAERS_QUARTER, Quarter
from ..tables import write_table

AGE_UNIT_YEARS = {"YR": 1.0, "DEC": 10.0, "MON": 1 / 12, "WK": 1 / 52, "DY": 1 / 365.25, "HR": 1 / 8766}
AGE_BANDS = ((0, 2, "0-1"), (2, 12, "2-11"), (12, 18, "12-17"), (18, 45, "18-44"),
             (45, 65, "45-64"), (65, 75, "65-74"), (75, 130, "75+"))


@dataclass
class DedupCounts:
    report_rows: int
    unique_report_keys: int
    after_deleted_cases: int
    unique_cases: int

    def as_dict(self) -> dict[str, int]:
        return self.__dict__.copy()


def read_bronze(spark: SparkSession, table: str) -> DataFrame:
    df = spark.read.parquet(str(paths.bronze("faers", table)))
    is_legacy = _quarter_sort(F.col("quarter")) < _quarter_value(FIRST_FAERS_QUARTER)
    layout = F.when(is_legacy, "AERS").otherwise("FAERS")
    return df.withColumn("layout", layout).withColumn(
        "report_key", F.concat_ws(":", F.col("layout"), F.col("primaryid"))
    )


def _quarter_value(q: str) -> int:
    parsed = Quarter.parse(q)
    return parsed.year * 10 + parsed.q


def _quarter_sort(col: Column) -> Column:
    return F.substring(col, 1, 4).cast("int") * 10 + F.substring(col, 6, 1).cast("int")


def parse_faers_date(col: Column) -> Column:
    """FAERS dates are yyyyMMdd, yyyyMM or yyyy; partial dates map to the first day."""
    s = F.trim(col)
    return (
        F.when(F.length(s) == 8, F.to_date(s, "yyyyMMdd"))
        .when(F.length(s) == 6, F.to_date(F.concat(s, F.lit("01")), "yyyyMMdd"))
        .when(F.length(s) == 4, F.to_date(F.concat(s, F.lit("0101")), "yyyyMMdd"))
    )


def age_years(age: Column, age_cod: Column) -> Column:
    value = F.regexp_replace(age, ",", ".").cast("double")
    expr = F.lit(None).cast("double")
    for unit, factor in AGE_UNIT_YEARS.items():
        expr = F.when(F.upper(age_cod) == unit, value * F.lit(factor)).otherwise(expr)
    # Implausible ages are treated as unknown rather than guessed.
    return F.when((expr >= 0) & (expr < 130), expr)


def age_band(years: Column) -> Column:
    expr = F.lit("unknown")
    for lo, hi, label in reversed(AGE_BANDS):
        expr = F.when((years >= lo) & (years < hi), label).otherwise(expr)
    return F.when(years.isNull(), "unknown").otherwise(expr)


def normalize_sex(sex: Column) -> Column:
    s = F.upper(F.trim(sex))
    return F.when(s.isin("M", "MALE"), "M").when(s.isin("F", "FEMALE"), "F").otherwise("UNK")


def deduplicate(demo: DataFrame, deleted: DataFrame) -> tuple[DataFrame, DedupCounts]:
    """Apply steps 2-4 and return one row per case plus the counts at each step."""
    report_rows = demo.count()

    by_key = Window.partitionBy("report_key").orderBy(_quarter_sort(F.col("quarter")).desc())
    unique_keys = demo.withColumn("_rn", F.row_number().over(by_key)).where("_rn = 1").drop("_rn")
    n_unique_keys = unique_keys.count()

    deleted_ids = deleted.select(F.trim("caseid").alias("caseid")).distinct()
    kept = unique_keys.join(deleted_ids, on="caseid", how="left_anti")
    n_after_deleted = kept.count()

    by_case = Window.partitionBy("caseid").orderBy(
        F.coalesce(F.col("caseversion").cast("int"), F.lit(-1)).desc(),
        F.col("fda_date").desc_nulls_last(),
        F.col("primaryid").cast("long").desc(),
    )
    latest = kept.withColumn("_rn", F.row_number().over(by_case)).where("_rn = 1").drop("_rn")
    n_cases = latest.count()
    return latest, DedupCounts(report_rows, n_unique_keys, n_after_deleted, n_cases)


def build_silver(spark: SparkSession, write: bool = True) -> dict[str, object]:
    demo = read_bronze(spark, "demo").withColumn("fda_date", parse_faers_date(F.col("fda_dt")))
    deleted = spark.read.parquet(str(paths.bronze("faers", "deleted")))
    reports, counts = deduplicate(demo, deleted)

    outc = read_bronze(spark, "outc").select("report_key", F.upper(F.trim("outc_cod")).alias("outc_cod"))
    outcomes = outc.groupBy("report_key").agg(F.sort_array(F.collect_set("outc_cod")).alias("outcomes"))

    years = age_years(F.col("age"), F.col("age_cod"))
    reports = (
        reports.join(outcomes, on="report_key", how="left")
        .withColumn("outcomes", F.coalesce(F.col("outcomes"), F.array().cast("array<string>")))
        .withColumn("serious", F.size("outcomes") > 0)
        .withColumn("age_years", years)
        .withColumn("age_band", age_band(years))
        .withColumn("sex", normalize_sex(F.col("sex")))
        .withColumn("event_date", parse_faers_date(F.col("event_dt")))
        .withColumn("init_fda_date", F.coalesce(parse_faers_date(F.col("init_fda_dt")), F.col("fda_date")))
        .select(
            "report_key", "primaryid", "caseid", "caseversion", "layout", "quarter", "fda_date",
            "init_fda_date", "event_date", "age_years", "age_band", "sex", "serious", "outcomes",
            "rept_cod", "occp_cod", "reporter_country", "occr_country",
        )
    )
    keys = reports.select("report_key")
    drug = read_bronze(spark, "drug").join(keys, on="report_key", how="left_semi")
    reac = read_bronze(spark, "reac").join(keys, on="report_key", how="left_semi")
    reac = reac.withColumn("pt", F.lower(F.trim("pt"))).where(F.col("pt").isNotNull() & (F.col("pt") != ""))

    if write:
        for name, df in (("report", reports), ("drug", drug), ("reac", reac)):
            write_table(df, "silver", "faers", name)
    return {"dedup": counts.as_dict()}
