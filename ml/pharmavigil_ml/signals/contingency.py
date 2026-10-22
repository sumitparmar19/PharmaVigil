"""Drug–event report counts from the FAERS gold tables, computed with DuckDB.

The gold tables are read from Delta folders (the local lake) or plain Parquet folders (the Databricks
export). Counts are over unique cases (gold `report` is already one row per case):

- N        reports in the background (reports with at least one mapped drug in the chosen roles)
- n_drug   reports mentioning the drug (in the chosen roles)
- n_event  reports with the event (a PT, or any PT of an outcome's PT list)
- a        reports with both

Two options shape every analysis: `roles` (primary suspect only, or all roles) and `before` (only
reports FDA received before a date, which the time-travel test uses so nothing from the future leaks).
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping
from datetime import date
from pathlib import Path

import duckdb
import pandas as pd

PRIMARY_SUSPECT = ("PS",)
ALL_ROLES = ("PS", "SS", "C", "I")


def _register(con: duckdb.DuckDBPyConnection, name: str, path: Path) -> None:
    if (path / "_delta_log").exists():
        from deltalake import DeltaTable

        con.register(name, DeltaTable(str(path)).to_pyarrow_dataset())
    else:
        con.execute(f"CREATE VIEW {name} AS SELECT * FROM read_parquet('{path.as_posix()}/**/*.parquet')")


def connect(gold_dir: Path) -> duckdb.DuckDBPyConnection:
    """Open DuckDB with views report / report_drug / report_reaction over a gold directory."""
    con = duckdb.connect()
    for name in ("report", "report_drug", "report_reaction"):
        _register(con, f"src_{name}", gold_dir / name)
    return con


def _prepare(con: duckdb.DuckDBPyConnection, roles: Iterable[str], before: date | None) -> None:
    roles_sql = ", ".join(f"'{r}'" for r in roles)
    date_filter = f"AND r.fda_dt < DATE '{before.isoformat()}'" if before else ""
    con.execute(f"""
        CREATE OR REPLACE TEMP TABLE drug_reports AS
        SELECT DISTINCT d.primaryid, d.rxcui
        FROM src_report_drug d JOIN src_report r USING (primaryid)
        WHERE d.role IN ({roles_sql}) {date_filter}
    """)
    con.execute("""
        CREATE OR REPLACE TEMP TABLE base_reports AS SELECT DISTINCT primaryid FROM drug_reports
    """)
    con.execute("""
        CREATE OR REPLACE TEMP TABLE event_reports AS
        SELECT DISTINCT e.primaryid, e.pt
        FROM src_report_reaction e JOIN base_reports b USING (primaryid)
    """)


def total_reports(con: duckdb.DuckDBPyConnection) -> int:
    return int(con.execute("SELECT count(*) FROM base_reports").fetchall()[0][0])


def pt_pair_counts(con: duckdb.DuckDBPyConnection, roles: Iterable[str] = PRIMARY_SUSPECT,
                   before: date | None = None, min_a: int = 1) -> pd.DataFrame:
    """Counts for every observed (rxcui, pt) pair with a ≥ min_a."""
    _prepare(con, roles, before)
    n_total = total_reports(con)
    df = con.execute(f"""
        WITH nd AS (SELECT rxcui, count(*) AS n_drug FROM drug_reports GROUP BY rxcui),
             ne AS (SELECT pt, count(*) AS n_event FROM event_reports GROUP BY pt),
             pa AS (SELECT d.rxcui, e.pt, count(*) AS a
                    FROM drug_reports d JOIN event_reports e USING (primaryid)
                    GROUP BY d.rxcui, e.pt HAVING count(*) >= {int(min_a)})
        SELECT pa.rxcui, pa.pt, pa.a, nd.n_drug, ne.n_event
        FROM pa JOIN nd USING (rxcui) JOIN ne USING (pt)
    """).df()
    df["n_total"] = n_total
    return df


def outcome_pair_counts(con: duckdb.DuckDBPyConnection, pairs: pd.DataFrame,
                        outcome_pts: Mapping[str, Iterable[str]], roles: Iterable[str] = PRIMARY_SUSPECT,
                        before: date | None = None) -> pd.DataFrame:
    """Counts for (rxcui, outcome) pairs where an outcome is a set of PTs (reference-set outcomes).

    `pairs` needs columns rxcui and outcome_name. Pairs whose drug is absent get n_drug = 0.
    """
    _prepare(con, roles, before)
    n_total = total_reports(con)
    groups = pd.DataFrame(
        [(o, pt.lower()) for o, pts in outcome_pts.items() for pt in pts], columns=["outcome_name", "pt"]
    )
    con.register("outcome_pts", groups)
    con.register("wanted", pairs[["rxcui", "outcome_name"]].drop_duplicates())
    df = con.execute("""
        WITH outcome_reports AS (
                SELECT DISTINCT g.outcome_name, e.primaryid
                FROM event_reports e JOIN outcome_pts g USING (pt)),
             ne AS (SELECT outcome_name, count(*) AS n_event FROM outcome_reports GROUP BY outcome_name),
             nd AS (SELECT rxcui, count(*) AS n_drug FROM drug_reports GROUP BY rxcui),
             pa AS (SELECT w.rxcui, w.outcome_name, count(o.primaryid) AS a
                    FROM wanted w
                    JOIN drug_reports d ON d.rxcui = w.rxcui
                    JOIN outcome_reports o ON o.primaryid = d.primaryid AND o.outcome_name = w.outcome_name
                    GROUP BY w.rxcui, w.outcome_name)
        SELECT w.rxcui, w.outcome_name,
               coalesce(pa.a, 0) AS a, coalesce(nd.n_drug, 0) AS n_drug, coalesce(ne.n_event, 0) AS n_event
        FROM wanted w
        LEFT JOIN pa ON pa.rxcui = w.rxcui AND pa.outcome_name = w.outcome_name
        LEFT JOIN nd ON nd.rxcui = w.rxcui
        LEFT JOIN ne ON ne.outcome_name = w.outcome_name
    """).df()
    df["n_total"] = n_total
    return df


def ingredient_names(con: duckdb.DuckDBPyConnection) -> pd.DataFrame:
    """(rxcui, ingredient) pairs present in gold, for mapping reference-set drug names."""
    return con.execute("""
        SELECT rxcui, lower(any_value(ingredient)) AS ingredient FROM src_report_drug GROUP BY rxcui
    """).df()
