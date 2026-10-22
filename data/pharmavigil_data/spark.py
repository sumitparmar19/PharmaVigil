"""Local Spark session with Delta Lake, configured the same way the Databricks notebooks run the
transforms (Databricks provides its own session; locally we build one)."""

from __future__ import annotations

import os

from delta import configure_spark_with_delta_pip
from pyspark.sql import SparkSession


def get_spark(app_name: str = "pharmavigil", cores: str | None = None) -> SparkSession:
    active = SparkSession.getActiveSession()
    if active is not None:
        return active
    builder = (
        SparkSession.builder.master(f"local[{cores or os.environ.get('PV_SPARK_CORES', '*')}]")
        .appName(app_name)
        .config("spark.sql.extensions", "io.delta.sql.DeltaSparkSessionExtension")
        .config("spark.sql.catalog.spark_catalog", "org.apache.spark.sql.delta.catalog.DeltaCatalog")
        .config("spark.driver.memory", os.environ.get("PV_SPARK_DRIVER_MEMORY", "6g"))
        .config("spark.sql.shuffle.partitions", os.environ.get("PV_SPARK_SHUFFLE_PARTITIONS", "16"))
        .config("spark.sql.session.timeZone", "UTC")
        .config("spark.ui.enabled", "false")
        .config("spark.ui.showConsoleProgress", "false")
    )
    spark = configure_spark_with_delta_pip(builder).getOrCreate()
    spark.sparkContext.setLogLevel("ERROR")
    return spark
