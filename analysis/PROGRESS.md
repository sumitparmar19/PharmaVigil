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
| M5 | Coder, triage, duplicates | code complete and tested (synthetic); real metrics pending FAERS/VAERS/GPU | model cards in `ml/coder/`, `ml/triage/`; ADR-007 |
| M6 | Java platform core | ✅ done (Oracle and SQL Server green locally) | `services/`, ADR-003 |
| M7 | E2B(R3) and SOAP | ✅ done (exports validate against the official schema set in CI) | `docs/E2B_R3.md`, ADR-009, `services/intake-gateway`, `scripts/fetch-e2b-xsd.sh` |
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

## M5: coder, triage, duplicates, ONNX

Built (`ml/pharmavigil_ml/`):
- `triage/features.py`: `FeatureSpec` (JSON for Java) and the outcome-leakage guard (outcome fields never
  read; outcome-revealing PTs dropped by pattern).
- `triage/seriousness.py`: LightGBM, time split, PR-AUC / ROC AUC / Brier / calibration / TreeSHAP.
- `triage/duplicates.py` + `data/.../faers/versions.py`: every case version, pair features, hard and
  random negatives, case-level split; the model card states the proxy.
- `coder/`: label space (95% coverage head + tail), BiomedBERT encoder + head, retrieval over PT names,
  merged ranking, micro/macro F1, top-5 recall (head/tail), drug-narrative scoring.
- `export/onnx_export.py`: ONNX for LightGBM (opset 15, the converter's maximum), the coder (opset 17) and
  SafetyFormer (`torch.export`, opset 18), parity checks, JSON test vectors for the Java parity tests.
- `m5.py`, `pv-ml triage | duplicates | coder | coder-drug-narratives`, `pv-data faers-versions | vaers`,
  Kaggle notebook `ml/notebooks/kaggle/meddra_coder.ipynb`.
- `ml/coder/drug_narratives_template.csv`: 50 invented drug narratives, label column empty for Sumit.

Problem found and fixed: the first ONNX export of SafetyFormer passed parity at the traced shape but
failed at another batch/sequence size (attention reshape frozen by the legacy tracer). A second-shape
parity test now guards every Transformer export.

Tests: 20 new tests (`ml/` 46 total; `data/` 43).

Pending: real metrics (FAERS, VAERS and Hugging Face hosts blocked; GPU runs are owner steps 5–6), and
the drug-narrative labels (owner step 7).

## M6: Java platform core

Built (`services/`):
- `common`: HS256 JWT issue/verify with a `roles` claim (→ `ROLE_*`), stateless security helper with a
  strict CSP header, RFC 7807 problem details that never leak internals (unexpected errors return a
  reference id only), and a test-jar with shared Testcontainers databases selected by `-Dpv.db`.
- `pharmacy-service`: drugs with expiring lots; FEFO dispensing that row-locks the drug's in-date lots and
  never touches expired ones; stock `CHECK (quantity >= 0)`; Rx-only drugs need a matching active
  prescription that is drawn down and completed; BCrypt logins with no HTTP session; PATIENT users read
  only their own history; one-click side-effect reports stored verbatim; Synthea-format patient import
  (only id, names, birth date, sex are kept); demo profile (catalog with expired lots, synthetic patients,
  logins only when `PV_DEMO_PASSWORD` is set); signal-service client that falls back to "unavailable"
  without blocking a dispense.
- `case-service`: case head (status machine, seriousness, 15/90-day deadline, optimistic lock) with
  append-only, Hibernate-`@Immutable` versions (drugs, reactions with model provenance); append-only
  narratives with single approval; audit events; review decisions that keep the model suggestion beside
  the final value (changed ones are the feedback set); processed-event and outbox tables for M8;
  idempotent receive by (source, source report id); reviewer queue (serious → unknown → non-serious, then
  deadline); read API for reviewers.
- Flyway migrations per vendor; Hibernate `validate` against both engines (ADR-003).

Tests and coverage (local run, Docker in this container):
- `mvn -B verify` (Oracle Database Free 23 via Testcontainers): common 7, pharmacy-service 23,
  case-service 9 tests, all green; skeleton services' context tests green.
- `mvn -B -Pmssql verify` for pharmacy-service and case-service on SQL Server 2022: same tests, green.
- JaCoCo line coverage: common 82.5% (52/63), pharmacy-service 93.2% (437/469), case-service 82.9%
  (306/369); the 70% gate passes.
- Notable tests: 50 concurrent dispenses against 30 in-date units (plus 1,000 expired) → exactly 30
  succeed, stock ends at 0, expired lot untouched; failed login gives no token, no session and the same
  response for unknown users; an immutable case version cannot be changed in place; a stale edit gets a
  conflict.

Problems hit: Oracle Free took about 70 s to report ready, beyond Testcontainers' default wait (fixed
with a 5-minute start-up timeout); `@PreAuthorize` denials surfaced as 500 through the catch-all handler
(now mapped to 403/401); Spring Security 7 adds a `FACTOR_BEARER` authority to JWT logins (test asserts
roles only).

## M7: E2B(R3) and SOAP

Built:
- `common/e2b`: E2B(R3) writer, hardened reader, schema validator (VALID / INVALID / SCHEMA_UNAVAILABLE) and
  `IcsrMapping` (E2B code lists ↔ `report.received`). Field mapping and gaps: `docs/E2B_R3.md`.
- Official schemas: `scripts/fetch-e2b-xsd.sh` downloads EMA's published copy of the ICH E2B(R3) schema set
  (50 files, following every include/import) at build time; nothing is committed. GitHub's runners reach
  it; this container does not. CI job *E2B(R3) export against the official schemas* runs the E2B tests
  with it, and the main Java job fetches it too.
- `intake-gateway`: contract-first SOAP (Spring-WS, WSDL from `partner-intake.xsd`) with `SubmitCase` and
  `GetCaseStatus`; WS-Security UsernameToken + Timestamp (ADR-009); request bodies validated against the
  contract; SOAP Client faults that explain bad XML, foreign roots, batches with more than one ICSR and
  schema errors. REST intake for pharmacy reports (`POST /api/intake/reports`) and reviewer E2B file
  uploads (`POST /api/intake/e2b`). Every accepted report gets a receipt and exactly one outbox event in one
  transaction; byte-identical resends return the original receipt; changed messages are follow-ups.
  Own Flyway schema (Oracle and SQL Server).
- `case-service`: `ReportIntake` (report.received → new case, new version for a follow-up that keeps
  confirmed codes and reopens review, nothing for a resend); `E2bExportService` (approved case with an
  approved narrative → E2B(R3) XML, validated; `queueable()` only when VALID); `GET /api/cases/{id}/e2b`
  and `/e2b/validation`.
- `common/web`: Spring MVC client errors (404, 405, 415) keep their status instead of becoming 500;
  unreadable bodies are 400.

Tests (local run in this container, Oracle Free via Testcontainers, no schema set available locally):
- common 18 tests (2 schema tests skipped locally, run in CI), intake-gateway 11 (1 schema test skipped
  locally), case-service 13; all green.
- JaCoCo line coverage (local): common 91.0% (514/565), intake-gateway 87.1% (229/263), case-service 87.6%
  (408/466).
- Official-schema results come from CI: see "M7 CI evidence" below.

Problems hit: Spring Boot's web-services auto-configuration backs off when the application declares
`@EnableWs` (the dispatcher servlet was never registered; fixed by relying on Boot); WSDL generation needs
`wsdl4j` on the classpath; a `${random.uuid}` test password resolves to a new value on every lookup, so
server and test client disagreed (fixed with a fixed test-only value); Oracle 23 maps Java `boolean` to its
native `BOOLEAN` type, not `NUMBER(1)`.

## Environment re-tests

### After the worker restart (during M6)

Still refused: fis.fda.gov, rxnav.nlm.nih.gov, vaers.hhs.gov, huggingface.co, database.ich.org.

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
| E2B(R3) JAXB classes | generate JAXB classes from the ICH XSDs into `common` | DOM writer/reader for the mapped subset, validated against the official schemas in CI | the schemas are not committed and cannot be downloaded in the build container, so code generation would break offline builds; validation keeps the output honest (`docs/E2B_R3.md`) |
| FDA regional elements | fetch FDA regional E2B(R3) elements | not included | FDA hosts refuse both this container and GitHub's runners |
| E2B schema source | ICH XSDs | EMA's published copy of the ICH E2B(R3) schema set | ICH and FDA downloads are blocked; EMA's copy is reachable from CI |
| ICH sample messages | round-trip official samples if the package has them | own fixtures only | the ICH package could not be downloaded |
| gitleaks locally | n/a | Docker Hub `zricethezav/gitleaks` image locally; CI uses `ghcr.io/gitleaks/gitleaks` | ghcr.io image layers are served from a blocked host in this container |
