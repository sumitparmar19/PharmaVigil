"""Drug-name keys: normalization rules, and the Spark twin producing identical keys."""

import pytest

from pharmavigil_data.names import name_key, split_ingredients

CASES = {
    "Lipitor 20mg": "LIPITOR",
    "AMOXICILLIN 250 MG/5ML": "AMOXICILLIN",
    "ROSIGLITAZONE (AVANDIA)": "ROSIGLITAZONE",
    "insulin glargine 100 IU/ML": "INSULIN GLARGINE",
    "Hydrocortisone 1% cream": "HYDROCORTISONE CREAM",
    "ATORVASTATIN CALCIUM.": "ATORVASTATIN CALCIUM",
}


@pytest.mark.parametrize(("raw", "key"), CASES.items())
def test_name_key(raw: str, key: str) -> None:
    assert name_key(raw) == key


def test_name_key_empty() -> None:
    assert name_key(None) is None
    assert name_key("(  )") is None
    assert name_key("10 MG") is None


def test_split_ingredients() -> None:
    assert split_ingredients("ACETAMINOPHEN\\HYDROCODONE BITARTRATE") == ["ACETAMINOPHEN", "HYDROCODONE BITARTRATE"]
    assert split_ingredients(None) == []


@pytest.mark.spark
def test_spark_twin_matches_python(spark) -> None:
    from pyspark.sql import functions as F

    from pharmavigil_data.faers.gold import spark_name_key

    df = spark.createDataFrame([(k,) for k in CASES], ["raw"])
    got = {r["raw"]: r["key"] for r in df.select("raw", spark_name_key(F.col("raw")).alias("key")).collect()}
    assert got == {raw: name_key(raw) for raw in CASES}
