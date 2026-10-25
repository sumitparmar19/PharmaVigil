# Model card: seriousness triage (Model C, part 1)

What this file is: the model that orders the reviewer queue by predicted seriousness, and its limits.

| | |
|---|---|
| Task | Predict whether a FAERS report is serious (death, life-threatening, hospitalization, disability, congenital anomaly, required intervention, other serious) |
| Model | LightGBM, 400 rounds, fixed seed (`ml/pharmavigil_ml/triage/seriousness.py`) |
| Features | Age band, sex, reporter type, reporter country, counts of drugs / primary-suspect drugs / PTs, indicators for the 300 most frequent drugs and the 500 most frequent **non-outcome-revealing** PTs (`FeatureSpec`, JSON, rebuilt identically in Java) |
| Never used as input | The outcome fields and any PT matching the outcome-revealing pattern (death, hospitalisation, life-threatening, disability, congenital, resuscitation, intensive care, coma...). Tests: `test_spec_never_contains_outcome_terms_or_fields`, `test_features_ignore_outcome_fields_and_revealing_terms` |
| Split | By FDA receipt date: train before the test date, test on and after it |
| Metrics | PR-AUC (main), ROC AUC, Brier score, 10-bin calibration table, top 20 features by mean absolute SHAP (LightGBM TreeSHAP) |
| Serving | `seriousness.onnx` (input `features` float[n], output probabilities) |

## Results

| Scope | Status | Evidence |
|---|---|---|
| subset: FAERS 2019Q1–2026Q2, random 1,000,000 cases, test from 2025-01-01 (201,450 reports, 55.0% serious) | ROC AUC **0.960**, PR-AUC **0.969**, Brier 0.078; ONNX parity max diff 2.3e-7 | `analysis/p4/triage.json` |
| same, US reports only (135,257; 35.2% serious) | ROC AUC **0.924**, PR-AUC 0.883 | `analysis/p4/triage.json` (`by_reporter_country`) |
| same, non-US reports only (66,193; 95.4% serious) | ROC AUC 0.923, PR-AUC 0.995 | `analysis/p4/triage.json` (`by_reporter_country`) |
| full: FAERS 2004Q1 onward | not run (owner step 4, Databricks) | — |
| Code checks (synthetic reports, ONNX parity < 1e-5) | ✅ passing | `ml/tests/test_triage.py` |

## Limits and use

- FAERS seriousness is the reporter's classification and is itself noisy.
- **Reporter country is the strongest feature.** FDA receives reports from abroad mostly when they are
  serious, so 95.4% of non-US test reports are serious against 35.2% of US ones. The overall AUC (0.960)
  includes that reporting rule; the within-country AUCs (about 0.92) are the fairer measure of what the model
  learns from drugs, reactions and reporter details.
- Trained on a random 1,000,000 of the 10.8M cases (the dense feature matrix of all cases does not fit the
  build machine's 15 GiB); SHAP importances use a fixed random 50,000 test rows.
- A suggestion for queue order only: the reviewer confirms seriousness, and the 15-day clock is computed
  by code from dates, never by the model.
- Not for clinical or regulatory use.
