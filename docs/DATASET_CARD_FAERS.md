# Dataset card: FDA Adverse Event Reporting System (FAERS)

What this file is: what FAERS contains, how PharmaVigil processes it, and what it cannot tell you.
Numbers come from `analysis/m2/*.json` and carry their data scope.

## Source

- **Publisher:** U.S. Food and Drug Administration (FDA), Quarterly Data Extract (QDE) files.
- **Download:** `https://fis.fda.gov/content/Exports/faers_ascii_<yyyy>q<q>.zip` (2012Q4 onward) and
  `aers_ascii_<yyyy>q<q>.zip` (2004Q1–2012Q3, the older AERS system).
- **Licence:** U.S. government work, public domain. No registration needed.
- **Contents:** spontaneous reports of suspected adverse events from health professionals, consumers
  and manufacturers. No direct identifiers; this project adds no linkage to other data.

## Layout

Seven `$`-delimited tables per quarter (header row, Latin-1, no quoting):

| Table | One row per | Key fields used |
|---|---|---|
| DEMO | report version | primaryid, caseid, caseversion, fda_dt, init_fda_dt, event_dt, age/age_cod, sex, reporter_country, occp_cod |
| DRUG | drug mention | drug_seq, role_cod (PS/SS/C/I), drugname, prod_ai |
| REAC | reaction | pt (MedDRA Preferred Term as printed) |
| OUTC | outcome | outc_cod (DE, LT, HO, DS, CA, RI, OT) |
| RPSR, THER, INDI | report source, therapy dates, indications | stored in bronze, not used yet |

Newer releases also include a list of case IDs FDA deleted that quarter.

**Legacy AERS (2004Q1–2012Q3)** uses ISR (report) and CASE (case) numbers, has no case version,
names sex `GNDR_COD`, and its DRUG/REAC/OUTC files carry only the ISR. The bronze reader maps these to
the FAERS names (`data/pharmavigil_data/faers/schema.py`); silver keys every report as `AERS:<isr>` or
`FAERS:<primaryid>` so the two numbering systems never collide.

## Processing (data/pharmavigil_data/faers)

1. **Bronze:** one quarter at a time, download, convert each table to Parquet, record a manifest
   (zip SHA-256, row counts, rows repaired or dropped by the parser), delete the zip.
2. **Silver:** drop repeated report keys, drop deleted cases, keep one report per case (highest case
   version, then latest FDA date, then highest report id; AERS rows sort below FAERS versions), derive
   age in years and an age band, normalized sex, seriousness (any outcome code) and the outcome list.
3. **Drug normalization:** prod_ai ingredients (split on `\`) or the drug name → a name key → RxNorm
   ingredient RxCUIs via RxNav, most frequent names first, cached in SQLite.
4. **Gold:** `report`, `report_drug` (ingredient-level, with role), `report_reaction` (lower-cased PT).
5. **Quality:** counts per quarter, parse repairs, unknown/null rates, PT vocabulary size, date range,
   and whether every reference-set outcome PT exists in the FAERS vocabulary.

Counts are reported before and after de-duplication (`analysis/m2/dedup_counts.json`). Any count used
on the resume is the number of **unique cases**.

## Scope of the committed numbers

| Scope label | Quarters | Where it runs | Status |
|---|---|---|---|
| subset | 2019Q1 to the latest quarter | build container (`pv-data`) | pending: fis.fda.gov blocked in the build container (see `analysis/PROGRESS.md`) |
| full | 2004Q1 to the latest quarter | Databricks notebooks `data/notebooks/databricks/` | owner step |

## Known limitations

- **Spontaneous reports do not prove that a drug caused a reaction.** Reporting is voluntary,
  incomplete and influenced by media attention, litigation and how long a drug has been marketed.
- **No denominators:** FAERS has no count of people exposed, so rates cannot be computed. Signal
  statistics compare reporting proportions only.
- **Duplicates remain:** the version rule removes follow-ups of the same case, not separate reports of
  one event from different reporters (cross-case duplicates). M5 adds a learned duplicate model.
- **Free-text drug names:** some names never map to RxNorm (misspellings, foreign brands, supplements);
  normalization coverage is measured and reported, not assumed.
- **MedDRA versions change over time:** PT strings are used as printed; no MedDRA hierarchy is
  applied until the owner's academic licence arrives.
