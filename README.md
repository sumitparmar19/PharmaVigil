# PharmaVigil

**An AI drug-safety (pharmacovigilance) platform: from the pharmacy counter to the safety signal and back.**

[![CI](https://github.com/sumitparmar19/PharmaVigil/actions/workflows/ci.yml/badge.svg)](https://github.com/sumitparmar19/PharmaVigil/actions/workflows/ci.yml)
[![Live smoke test](https://github.com/sumitparmar19/PharmaVigil/actions/workflows/live-smoke.yml/badge.svg)](https://github.com/sumitparmar19/PharmaVigil/actions/workflows/live-smoke.yml)
[![Demo stack on arm64](https://github.com/sumitparmar19/PharmaVigil/actions/workflows/demo-arm64.yml/badge.svg)](https://github.com/sumitparmar19/PharmaVigil/actions/workflows/demo-arm64.yml)

**Live demo:** being set up on an Oracle Cloud Always Free server (owner steps 15–18); its address goes here when
it is up. The landing page shows the two shared demo accounts (`demo-pharmacist`, `demo-reviewer`) and their
password; there is nothing to sign up for. "Run the pipeline" sends 200 real FAERS reports through the whole system
and shows each stage's counter as it moves.

**Headline numbers** (each from a committed result file, with its data scope):

| | Result | Scope and file |
|---|---|---|
| Real FAERS processed | 12,902,877 report rows → **10,848,477 unique cases**; RxNorm mapping covers 98.5% of drug mentions | subset: FAERS 2019Q1–2026Q2 — `analysis/p1/data_counts.json` |
| Signal detection, best classic method | IC: **AUC 0.821** (0.779–0.860) on 388 OMOP pairs; 0.92 on 92 EU-ADR pairs | same — `analysis/p2/baselines.json` |
| SafetyFormer (self-supervised Transformer, CPU-sized) | AUC 0.765 on OMOP, 0.813 on EU-ADR: **below the classic methods** | same — `analysis/p3/safetyformer.json` |
| Seriousness triage | ROC AUC 0.960 overall; **0.92 within US and within non-US reports** (country alone explains part of the label) | same, time split at 2025-01-01 — `analysis/p4/triage.json` |
| Demo stack memory on arm64 | 5,428 MiB idle, 5,560 MiB peak during a replay; 40 of 40 replayed reports acknowledged | GitHub arm64 runner, fixture models — `analysis/p6/memory_arm64_ci_fixture.json` |

> **Not for clinical or regulatory use.** Spontaneous reports do not prove that a drug caused a reaction.
> The pharmacy side uses synthetic patients only. AI outputs are suggestions; a human reviewer decides.

A pharmacist reports a side effect in one click. The report is de-duplicated, MedDRA-coded and triaged by
models trained for this project, reviewed by a person, exported as a schema-valid ICH E2B(R3) file and
acknowledged by a (mock) regulator over JMS. Drug–event signals computed from public FDA adverse-event reports
(FAERS) come back to the counter as evidence the next time the drug is dispensed.

| | |
|---|---|
| Plan, research and sources | [`docs/PLAN.md`](docs/PLAN.md) |
| Progress, evidence and deviations | [`analysis/PROGRESS.md`](analysis/PROGRESS.md), [`analysis/PROJECT_REPORT.md`](analysis/PROJECT_REPORT.md) |
| Versions and components | [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) |
| Decisions | [`docs/adr/`](docs/adr) (ADR-001 to ADR-010) |
| What only the owner can do | [`OWNER_STEPS.md`](OWNER_STEPS.md) |

## Status in one table

| Part | State | Evidence |
|---|---|---|
| Pharmacy app, reviewer console, signals page | working, tested | 33 Vitest tests; Playwright smoke test against compose (CI) |
| Report → coded, triaged, reviewed case → E2B(R3) → ACK over JMS | working, tested end to end | `services/e2e-tests` (3/3 in CI, official schemas) |
| Kafka pipeline (outbox, idempotent consumers, DLQ, replay-safe) | working, tested | `KafkaPipelineTest`, `EndToEndTest`, ADR-001 |
| SOAP partner intake (WS-Security) and E2B(R3) import/export | working, tested | `SoapIntakeContractTest`, `docs/E2B_R3.md` |
| Oracle primary, SQL Server portable | both test profiles green in CI | ADR-003 |
| Models in Java (ONNX Runtime) | parity proven on a synthetic fixture bundle; the bundle trained on real FAERS is in the demo-data release | `ModelParityTest`, ADR-008, `analysis/models/MANIFEST.md` |
| Data platform | real FAERS 2019Q1–2026Q2 through bronze → silver → gold (Spark + Delta) | `analysis/p1/data_counts.json` |
| Real-data model results | baselines, SafetyFormer, triage and duplicates measured; the coder is not evaluated (needs VAERS, owner step 14); the time-travel test is not computable (FDA's label-change database refuses automated downloads) | `analysis/p2/`, `analysis/p3/`, `analysis/p4/` |
| Live demo | stack, images, server setup, deploy with rollback, daily smoke test: built and green on arm64 in CI; the server itself is owner steps 15–18 | `docs/DEMO_HOSTING.md` |
| Observability, load test | measured on the local stack | `docs/OBSERVABILITY.md`, `docs/PERFORMANCE.md` |
| Helm on Kubernetes (kind), Jenkins | whole stack on kind and the full Jenkins pipeline green in CI (`analysis/m10/jenkins-run.log`) | `docs/KUBERNETES.md`, `docs/JENKINS.md` |
| Azure (AKS, ACR, Key Vault) | Terraform validated and Checkov-clean; **never applied** | `docs/AZURE.md`, owner step 9 |

## The problem

From the sources collected in `docs/PLAN.md` (not re-checked from the build environment, where most of these
sites are blocked):

- Outpatient adverse drug events cause about 4 emergency visits per 1,000 people a year in the US.
- FDA's FAERS database holds about 33 million reports and grows by about 2 million a year; case processing is
  largely manual and takes most of a drug-safety budget.
- Every symptom must be coded to MedDRA by hand or by exact match.
- Classic disproportionality statistics (PRR/ROR) reach AUC 0.64–0.73 on the OMOP reference set.
- Since 1 October 2026 FDA accepts postmarketing safety reports only as ICH E2B(R3) XML.
- Pharmacists see side effects at the counter, but reporting is voluntary and manual.

## Architecture

```mermaid
flowchart LR
  subgraph Counter["Pharmacy (synthetic patients)"]
    UI["React UI<br/>pharmacy · review · signals"]
    PH["pharmacy-service<br/>FEFO dispensing, reports"]
  end
  subgraph Pipeline["Safety pipeline"]
    IN["intake-gateway<br/>REST · E2B upload · SOAP"]
    CS["case-service<br/>dedup · coding · triage · narrative<br/>reviewer API · E2B export"]
    SG["submission-gateway<br/>JMS to the regulator"]
    MR["mock regulator<br/>schema check → ACK/NACK"]
  end
  subgraph Signals["Signals (public FAERS)"]
    ML["data/ + ml/ (Python)<br/>FAERS ETL · PRR/ROR/IC/EBGM · SafetyFormer"]
    SS["signal-service<br/>snapshot API"]
  end
  K[("Kafka")]
  A[("Artemis JMS")]
  O[("Oracle<br/>(SQL Server portable)")]
  UI --> PH
  UI --> CS
  UI --> SS
  PH -- "report (outbox relay)" --> IN
  IN -- report.received --> K
  K --> CS
  CS -- "case.ready" --> K
  K --> SG
  SG -- regulatory.outbound --> A --> MR
  MR -- regulatory.ack --> A --> SG
  SG -- submission.status --> K
  ML -- export folder --> SS
  PH -- "warnings at dispense" --> SS
  PH & IN & CS & SG & SS --- O
```

Five Spring Boot 4.1 services (Java 21), one schema each in Oracle Database Free; every event leaves through a
transactional outbox (ADR-001); Kafka carries the internal pipeline and JMS the regulator hand-over (ADR-002).
The UI is React 19 + TypeScript behind nginx, which routes each `/api/...` path to its service.

## The pipeline, step by step

```mermaid
sequenceDiagram
  autonumber
  actor P as Pharmacist
  participant PH as pharmacy-service
  participant IN as intake-gateway
  participant K as Kafka
  participant CS as case-service
  actor R as Safety reviewer
  participant SG as submission-gateway
  participant MR as Mock regulator (JMS)
  P->>PH: dispense (lots first-expiry-first-out), report a side effect
  PH-->>P: signal evidence for the drug (never a verdict)
  PH->>IN: de-identified report (relay retries while intake is down)
  IN->>K: report.received (outbox, same transaction as the receipt)
  K->>CS: duplicate check (model) → report.deduped
  K->>CS: MedDRA coding suggestions (model) → report.coded
  K->>CS: seriousness triage (model) → case.triaged
  K->>CS: narrative draft (Claude if configured, else template)
  R->>CS: accept or correct codes, seriousness, duplicate; approve narrative and case
  CS->>CS: export E2B(R3), validate against the official schemas
  CS->>K: case.ready (only if schema-valid)
  K->>SG: record message, send on regulatory.outbound
  SG->>MR: E2B(R3) XML
  MR-->>SG: ACK or NACK (with errors)
  SG->>K: submission.status → case ACKNOWLEDGED, or back to review
```

Every stage is idempotent (processed-event table), retried three times, then dead-lettered. Without a model,
cases go to manual coding or "seriousness unknown" and keep moving. Every AI suggestion stores the model name
and version next to the reviewer's final choice.

## Data and models

| | Source | Status |
|---|---|---|
| FAERS (adverse event reports) | FDA quarterly files, 2004Q1 onward; legacy AERS layout handled | **real**: 2019Q1–2026Q2 loaded in the build container (30 quarters, 10,848,477 unique cases); 2004 onward is owner step 4 (Databricks) — [dataset card](docs/DATASET_CARD_FAERS.md) |
| VAERS (vaccine reports with narratives) | HHS VAERS datasets | coder training and evaluation data; the files sit behind a CAPTCHA, so owner step 14 — [dataset card](docs/DATASET_CARD_VAERS.md) |
| Drug names → ingredients | NLM RxNorm (RxNav API) | **real**: the 20,000 most frequent names (99.2% of mentions) looked up; 98.5% of mentions mapped — `analysis/m2/rxnorm_map_run.json` |
| Reference sets | OHDSI MethodEvaluation (OMOP, EU-ADR) | **real**: OMOP 399 pairs (165+/234−), EU-ADR 93 pairs (43+/50−) — `analysis/m2/refsets.json` |
| Pharmacy patients | Synthea-format synthetic CSV | demo data only |

| Model | What it does | Design |
|---|---|---|
| Baselines | PRR, ROR, IC (shrunk), EBGM/EB05 (MGPS) | `ml/.../signals`, ADR-005 |
| **SafetyFormer** | self-supervised set Transformer trained from scratch on FAERS reports; scores drug–event pairs | ADR-005, ADR-006 (time-travel test), [model card](ml/signals/MODEL_CARD.md), [real-data results](ml/MODEL_CARD_safetyformer.md) |
| MedDRA coder | biomedical encoder + head, merged with retrieval over PT names; the demo runs a zero-shot version (no training labels) over the 3,000 most reported PTs | ADR-007, [model card](ml/coder/MODEL_CARD.md) |
| Seriousness triage | LightGBM; never sees outcome fields or outcome-revealing terms | [model card](ml/triage/MODEL_CARD_SERIOUSNESS.md) |
| Duplicate detection | LightGBM on pair features (a proxy: FAERS versions of one case) | [model card](ml/triage/MODEL_CARD_DUPLICATES.md) (states the proxy) |

Training labels come from human coding in FAERS/VAERS and Sumit's hand-labelled set, never from Claude. No
MedDRA dictionary file is in the repository; only PT strings that appear in public FAERS/VAERS files.

## Results (every number carries its scope)

**Engineering** (measured; CI run on GitHub runners unless noted):

| Measure | Result | Scope |
|---|---|---|
| Java tests | 103 tests, 0 failures, 0 skipped (CI, official schemas present); coverage gate ≥ 70% per service | CI run 39763594556 (M8); later runs add tests |
| End-to-end | report → ACKed E2B(R3) submission; full Kafka replay changes nothing; poison message in DLQ; invalid file NACKed | `EndToEndTest`, CI |
| Python tests | data and ml packages green (pytest + ruff) | CI |
| Frontend | 33 Vitest tests; Playwright smoke test against the compose stack | CI |
| Load (k6, ramp to 40 reports/s) | 5,730 intake requests, 0 failed, HTTP p95 22 ms; peak 2,300 cases coded/min; intake→coded p50 0.94 s, p95 7.5 s; max consumer lag 0 | local compose stack, 4 vCPU / 15.7 GiB, synthetic reports, fixture models ([PERFORMANCE.md](docs/PERFORMANCE.md)) |
| Load-test fixes | duplicate check batched, outbox relay pipelined: p95 intake→coded 103 s → 7.5 s | same scope, three runs in `analysis/m10/` |
| Model parity Java vs Python | identical token ids and features, scores within 1e-5 (coder 1e-4), identical top-5 | synthetic fixture bundle |
| Tracing | one trace across pharmacy, intake, Kafka and all case-service stages (182 spans) | local stack |

**Models on real data** (scope for all rows: subset: FAERS 2019Q1–2026Q2; AUCs with 95% bootstrap intervals):

| Model | Result | File |
|---|---|---|
| PRR / ROR / IC / EBGM, primary-suspect drugs | OMOP (388 pairs): 0.759 / 0.759 / **0.821** / 0.765; EU-ADR (92 pairs): 0.916 / 0.916 / 0.919 / 0.910. Counting every drug role is clearly worse (OMOP IC 0.688) | `analysis/p2/README.md` |
| SafetyFormer (d = 128, 2 layers, 10,000 steps on CPU) | OMOP 0.765 (0.719–0.811), EU-ADR 0.813 (0.721–0.897): **does not beat the classic methods** on the same pairs | `analysis/p3/safetyformer.json`, [card](ml/MODEL_CARD_safetyformer.md) |
| Seriousness triage (LightGBM, 1,000,000 cases, test from 2025-01-01) | ROC AUC 0.960, PR-AUC 0.969; within US reports 0.924 / 0.883, within non-US 0.923 | `analysis/p4/triage.json` |
| Duplicates (LightGBM, proxy label: versions of one case) | PR-AUC 0.998; precision 0.984, recall 0.979 at the best-F1 threshold. Measures follow-up matching, an easy task, not general duplicate detection | `analysis/p4/duplicates.json` |
| SafetyFormer leakage check | a shuffled train/test split overstates masked-reaction recall@10 by 0.061 (0.498 vs 0.437 time-ordered), so all splits here are by date | `analysis/p3/leakage.json` |
| MedDRA coder (zero-shot) | not evaluated: needs narratives with human-assigned PTs (VAERS, owner step 14) | `analysis/p4/coder.json` |
| Time-travel test | not computed: the reference sets carry no dates and FDA's label-change database (SrLC) refuses automated downloads | `analysis/p2/README.md` |

## Run it

One command for the local stack (Docker with compose, Java 21, Maven):

```bash
scripts/dev-up.sh                  # add --observability for Prometheus, Grafana and Jaeger
```

Then open http://localhost:8080 and sign in as `pharmacist` or `reviewer` with `PV_DEMO_PASSWORD` from the
generated `.env`. Tests:

```bash
cd services && mvn -B verify                                   # Java (Docker needed for Testcontainers)
cd data && uv venv && uv pip install -e '.[dev,spark]' && .venv/bin/pytest
cd ml   && uv venv && uv pip install -e '.[dev]' && .venv/bin/pytest
cd frontend && npm ci && npm test && npm run e2e               # e2e needs the stack running
```

Kubernetes: [`docs/KUBERNETES.md`](docs/KUBERNETES.md). Jenkins: [`docs/JENKINS.md`](docs/JENKINS.md).
Azure: [`docs/AZURE.md`](docs/AZURE.md).

## Before and after

The repository previously held a 2024 JSP/MySQL pharmacy app. The plan included an audit of it ("the
before"); that milestone (M1) was **removed by the owner** (Amendment 1), and PharmaVigil was started fresh.
No before/after comparison is claimed.

## Limits and ethics

- **Not for clinical or regulatory use.** Shown on every page; the signal API's answers carry the causation
  disclaimer.
- **No causation.** Signals are statistical associations in spontaneous reports; the counter shows them as
  evidence to discuss, never as a verdict.
- **No real patient data.** Public FAERS/VAERS only; synthetic patients on the pharmacy side; reports forwarded
  to intake are de-identified (age in years, sex, drug, text).
- **AI as suggestion.** Codes, seriousness, duplicates and narratives are confirmed by a reviewer; every
  decision is stored with the model's name, version, score and suggestion next to the reviewer's final value.
- **Narratives** are drafted only from structured, already-coded facts (no free text, no arithmetic); without an
  API key a deterministic template is used.
- **E2B(R3)**: a mapped subset; XSD validation proves structure, not business rules ([`docs/E2B_R3.md`](docs/E2B_R3.md)).
- **Fixture models** in the repository are tiny synthetic stand-ins for tests; their scores mean nothing and are
  labelled `fixture:` everywhere they appear.

## AI assistance

This project was built with AI pair-programming (Claude Code), from instruction files written with a planning
chat and verified by Sumit. Design choices and their reasons are in the ADRs; commits carry `Co-Authored-By`
trailers. Training labels never come from an AI model.

## Credits

FDA FAERS and openFDA; HHS VAERS; NLM RxNorm/RxNav; OHDSI MethodEvaluation reference sets (OMOP, EU-ADR); ICH
E2B(R3) schemas as published by the European Medicines Agency; Synthea (synthetic patient format); and the
open-source projects listed with their versions in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md). MedDRA® is a
registered trademark of ICH; no MedDRA dictionary content is distributed here.

## Licence

MIT. See [`LICENSE`](LICENSE).
