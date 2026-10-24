# Databricks notebook source
# MAGIC %md
# MAGIC # PharmaVigil 00: setup and download check
# MAGIC Installs the `pharmavigil-data` package from GitHub, creates the schema and volume, and checks
# MAGIC that this workspace can reach the public data sources (FDA FAERS, RxNav, VAERS). Run this first.
# MAGIC Not for clinical or regulatory use.

# COMMAND ----------

dbutils.widgets.text("git_ref", "main", "Git branch or tag")
dbutils.widgets.text("catalog", "workspace", "Unity Catalog catalog")
git_ref = dbutils.widgets.get("git_ref")
catalog = dbutils.widgets.get("catalog")

# COMMAND ----------

# MAGIC %pip install "pharmavigil-data @ git+https://github.com/sumitparmar19/PharmaVigil.git@$git_ref#subdirectory=data"

# COMMAND ----------

catalog = dbutils.widgets.get("catalog")
spark.sql(f"CREATE SCHEMA IF NOT EXISTS {catalog}.pharmavigil")
spark.sql(f"CREATE VOLUME IF NOT EXISTS {catalog}.pharmavigil.lake")
print(f"Volume ready: /Volumes/{catalog}/pharmavigil/lake")

# COMMAND ----------

import requests

checks = {
    "FAERS quarterly files": "https://fis.fda.gov/content/Exports/faers_ascii_2025q1.zip",
    "RxNav API": "https://rxnav.nlm.nih.gov/REST/version.json",
    "VAERS data page": "https://vaers.hhs.gov/data/datasets.html",
}
for name, url in checks.items():
    try:
        status = requests.head(url, timeout=30, allow_redirects=True).status_code
    except Exception as exc:  # report every failure; nothing else depends on this cell
        status = f"failed: {exc}"
    print(f"{name:25s} {status}")
