# Model card: SafetyFormer (Model A)

What this file is: what SafetyFormer is, how it is trained and evaluated, and what it must not be used
for. Every number links to a committed file under `analysis/m4/` and carries its data scope.

## Summary

| | |
|---|---|
| Task | Drug-safety signal detection: score how strongly a drug and a reaction are associated in FAERS reports |
| Type | Transformer encoder over the *set* of tokens in a report, trained **from scratch** |
| Objective | Self-supervised masked-reaction prediction (hide reactions, predict them from the rest); optional masked-drug objective |
| Code | `ml/pharmavigil_ml/safetyformer/` |
| Default size | d=256, 4 layers, 8 heads, FFN 1024 (≈3.2M parameters at the synthetic-test vocabulary; grows with the drug/PT vocabulary) |
| Training data | FAERS gold tables, unique cases only; no hand labels |
| Labels used in evaluation | OMOP and EU-ADR reference sets (OHDSI), FDA label-change dates (time-travel test) |
| Output | `score(d, O) = log Σ p(pt∣d) − log Σ p(pt∣no drug)` over the outcome's PTs (ADR-005) |

## Inputs

Per report: age band, sex, seriousness, each drug as an RxNorm ingredient with its role (primary
suspect, secondary suspect, concomitant, interacting), and each MedDRA PT. No positions (a report is a
set); a type embedding separates context, drug, reaction and mask tokens. Drugs and PTs seen in fewer
than `min_count` (default 5) reports of the training window are left out of the vocabulary.

## Training

- Views: one [MASK] query per report; the target is the uniform distribution over the hidden PTs.
- Conditioning dropout (single drug 30%, no drug 5%, all PTs hidden 50%, each context token unknown
  30%) so the scoring query (one drug, unknown context) is in-distribution.
- AdamW, warm-up then cosine decay, gradient clipping 1.0, seed 13, MLflow tracking (local file store).
- Checkpoints are hashed into `analysis/models/MANIFEST.md`.
- Measured CPU throughput in the build container (4 cores, synthetic data, batch 256, default size):
  about 750 reports per second. Full-scale training runs on a Kaggle GPU
  (`ml/notebooks/kaggle/safetyformer_full.ipynb`).

## Evaluation

| Test | Status | Evidence |
|---|---|---|
| Reference-set AUC vs PRR/ROR/IC/EBGM on the same scorable pairs | pending full data | `analysis/m4/safetyformer.json` |
| Time-travel (yearly snapshots vs FDA label changes) | pending data and label-change source | `analysis/m4/time_travel.json` |
| Shuffled vs time-ordered split | pending full data | `analysis/m4/leakage.json` |
| Code checks on synthetic reports with planted associations | ✅ passing | `ml/tests/test_safetyformer.py` |

## Limitations and intended use

- **Spontaneous reports do not prove that a drug caused a reaction.** A high score means "reported
  together more often than expected", which can come from the disease being treated (confounding by
  indication), co-prescribed drugs, publicity or duplicate reports.
- FAERS has no exposure denominators; scores are not risks or rates.
- Drugs or reactions with few reports are outside the vocabulary and get no score.
- The model is a research prototype. **Not for clinical or regulatory use.** In PharmaVigil its scores
  are shown only as evidence next to report counts, never as a verdict.
