# Test fixtures

Tiny, hand-made files in the exact FAERS/AERS ASCII layouts. They contain no real report data:
IDs, dates and drug names are invented to exercise parsing, de-duplication and normalization.

- `faers/2019Q1`, `faers/2019Q2`: FAERS layout (2012Q4 onward), including a row broken by an embedded
  newline, a trailing `$` on every row, three versions of one case, and a deletion list.
- `faers/2012Q3`: legacy AERS layout (ISR/CASE, upper-case file names, no case version, no prod_ai).

Expected after de-duplication: 8 DEMO rows, 8 unique report keys, 7 after removing deleted case 300,
4 unique cases (100 → version 3, 200 → FAERS version beats the AERS row, 400, 500).
