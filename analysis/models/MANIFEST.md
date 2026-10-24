# Model manifest

What this file is: every trained model artefact, with its SHA-256, data scope and the evidence file that
used it. Artefacts themselves are not committed (`.gitignore`); this table lets anyone confirm that a
downloaded file is the one the numbers came from.

| Model | File | SHA-256 | Data scope | Trained where | Evidence |
|---|---|---|---|---|---|
| coder labels and label embeddings | `coder.json` | `c3358989119adac385e7c323e2062f6eeca33fcd80b4cfef358fd559b81585e2` | subset: FAERS 2019Q1–2026Q2 (same) | build container, CPU (`pv-ml p4`) | `analysis/p4/coder.json` |
| MedDRA coder, zero-shot (PubMedBERT embeddings, int8) | `coder.onnx` | `de154f2238b7a6b5d3d00170ff0171e6cef6a169f355c297e4b4cbd07bc930b4` | subset: FAERS 2019Q1–2026Q2 (label space: 3,000 most reported FAERS PTs) | build container, CPU (`pv-ml p4`) | `analysis/p4/coder.json` |
| coder tokenizer vocabulary | `coder_vocab.txt` | `79489a52be45e6fa033521e8ce8e4f62aedc0a742ee2aa6fc04667e5b0b1454d` | subset: FAERS 2019Q1–2026Q2 (—) | build container, CPU (`pv-ml p4`) | `analysis/p4/coder.json` |
| duplicates threshold and feature list | `duplicates.json` | `ddc45b067667bb28927a6d8375cb9dd056295097ef0d459fd09b8bb8b25ecae6` | subset: FAERS 2019Q1–2026Q2 (same) | build container, CPU (`pv-ml p4`) | `analysis/p4/duplicates.json` |
| duplicates (LightGBM, 300 rounds) | `duplicates.onnx` | `75283c9e7b4eae4fb79bac5a67abf71c666c1075ebab685d1ebb0464a05efb65` | subset: FAERS 2019Q1–2026Q2 (versions of a random 300,000 cases) | build container, CPU (`pv-ml p4`) | `analysis/p4/duplicates.json` |
| Python↔Java parity examples | `parity.json` | `dad5ee533d8abe73e4d7ff1798f3255dbbff0ac59c9c126e66d9e85ec1fc4dc0` | subset: FAERS 2019Q1–2026Q2 (—) | build container, CPU (`pv-ml p4`) | `analysis/p4/parity.json` |
| seriousness triage (LightGBM, 400 rounds) | `seriousness.onnx` | `44b71f68b4b0151e6b047d3db7c7d5cd491d148156c1cc62548dbf16777cecc4` | subset: FAERS 2019Q1–2026Q2 (random 1,000,000 cases, time split 2025-01-01) | build container, CPU (`pv-ml p4`) | `analysis/p4/triage.json` |
| seriousness feature spec | `seriousness_features.json` | `0d05fec284313218ba5f3b421c2622a85192c2e53c98a49267aec445eec810d9` | subset: FAERS 2019Q1–2026Q2 (same) | build container, CPU (`pv-ml p4`) | `analysis/p4/triage.json` |

These files form the case-service bundle of the live demo (`bundle.json`, kind `trained`, SHA-256 `cc4fb48698a18c4fb5d505c009d66afb3f286832e8a541c7a461a4994dc4b7cb`); they ship in the demo-data release archive, not in git.

## Test fixtures (committed, synthetic)

`services/case-service/src/test/resources/model-bundle/` holds tiny models trained by `pv-ml java-bundle` on
**invented data** (no FAERS, VAERS or MedDRA content). They exist only so the Java pipeline is tested on real
ONNX graphs; their scores mean nothing. `bundle.json` in that folder lists every file's SHA-256.

| Model | File | SHA-256 | Data scope |
|---|---|---|---|
| seriousness (LightGBM, 25 rounds) | `seriousness.onnx` | `4af5119482a694a3e2b58b7825488808810f7de6abacf8d5e89121a3006bca66` | synthetic fixture |
| duplicates (LightGBM, 25 rounds) | `duplicates.onnx` | `edf5fe4c739df0c19f9a8a60d20f3802deb57b226d26c6638a460baedd0ae913` | synthetic fixture |
| MedDRA coder (1-layer, 32-dim BERT) | `coder.onnx` | `a32eafa289fd754b4cc439c1776c2948a145e04a2df9a73f18b8faf0b3bf7419` | synthetic fixture |
