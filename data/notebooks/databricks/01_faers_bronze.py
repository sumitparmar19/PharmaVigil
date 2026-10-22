# Databricks notebook source
# MAGIC %md
# MAGIC # PharmaVigil 01: FAERS bronze (full history)
# MAGIC Downloads every FAERS/AERS quarter from 2004Q1, converts each table to Parquet in the volume, and
# MAGIC deletes the raw zip. Quarters already converted are skipped, so the notebook can be re-run after a
# MAGIC time-out. Same code as `pv-data faers-bronze` in the repository.

# COMMAND ----------

dbutils.widgets.text("git_ref", "main", "Git branch or tag")
dbutils.widgets.text("catalog", "workspace", "Unity Catalog catalog")
dbutils.widgets.text("first", "2004Q1", "First quarter")
dbutils.widgets.text("last", "2026Q2", "Last quarter")
git_ref = dbutils.widgets.get("git_ref")

# COMMAND ----------

# MAGIC %pip install "pharmavigil-data @ git+https://github.com/sumitparmar19/Pharmacy-Managment.git@$git_ref#subdirectory=data"

# COMMAND ----------

import os

catalog = dbutils.widgets.get("catalog")
os.environ["PV_LAKE"] = f"/Volumes/{catalog}/pharmavigil/lake"
os.environ["PV_RAW"] = "/tmp/pharmavigil_raw"

from pharmavigil_data.faers.bronze import ingest_quarter
from pharmavigil_data.http import SourceUnavailable
from pharmavigil_data.quarters import quarter_range

results = []
for q in quarter_range(dbutils.widgets.get("first"), dbutils.widgets.get("last")):
    try:
        m = ingest_quarter(q)
        results.append((str(q), m["layout"], m["tables"]["demo"]["rows"], "ok"))
    except SourceUnavailable as exc:
        results.append((str(q), None, None, str(exc)))
    print(results[-1])

display(spark.createDataFrame(results, "quarter string, layout string, demo_rows long, status string"))
