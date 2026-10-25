# Model card: SafetyFormer on real FAERS (Phase 2, P3)

<!-- The results of the CPU-sized SafetyFormer trained on real FAERS in Phase 2: configuration, the reference-set
     comparison with the classic methods, the leakage check and the exported model. Method details (inputs,
     views, objective, scoring) are in ml/signals/MODEL_CARD.md. Every number is copied from analysis/p3/. -->

Spontaneous reports do not prove that a drug caused a reaction. Not for clinical or regulatory use.

**Scope: subset: FAERS 2019Q1–2026Q2.** Method: `ml/signals/MODEL_CARD.md`, ADR-006.

## Bottom line

**On these reference sets SafetyFormer does not beat the classic methods.** On OMOP it ties PRR, ROR and EBGM and
is clearly below IC; on EU-ADR it is below all of them. It is kept in the platform as a second view of the data, shown
next to the classic scores and never instead of them.

## What was trained

| | |
|---|---|
| Data | gold FAERS cases with at least one mapped drug and one PT; time split: 10,302,667 training reports, the newest 542,900 for validation |
| Vocabulary | 4,174 drugs (RxNorm ingredients) and 17,094 PTs seen in at least 5 reports |
| Size | **CPU-sized**: d = 128, 2 layers, 4 heads, FFN 512; 5,327,814 parameters (the default and the GPU notebook use d = 256, 4 layers) |
| Training | 10,000 steps × 256 reports (about 2.56M report views, a quarter of one pass over the data), AdamW, seed 13, CPU (4 cores), 36 minutes |
| Validation (masked-reaction prediction) | recall@10 0.477, NLL 5.45 at step 10,000 (0.303 / 6.55 at step 500); the curve is in `safetyformer.json` (`training.history`) |
| Checkpoint | `ml/artifacts/safetyformer/safetyformer.pt`, SHA-256 `31e325c717649e4a0fa922d5bcd12c956933e50706e0afdeaea6e6839b7a9a20` (`analysis/models/MANIFEST.md`) |

## Reference-set comparison

Same scorable pairs for every method (drug seen in the window and in the model's vocabulary), primary-suspect
counts for the classic methods, 1,000 bootstrap resamples. From `analysis/p3/safetyformer.json`.

| Method | OMOP (388 pairs: 161 / 227) | EU-ADR (92 pairs: 42 / 50) |
|---|---|---|
| **SafetyFormer** | **0.765 (0.719–0.811)** | **0.813 (0.721–0.897)** |
| PRR | 0.759 (0.707–0.807) | 0.916 (0.849–0.969) |
| PRR lower 95% bound | 0.814 (0.772–0.855) | 0.930 (0.859–0.981) |
| ROR | 0.759 (0.707–0.807) | 0.916 (0.851–0.969) |
| IC | 0.821 (0.779–0.860) | 0.919 (0.852–0.970) |
| EBGM | 0.765 (0.712–0.815) | 0.910 (0.829–0.971) |
| EB05 | 0.784 (0.737–0.828) | 0.920 (0.847–0.979) |

Likely reasons, none tested here: the model is small and saw a quarter of one pass over the data; its score asks
"which reaction is hidden, given only this drug", which mixes in what the drug is prescribed for; and IC and the
lower-bound statistics already shrink the noisy small counts that a learned model would have to learn to ignore.
A full-size run on a GPU (owner steps 4–5) is the next test; it may or may not close the gap.

## Leakage check

**Pending**: the time-ordered vs shuffled comparison (two more trainings of 5,000 steps, test from 2025-01-01) is running; its result goes into `analysis/p3/leakage.json` and this section.

## Export

`pv-ml safetyformer-onnx` (`analysis/p3/safetyformer_onnx.json`): `safetyformer.onnx`, 21,577,654 bytes, SHA-256
`fbf4d41138d6633d33fc56ee836356f805ca6a9104fab4493ad767b7b4f56b12`; inputs tokens, types, roles, query_pos; output
log_probs; ONNX Runtime matches PyTorch to 5.7e-6 on eight drug queries. The model file is not in git; it is listed in
`analysis/models/MANIFEST.md` and goes into the release assets with the demo data.

## Not done

- Time-travel test (lead time before FDA label changes): needs dated label changes; FDA's SrLC server refuses
  automated downloads from the build container (`analysis/p2/README.md`).
- Full-size model and full FAERS history (2004 onward): owner steps 4–5.
