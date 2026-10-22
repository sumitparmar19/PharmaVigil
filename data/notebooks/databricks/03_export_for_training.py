# Databricks notebook source
# MAGIC %md
# MAGIC # PharmaVigil 03: export gold tables for model training
# MAGIC Writes the three FAERS gold tables as Parquet files into the volume so they can be downloaded
# MAGIC (Catalog → Volumes → pharmavigil → lake → export) and attached to the Kaggle training notebooks.

# COMMAND ----------

dbutils.widgets.text("catalog", "workspace", "Unity Catalog catalog")
catalog = dbutils.widgets.get("catalog")
out = f"/Volumes/{catalog}/pharmavigil/lake/export"
for name in ("report", "report_drug", "report_reaction"):
    df = spark.table(f"{catalog}.pharmavigil.gold_faers_{name}")
    df.coalesce(8).write.mode("overwrite").parquet(f"{out}/{name}")
    print(name, df.count())
