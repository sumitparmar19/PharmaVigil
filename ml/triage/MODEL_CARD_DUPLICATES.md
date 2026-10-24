# Model card: duplicate detection (Model C, part 2)

What this file is: the model that warns a reviewer when a new report may describe an event already on
file, and why its training signal is only a proxy.

| | |
|---|---|
| Task | Score a pair of reports: probability they describe the same event |
| Model | LightGBM on 10 record-linkage features (`PAIR_FEATURES`: drug and PT Jaccard, shared counts, same sex / age band / country / reporter type, event-date and FDA-date gaps) |
| **Training signal: a proxy** | Positives are consecutive **versions of the same FAERS case** (follow-ups of one event). True cross-case duplicates (two different case IDs for one event) are not labelled in FAERS. Negatives: different cases, half sharing a drug ("hard"), half random |
| Split | By case ID (hash), so no case is on both sides |
| Metrics | PR-AUC, precision/recall at 0.5 and at the best-F1 threshold |
| Serving | `duplicates.onnx` (input `features` float[10]); the Java case-service computes the same features against recent cases sharing a drug |

## Results

| Scope | Status | Evidence |
|---|---|---|
| FAERS subset / full | **pending full data** | `analysis/m5/duplicates.json` |
| Code checks (synthetic versions, ONNX parity < 1e-5) | ✅ passing | `ml/tests/test_duplicates.py` |

## Limits and use

- Because the positives are follow-up versions, the measured precision/recall describe how well the
  model recognizes *follow-ups*; performance on genuine cross-case duplicates is unmeasured.
- Suggestion only: the reviewer marks or clears a duplicate in the console; nothing is merged
  automatically.
