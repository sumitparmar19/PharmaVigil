"""Read and write the silver/gold tables either as Delta folders under the local lake (default) or
as Unity Catalog tables on Databricks (set PV_CATALOG, e.g. `workspace.pharmavigil`). The transforms
call only these two functions, so the same code runs in both places."""

from __future__ import annotations

import os

from pyspark.sql import DataFrame, SparkSession

from . import paths


def _catalog() -> str | None:
    return os.environ.get("PV_CATALOG") or None


def write_table(df: DataFrame, layer: str, *name: str) -> None:
    catalog = _catalog()
    writer = df.write.format("delta").mode("overwrite").option("overwriteSchema", "true")
    if catalog:
        writer.saveAsTable(f"{catalog}.{layer}_{'_'.join(name)}")
    else:
        writer.save(str(paths.lake_root().joinpath(layer, *name)))


def read_table(spark: SparkSession, layer: str, *name: str) -> DataFrame:
    catalog = _catalog()
    if catalog:
        return spark.table(f"{catalog}.{layer}_{'_'.join(name)}")
    return spark.read.format("delta").load(str(paths.lake_root().joinpath(layer, *name)))


def table_exists(spark: SparkSession, layer: str, *name: str) -> bool:
    catalog = _catalog()
    if catalog:
        return bool(spark.catalog.tableExists(f"{catalog}.{layer}_{'_'.join(name)}"))
    return paths.lake_root().joinpath(layer, *name, "_delta_log").exists()
