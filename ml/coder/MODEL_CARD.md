# Model card: MedDRA auto-coder (Model B)

What this file is: what the coder does, how it is trained and scored, and how it must be used.

| | |
|---|---|
| Task | Suggest MedDRA Preferred Terms (PTs) for a free-text side-effect narrative |
| Base model | `microsoft/BiomedNLP-BiomedBERT-base-uncased-abstract-fulltext` (BiomedBERT, formerly PubMedBERT; pretrained from scratch on PubMed text with a biomedical vocabulary), fine-tuned. Choice and alternatives: ADR-007 |
| Method | Multi-label classification head over the frequent PTs (those covering 95% of label occurrences, at most 2,000) + embedding retrieval over PT names for the rare ones |
| Training data | VAERS narratives (`SYMPTOM_TEXT`) with the PTs that **human coders** assigned. No labels from any AI model |
| Split | By receipt year: train on earlier years, tune on year T−1, test on year T |
| Metrics | micro-F1 and macro-F1 (head labels), top-5 recall over all true PTs, split into head and tail labels |
| Domain-shift check | 50 invented drug narratives labelled by Sumit (`drug_narratives_template.csv`) |
| Serving | ONNX (`coder.onnx`, inputs `input_ids`, `attention_mask`; outputs `probabilities`, `embedding`) in the Java case-service |
| Code | `ml/pharmavigil_ml/coder/`, `pv-ml coder` |

## Results

| Scope | Status | Evidence |
|---|---|---|
| Deployed in the demo (Phase 2): **zero-shot** retrieval with `NeuML/pubmedbert-base-embeddings` (Apache-2.0, revision b79526d6), no fine-tuning and no training labels; label space = the 3,000 most reported FAERS PTs in subset: FAERS 2019Q1–2026Q2; int8 dynamic quantization | **not evaluated**: accuracy needs narratives with human-assigned PTs (VAERS, owner step 14). Example outputs, as produced: "Rhabdomyolysis" → rhabdomyolysis (1.00); "itchy skin" → skin weeping, dry skin, skin warm (0.87); "a high fever" → rheumatic fever (0.81) first; "my head hurts badly" → head discomfort (0.80) | `analysis/p4/coder.json` |
| Fine-tuned coder on VAERS narratives (the plan above) | **not run**: needs VAERS (owner step 14) and a GPU (owner steps 5–6) | `analysis/m5/coder.json` |
| Drug narratives (domain shift) | **pending owner labels** | `analysis/m5/coder_drug_narratives.json` |
| Code checks (tiny random BERT on invented sentences, ONNX parity at several shapes) | ✅ passing | `ml/tests/test_coder.py` |

SOC (System Organ Class) breakdowns are skipped until the MedDRA academic licence arrives; no MedDRA
dictionary files are used or committed.

## Limits and use

- The deployed zero-shot coder matches exact or near-exact PT wording well and lay wording poorly (the
  examples above). Its auto-code threshold is 0.97, so in practice only exact or near-exact wording skips manual coding;
  everything else is a ranked list for the reviewer, and the case is marked "needs manual coding".
- Trained on vaccine reports (fine-tuned plan); drug-report wording differs. The domain-shift number shows how much.
- Suggestions only: in the reviewer console every code is accepted or corrected by a human, the model
  name, version and score are stored with the decision, and corrections become new training data.
- Narratives can contain personal details; they stay in the training environment and are never
  committed.
- Not for clinical or regulatory use.
