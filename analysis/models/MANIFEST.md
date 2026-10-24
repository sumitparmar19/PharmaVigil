# Model manifest

What this file is: every trained model artefact, with its SHA-256, data scope and the evidence file that
used it. Artefacts themselves are not committed (`.gitignore`); this table lets anyone confirm that a
downloaded file is the one the numbers came from.

| Model | File | SHA-256 | Data scope | Trained where | Evidence |
|---|---|---|---|---|---|
| — | — | — | — | — | No real-data model has been trained yet (FAERS download host blocked; see `analysis/PROGRESS.md`). |

## Test fixtures (committed, synthetic)

`services/case-service/src/test/resources/model-bundle/` holds tiny models trained by `pv-ml java-bundle` on
**invented data** (no FAERS, VAERS or MedDRA content). They exist only so the Java pipeline is tested on real
ONNX graphs; their scores mean nothing. `bundle.json` in that folder lists every file's SHA-256.

| Model | File | SHA-256 | Data scope |
|---|---|---|---|
| seriousness (LightGBM, 25 rounds) | `seriousness.onnx` | `4af5119482a694a3e2b58b7825488808810f7de6abacf8d5e89121a3006bca66` | synthetic fixture |
| duplicates (LightGBM, 25 rounds) | `duplicates.onnx` | `edf5fe4c739df0c19f9a8a60d20f3802deb57b226d26c6638a460baedd0ae913` | synthetic fixture |
| MedDRA coder (1-layer, 32-dim BERT) | `coder.onnx` | `a32eafa289fd754b4cc439c1776c2948a145e04a2df9a73f18b8faf0b3bf7419` | synthetic fixture |
