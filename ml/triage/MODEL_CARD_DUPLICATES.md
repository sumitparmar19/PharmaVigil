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
| subset: FAERS 2019Q1–2026Q2, versions of a random 300,000 cases (130,894 train / 32,769 test pairs, 10,973 test positives) | PR-AUC **0.998**; at the best-F1 threshold (0.528) precision 0.984, recall 0.979; ONNX parity max diff 8.2e-8 | `analysis/p4/duplicates.json` |
| full: FAERS 2004Q1 onward | not run (owner step 4) | — |
| Code checks (synthetic versions, ONNX parity < 1e-5) | ✅ passing | `ml/tests/test_duplicates.py` |

## Limits and use

- Because the positives are follow-up versions, the measured precision/recall describe how well the
  model recognizes *follow-ups*; performance on genuine cross-case duplicates is unmeasured. Follow-up
  versions usually repeat most of the first report, so this task is easy and the 0.998 PR-AUC should not be
  read as duplicate-detection accuracy in general.
- Suggestion only: the reviewer marks or clears a duplicate in the console; nothing is merged
  automatically.
