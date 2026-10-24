"""Canonical column names for the FAERS tables, and the mapping from the older AERS layout.

FAERS (2012Q4 onward) and legacy AERS (2004Q1 to 2012Q3) name the same facts differently. The
bronze layer stores every quarter with these canonical lower-case columns so that later steps never
need to know which layout a row came from. Columns a layout does not have are filled with nulls.
"""

from __future__ import annotations

TABLES = ("demo", "drug", "reac", "outc", "rpsr", "ther", "indi")

CANONICAL: dict[str, tuple[str, ...]] = {
    "demo": (
        "primaryid", "caseid", "caseversion", "i_f_code", "event_dt", "init_fda_dt", "fda_dt",
        "rept_cod", "mfr_num", "age", "age_cod", "age_grp", "sex", "wt", "wt_cod", "rept_dt",
        "occp_cod", "reporter_country", "occr_country",
    ),
    "drug": (
        "primaryid", "caseid", "drug_seq", "role_cod", "drugname", "prod_ai", "route", "dose_vbm",
        "dechal", "rechal", "nda_num",
    ),
    "reac": ("primaryid", "caseid", "pt", "drug_rec_act"),
    "outc": ("primaryid", "caseid", "outc_cod"),
    "rpsr": ("primaryid", "caseid", "rpsr_cod"),
    "ther": ("primaryid", "caseid", "dsg_drug_seq", "start_dt", "end_dt", "dur", "dur_cod"),
    "indi": ("primaryid", "caseid", "indi_drug_seq", "indi_pt"),
}

# Column renames applied to raw headers (already lower-cased) before selecting canonical columns.
# Legacy AERS uses ISR for the report id, CASE for the case id and GNDR_COD for sex. Some early
# FAERS quarters spell i_f_code as i_f_cod.
RENAMES: dict[str, str] = {
    "isr": "primaryid",
    "case": "caseid",
    "gndr_cod": "sex",
    "i_f_cod": "i_f_code",
}

# FAERS outcome codes. Every code marks the report as serious (FAERS lists outcomes only for
# serious reports).
OUTCOME_CODES: dict[str, str] = {
    "DE": "death",
    "LT": "life_threatening",
    "HO": "hospitalization",
    "DS": "disability",
    "CA": "congenital_anomaly",
    "RI": "required_intervention",
    "OT": "other_serious",
}

ROLE_CODES: dict[str, str] = {
    "PS": "primary_suspect",
    "SS": "secondary_suspect",
    "C": "concomitant",
    "I": "interacting",
}


def canonical_name(raw_header: str) -> str:
    """Lower-case a raw header and apply the legacy renames."""
    name = raw_header.strip().lower()
    return RENAMES.get(name, name)
