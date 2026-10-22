# Dataset card: Vaccine Adverse Event Reporting System (VAERS)

What this file is: what VAERS contains, why PharmaVigil uses it (training data for the MedDRA
auto-coder), how it is processed, and its limits.

## Source

- **Publishers:** U.S. Centers for Disease Control and Prevention (CDC) and FDA.
- **Download:** yearly zips from https://vaers.hhs.gov/data/datasets.html (`<year>VAERSData.zip`),
  with `VAERSDATA`, `VAERSSYMPTOMS` and `VAERSVAX` CSV files (Latin-1). Non-domestic reports are
  published as separate `NonDomestic*` files.
- **Licence:** U.S. government work, public domain.

## Why VAERS

Every VAERS report has a free-text narrative (`SYMPTOM_TEXT`) and the MedDRA Preferred Terms that
human coders assigned (`SYMPTOM1`–`SYMPTOM5`, several rows per report). That pairing is ready-made
training data for an auto-coder whose labels come from people, never from an AI model.

## Processing (data/pharmavigil_data/vaers.py)

- Join each narrative to the set of PTs coded for it; lower-case and de-duplicate the PTs; drop
  reports with no narrative or no PT.
- Output: `vaers_narrative(vaers_id, recv_date, year, text, pts[])`.

## Status

The VAERS host was blocked in the build container at M2 (see `analysis/PROGRESS.md`). The reader is
tested on hand-made fixtures. Whether the non-domestic files include narratives is to be checked on
the first real download rather than assumed.

## Known limitations

- **Vaccines, not drugs:** narrative style and vocabulary differ from drug reports, so the coder is also
  scored on a hand-labelled set of drug narratives (`ml/coder/drug_narratives_template.csv`) to measure
  the domain gap.
- **Reports do not prove causation**, and coding practice varies between coders and over MedDRA versions.
- Narratives may contain sensitive free text. They are used only for model training inside the
  training environment and are never committed to git.
