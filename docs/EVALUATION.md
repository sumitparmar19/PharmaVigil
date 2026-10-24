# Evaluation

What this file is: how every PharmaVigil model is measured, the results with their data scope, and how
they compare with published work. A number appears here only if a committed file under `analysis/`
produced it. **Spontaneous reports do not prove that a drug caused a reaction**; a high signal score
means "reported together more often than expected", nothing more.

## Baselines (M3)

### Methods

Implemented in `ml/pharmavigil_ml/signals/`:

| Method | Score used | Lower-bound score | Notes |
|---|---|---|---|
| PRR (Evans et al. 2001) | `prr` | `prr_lo` (95% CI, log scale) | Haldane +0.5 correction for tables with a zero cell |
| ROR (van Puijenbroek et al. 2002) | `ror` | `ror_lo` (95% CI, log scale) | same correction |
| IC / BCPNN (Norén et al. 2006) | `ic` = log2((a+0.5)/(E+0.5)) | `ic025` (closed-form approximation) | shrinks small counts toward 0 |
| EBGM / MGPS (DuMouchel 1999) | `ebgm` | `eb05` (5th posterior percentile) | two-gamma prior fitted by maximum zero-truncated marginal likelihood (L-BFGS-B, openEBGM starting values); fitted on up to 2M observed pairs |

Two variants of every count: **primary suspect only** and **all roles** (primary, secondary,
concomitant, interacting). The background `N` is the number of unique cases with at least one mapped
drug in the chosen roles.

### Reference sets

From OHDSI MethodEvaluation at commit `d7305910f6c6a390a9e423ffc1685a97c017183e`
(`analysis/m2/refsets.json`):

| Set | Pairs | Positive | Negative | Outcomes | Drugs |
|---|---|---|---|---|---|
| OMOP | 399 | 165 | 234 | 4 | 182 |
| EU-ADR | 93 | 43 | 50 | 10 | 65 |

Drugs are matched to RxNorm ingredients by name against the ingredients present in the gold tables.
Each outcome is the set of MedDRA PTs listed in ADR-005. A pair whose drug never appears in the analysed
reports is reported as `unscorable` (counted, never silently dropped). AUCs are computed on scorable
pairs, with a percentile bootstrap 95% CI (1,000 resamples, positives and negatives resampled
separately).

### Results

| Scope | Status |
|---|---|
| subset: FAERS 2019Q1 to the latest quarter | **pending full data**: the FAERS download host is blocked in the build container (`analysis/PROGRESS.md`) |
| full: FAERS 2004Q1 to the latest quarter | **pending owner step** (Databricks run, then `pv-ml baselines`) |

The harness itself is tested end to end on synthetic reports with planted associations
(`ml/tests/test_contingency_eval.py`): it recovers the planted pairs (EBGM AUC > 0.9 on that synthetic
set). That test checks the code and is **not** a result about real drugs.

### Published comparison

`docs/PLAN.md` quotes classic disproportionality at AUC 0.64–0.73 on the OMOP set and learned
embeddings at 0.72–0.84, citing Ding & Cohen, "Retrofitting Vector Representations of Adverse Event
Reporting Data to Structured Knowledge to Improve Pharmacovigilance Signal Detection" (AMIA 2020,
arXiv:2008.03340). arXiv was not reachable from the build environment, so these exact figures were not
re-checked against the paper's tables. They are to be confirmed before the comparison is reported.
Differences to expect: data years, how outcomes are mapped to PTs, whether drug names are normalized,
and whether unscorable pairs are dropped or kept.

### How to reproduce

```bash
cd data && .venv/bin/pv-data faers-bronze --first 2019Q1 --last <latest> && .venv/bin/pv-data faers-silver \
  && .venv/bin/pv-data rxnorm-map && .venv/bin/pv-data faers-gold && .venv/bin/pv-data refsets
cd ../ml && .venv/bin/pv-ml baselines --scope "subset: FAERS 2019Q1–<latest>"
```

Outputs: `analysis/m3/baselines.json`, `analysis/m3/roc_*.png`, `analysis/m3/scores_*.csv`.

## SafetyFormer (M4)

Model: `ml/signals/MODEL_CARD.md`. Score definition: ADR-005. Time-travel method: ADR-006.

### Reference sets

SafetyFormer is scored on the **same scorable pairs** as the baselines: a pair counts only if its drug
appears in the training window and in the model vocabulary, so no method gets an easier subset. Output:
`analysis/m4/safetyformer.json` (AUCs with bootstrap CIs for PRR, ROR, IC, EBGM and SafetyFormer side by
side).

| Scope | Status |
|---|---|
| subset/CPU: FAERS 2019Q1 to the latest quarter | **pending full data** (FAERS host blocked) |
| full/GPU: FAERS 2004Q1 to the latest quarter | **pending owner step** (Kaggle notebook) |

### Time-travel test

Yearly snapshots; flagged = in the drug's top 10 / 20 / 50; lead time in months before the label
change. Output: `analysis/m4/time_travel.json`. Status: **pending** (needs FAERS and a reachable
label-change source, ADR-006).

### Leakage check

Same number of training reports and same test-set size; a time-ordered split (train before the test
year) against a shuffled split. Metric: held-out masked-reaction recall@10 and NLL. Output:
`analysis/m4/leakage.json`. Status: **pending full data**.

### Code checks

`ml/tests/test_safetyformer.py` trains tiny models on synthetic reports with planted associations and
checks that training lowers the loss, the planted pair gets a positive score above the median of other
drugs, checkpoints round-trip exactly, label-change text matching works, lead times are computed
correctly, and both leakage splits run. These are tests of the code, not results.

## MedDRA coder, seriousness triage and duplicates (M5)

| Model | Metrics | Evidence | Status |
|---|---|---|---|
| MedDRA coder (Model B) | micro-F1, macro-F1 (head labels); top-5 recall over all PTs, head and tail separately; drug-narrative top-5 recall | `analysis/m5/coder.json`, `analysis/m5/coder_drug_narratives.json` | **pending** VAERS + GPU (owner step 6) and owner labels (step 7) |
| Seriousness (Model C1) | PR-AUC, ROC AUC, Brier, calibration bins, mean-SHAP top features; time split | `analysis/m5/triage.json` | **pending full data** |
| Duplicates (Model C2, proxy signal) | PR-AUC, precision/recall at 0.5 and at best F1; split by case | `analysis/m5/duplicates.json` | **pending full data** |
| ONNX parity (all models) | max absolute difference, original vs ONNX Runtime | `analysis/m5/parity.json` | written by each real run; the export path is tested now (below) |

Code checks now passing (`ml/tests/`): the seriousness features never read outcome fields or
outcome-revealing PTs (changing them leaves every feature vector identical); synthetic training beats
the base rate; duplicates recover synthetic follow-ups; the coder learns invented sentences with a tiny
random BERT; ONNX parity holds for LightGBM (< 1e-5), the coder (< 1e-4, at two different batch/sequence
shapes) and SafetyFormer (< 1e-4, at two shapes). Writing the second-shape test exposed that the legacy
TorchScript exporter bakes the attention reshape of `nn.TransformerEncoder` to the traced size; SafetyFormer
now uses the `torch.export`-based exporter with symbolic batch and sequence dimensions.
