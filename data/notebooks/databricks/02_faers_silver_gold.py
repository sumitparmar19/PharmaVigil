# Databricks notebook source
# MAGIC %md
# MAGIC # PharmaVigil 02: FAERS silver, RxNorm mapping, gold and quality
# MAGIC De-duplicates to one report per case, maps drug names to RxNorm ingredients (RxNav, cached),
# MAGIC builds the gold tables as Unity Catalog tables `<catalog>.pharmavigil.gold_faers_*`, and prints
# MAGIC the evidence JSON to copy into `analysis/m2/` (labelled "full: FAERS 2004Q1–<last>").

# COMMAND ----------

dbutils.widgets.text("git_ref", "main", "Git branch or tag")
dbutils.widgets.text("catalog", "workspace", "Unity Catalog catalog")
dbutils.widgets.text("top_names", "60000", "Drug names to map via RxNav")
git_ref = dbutils.widgets.get("git_ref")

# COMMAND ----------

# MAGIC %pip install "pharmavigil-data @ git+https://github.com/sumitparmar19/Pharmacy-Managment.git@$git_ref#subdirectory=data"

# COMMAND ----------

import json
import os

catalog = dbutils.widgets.get("catalog")
os.environ["PV_LAKE"] = f"/Volumes/{catalog}/pharmavigil/lake"
os.environ["PV_CATALOG"] = f"{catalog}.pharmavigil"

from pharmavigil_data.faers.silver import build_silver

silver = build_silver(spark)
print(json.dumps(silver, indent=2))

# COMMAND ----------

from pharmavigil_data import paths
from pharmavigil_data.faers.gold import name_frequencies
from pharmavigil_data.rxnorm import RxNavClient
from pharmavigil_data.tables import write_table

freq = name_frequencies(spark)
top = freq.limit(int(dbutils.widgets.get("top_names"))).collect()
client = RxNavClient(paths.lake_root() / "cache" / "rxnav.sqlite")
mappings = client.resolve_many(r["example"] for r in top)
write_table(spark.createDataFrame(client.export_rows()), "silver", "rxnorm_map")
print(f"resolved {len(mappings)} names with {client.api_calls} API calls")

# COMMAND ----------

from pharmavigil_data.faers.gold import build_gold
from pharmavigil_data.quality import gold_quality, manifest_summary
from pharmavigil_data.tables import read_table

gold = build_gold(spark, read_table(spark, "silver", "rxnorm_map"))
evidence = {"dedup": silver["dedup"], **gold, "quality": gold_quality(spark),
            "bronze_quarters": manifest_summary()}
print(json.dumps(evidence, indent=2, default=str))
