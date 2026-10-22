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
| FAERS subset / full | **pending full data** (FAERS host blocked) | `analysis/m5/triage.json` |
| Code checks (synthetic reports, ONNX parity < 1e-5) | ✅ passing | `ml/tests/test_triage.py` |

## Limits and use

- FAERS seriousness is the reporter's classification and is itself noisy.
- A suggestion for queue order only: the reviewer confirms seriousness, and the 15-day clock is computed
  by code from dates, never by the model.
- Not for clinical or regulatory use.
