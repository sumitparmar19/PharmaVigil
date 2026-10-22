# PharmaVigil progress log

What this file is: the running record of each milestone (status, evidence, data scope), every deviation
from `docs/BUILD_INSTRUCTIONS.md`, and every environment re-test. The planning chat checks it against
the repo.

## Milestones

| # | Milestone | Status | Evidence |
|---|---|---|---|
| M0 | Bootstrap, context and rules | ✅ done (CI green; Dependency review waits on owner step 3) | this file, `analysis/M0_REVIEW.md`, CI workflows |
| M1 | Legacy audit | **removed by owner** (Amendment 1, item 1) | n/a |
| M2 | Data platform | code complete and tested on fixtures; real FAERS/VAERS run pending network (owner step 1); reference sets real | `analysis/m2/refsets.json`, `data/tests/` |
| M3 | Signal baselines | code complete and tested (synthetic data); real AUCs pending FAERS | `docs/EVALUATION.md`, ADR-005, `ml/tests/` |
| M4 | SafetyFormer | code complete and tested (synthetic); real training, time-travel and leakage numbers pending FAERS | `ml/signals/MODEL_CARD.md`, ADR-006, `ml/tests/test_safetyformer.py` |
| M5 | Coder, triage, duplicates | pending | |
| M6 | Java platform core | pending | |
| M7 | E2B(R3) and SOAP | pending | |
| M8 | Kafka, ONNX in Java, JMS, narrative | pending | |
| M9 | Frontend | pending | |
| M10 | Operations | pending | |
| M11 | Azure infrastructure (code only) | pending | |
| M12 | Docs, evidence, report | pending | |

## M0: bootstrap

- Removed the previous application's files in one commit (`M0: start PharmaVigil fresh`).
- Created the layout from §3: `services/` (Maven parent + `common` + 5 Spring Boot service skeletons),
  `data/` and `ml/` (Python packages with pytest and ruff), `frontend/` (React 19 + TypeScript 6 +
  Vite 8 + Tailwind 4 with Vitest), `docs/` (plan, instructions with Amendment 1, architecture,
  ADR template), `analysis/`, `CLAUDE.md`, `OWNER_STEPS.md`, `.env.example`, `.gitignore`, MIT `LICENSE`.
- CI: `.github/workflows/ci.yml` (Maven verify, pytest + ruff for `data` and `ml`, frontend
  typecheck/lint/test/build, gitleaks over full history), `codeql.yml` (Java, Python, TypeScript),
  `dependency-review.yml` (pull requests).
- Local results before the first push: `mvn -B verify` green (6 modules, 6 tests); `pytest` green in
  `data` and `ml`; frontend typecheck, lint, 2 Vitest tests and build green; gitleaks found no leaks
  in the history.

## M2: data platform

Built (`data/pharmavigil_data/`):
- `faers/ascii.py`, `faers/bronze.py`: quarter-at-a-time download → Parquet per table → manifest (zip
  SHA-256, rows, repaired/dropped rows) → raw zip deleted. Handles both the FAERS layout and the legacy
  AERS layout (`faers/schema.py`), rows broken by embedded newlines, trailing delimiters, and the
  quarterly deletion lists.
- `faers/silver.py` (Spark): repeated report keys removed, deleted cases removed, one report per case
  (case version, then FDA date, then report id), age band, sex, seriousness, outcomes. Returns counts
  at every step (before/after de-duplication).
- `rxnorm.py`: RxNav client (exact, then approximate match; reduced to TTY=IN ingredients), SQLite cache,
  15 requests/s limiter, stops after repeated errors and never caches failures.
- `faers/gold.py` (Spark): `report`, `report_drug`, `report_reaction`; normalization coverage (all
  mentions and primary-suspect mentions).
- `refsets.py`: OMOP and EU-ADR reference sets from OHDSI MethodEvaluation at commit
  `d7305910f6c6a390a9e423ffc1685a97c017183e`; outcome → PT map in `resources/outcome_pt_map.csv`.
- `vaers.py`: narratives joined to coder-assigned PTs.
- `quality.py`, `cli.py` (`pv-data`), `tables.py` (local Delta or Databricks Unity Catalog).
- Databricks notebooks `data/notebooks/databricks/00–03` for the full 2004Q1 onward run.
- Dataset cards: `docs/DATASET_CARD_FAERS.md`, `docs/DATASET_CARD_VAERS.md`.

Tests: 42 pytest tests (`data/tests/`), including an end-to-end Spark run over hand-made FAERS and AERS
fixtures that checks every de-duplication count, the kept versions, derived fields, gold rows and
coverage, and mocked-API tests for RxNav.

Real data so far:
- Reference sets (`analysis/m2/refsets.json`): OMOP 399 pairs (165 positive / 234 negative, 4
  outcomes, 182 drugs); EU-ADR 93 pairs (43 positive / 50 negative, 10 outcomes, 65 drugs).

Pending (blocked hosts, owner step 1): FAERS bronze 2019Q1 onward, de-duplication counts vs FDA's
published totals, RxNorm normalization coverage, VAERS narratives, data-quality report.

Open checks for the first real run:
- The RxNav approximate-match score threshold (`APPROXIMATE_MIN_SCORE = 8.0`) is a starting value; it is
  checked against a sample of real matches before the coverage number is reported.
- Every PT in `outcome_pt_map.csv` is checked against the FAERS vocabulary (`quality.json` lists any
  missing).

## M3: signal baselines

Built (`ml/pharmavigil_ml/signals/`):
- `disproportionality.py`: PRR and ROR with 95% CIs (Haldane correction for zero cells), IC with Norén
  shrinkage and IC025/IC975.
- `ebgm.py`: MGPS/EBGM, two-gamma prior by maximum zero-truncated marginal likelihood, posterior
  EBGM/EB05/EB95 (vectorized bisection for percentiles).
- `contingency.py`: DuckDB counts over the gold tables (Delta via delta-rs, or Parquet exports), with
  role variants and a received-before date cutoff for the time-travel test.
- `evaluate.py`: reference-drug name matching, AUC with bootstrap 95% CI, unscorable pairs counted.
- `baselines.py` + `pv-ml baselines`: writes `analysis/m3/baselines.json`, ROC charts, per-pair score
  CSVs, and the PT-level signal table (pairs with ≥ 3 reports) for signal-service.
- ADR-005 fixes the outcome → PT lists and the SafetyFormer score before any model results exist.

Tests: 15 pytest tests, including hand-computed PRR/ROR/IC values, EBGM shrinkage and percentile checks,
DuckDB counts on a hand-checkable gold set, and an end-to-end run on synthetic reports with planted
associations. An integration check (Spark-written gold Delta read by the ML package) passed locally.

Pending: the real AUCs need the FAERS gold tables (blocked host). The published comparison figures in
`docs/PLAN.md` (Ding & Cohen, AMIA 2020) could not be re-checked because arXiv is blocked too.

## M4: SafetyFormer

Built (`ml/pharmavigil_ml/safetyformer/`):
- `corpus.py`: unique cases → token sets in CSR numpy arrays; vocabulary from the training window only.
- `model.py`: set Transformer encoder (type + role embeddings, no positions, pre-norm), PT head, optional
  drug head.
- `views.py`: masked-reaction views with soft targets and conditioning dropout; scoring queries.
- `train.py`: AdamW + warm-up/cosine, seed, held-out recall@10/NLL, MLflow (never blocks training),
  hashed checkpoints, CUDA when available.
- `score.py`: ADR-005 score for PTs and outcome PT lists.
- `timetravel.py`: yearly snapshots, per-drug top-N flags, lead times; SrLC text → (drug, PT, date) pairs.
- `leakage.py`: shuffled vs time-ordered split with equal sizes.
- `run.py` + `pv-ml safetyformer | time-travel | leakage`; Kaggle notebook
  `ml/notebooks/kaggle/safetyformer_full.ipynb`.

Measured: CPU throughput in this container (4 cores, synthetic data, default size, batch 256) about
750 reports per second (≈ 2.9 steps/s). A one-epoch pass over 10M reports would take about 3.7 hours,
so the subset run here uses a step budget and the full run uses the Kaggle GPU notebook.

Tests: 11 new tests (26 in `ml/` total).

Pending: real-data numbers (FAERS blocked) and the label-change source (accessdata.fda.gov and DailyMed
not reachable; ADR-006 keeps the test pending rather than inventing dates).

## Environment re-tests

### Start of M0

All hosts that Amendment 1 item 3 lists were still refused by the session's network policy (403 at
the proxy): fis.fda.gov, api.fda.gov, vaers.hhs.gov, rxnav.nlm.nih.gov, www.accessdata.fda.gov,
www.fda.gov, ich.org (www, database), huggingface.co, download.pytorch.org, quay.io, get.helm.sh, and
GitHub release/package downloads (pkg-containers.githubusercontent.com). raw.githubusercontent.com was
reachable. Owner step 1 in `OWNER_STEPS.md` asks Sumit to re-check the setting. Hosts are re-tested at
the start of each data milestone.

## Deviations from the build instructions

| Item | Instruction | What was done | Why |
|---|---|---|---|
| Plan copies | copy both files into `docs/` | copied verbatim, each with a one-line pointer to Amendment 1 at the top | keeps the original instructions intact while making the override visible |
| TypeScript | newest version | 6.0.3, not 7.0.2 | typescript-eslint 8.72 (latest) supports TypeScript < 6.1 only (Amendment 1 item 4: "newest the whole toolchain supports") |
| Kafka 4.4 | Kafka 4.4 | broker image `apache/kafka:4.4.0`; client 4.2.1 as managed by Spring Boot 4.1.1 | overriding the client under spring-kafka 4.1.1 risks incompatibilities; Kafka clients and brokers are wire-compatible |
| Docker locally | §6 fallback: rely on GitHub Actions | the container's Docker daemon can be started, so Testcontainers also runs locally | better local verification |
| gitleaks locally | n/a | Docker Hub `zricethezav/gitleaks` image locally; CI uses `ghcr.io/gitleaks/gitleaks` | ghcr.io image layers are served from a blocked host in this container |
