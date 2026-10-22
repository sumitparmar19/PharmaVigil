# ADR-008: Models run inside the Java services through ONNX Runtime

<!-- Records why the duplicate, coder and seriousness models are served from case-service with ONNX Runtime
     instead of a Python model server, and how parity between the two languages is guaranteed. -->

- **Status:** accepted
- **Milestone:** M8 (export side in M5)

## Context

Every report passes three models (duplicate check, MedDRA coder, seriousness triage) on its way to a
reviewer. Training happens in Python (PyTorch, LightGBM). The live pipeline is Java (Spring Boot, Kafka).
Cases must keep moving when a model is missing (engineering rule 2), and every suggestion must record the
model name and version (rule 5).

## Options considered

1. **Python model server** (FastAPI/TorchServe) called over HTTP. Pros: no re-implementation of
   pre-processing. Cons: another service, network hop and failure mode per report; Python in the live path.
2. **ONNX Runtime inside case-service.** Pros: in-process, no extra service, one dependency
   (`com.microsoft.onnxruntime:onnxruntime`); the models are small. Cons: pre-processing (feature vector,
   WordPiece tokenizer, ranking) must be written twice and kept identical.
3. **Deep Java Library (DJL) with Hugging Face tokenizers.** Pros: ready tokenizer. Cons: downloads native
   libraries at run time from hosts the build environment cannot reach.

## Decision

Option 2. Python exports a **model bundle** (`pv-ml java-bundle`; `ml/pharmavigil_ml/export/java_bundle.py`)
with the ONNX graphs, the feature spec, vocabulary, label space, tail embeddings and **parity vectors**
(inputs plus Python's outputs). case-service loads it from `PV_MODEL_DIR` (`ModelBundle`), and
`ModelParityTest` replays the parity vectors: identical token ids and feature vectors, scores within 1e-5
(1e-4 for the coder) and identical top-5 rankings.

## Consequences

- Any change to the Python pre-processing must regenerate the bundle; the parity test fails otherwise.
- Each model loads on its own; a missing or corrupt file disables only that model and the case is routed to
  manual coding or "seriousness unknown".
- The stored model version is `<bundle kind>:<first 12 hex of the ONNX file's SHA-256>`, traceable in
  `analysis/models/MANIFEST.md`.
- The committed bundle is a synthetic fixture; real bundles come from the Kaggle/Databricks runs.
