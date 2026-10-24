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
| VAERS narratives (years recorded in the output) | **pending data and GPU** (vaers.hhs.gov and huggingface.co blocked in the build container; Kaggle notebook ready) | `analysis/m5/coder.json` |
| Drug narratives (domain shift) | **pending owner labels** | `analysis/m5/coder_drug_narratives.json` |
| Code checks (tiny random BERT on invented sentences, ONNX parity at several shapes) | ✅ passing | `ml/tests/test_coder.py` |

SOC (System Organ Class) breakdowns are skipped until the MedDRA academic licence arrives; no MedDRA
dictionary files are used or committed.

## Limits and use

- Trained on vaccine reports; drug-report wording differs. The domain-shift number shows how much.
- Suggestions only: in the reviewer console every code is accepted or corrected by a human, the model
  name, version and score are stored with the decision, and corrections become new training data.
- Narratives can contain personal details; they stay in the training environment and are never
  committed.
- Not for clinical or regulatory use.
