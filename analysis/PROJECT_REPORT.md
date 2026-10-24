# PharmaVigil: project report (M0–M12)

<!-- The final report in the format of BUILD_INSTRUCTIONS §11: summary, milestones, numbers with their scope,
     claims, deviations, problems solved, open owner steps and next work. Every number links to a committed file
     or a CI run. -->

## 1. Summary

1. PharmaVigil is a pharmacovigilance platform: a pharmacy counter app, a case pipeline, a reviewer console, an
   ICH E2B(R3) gateway and a signal service, plus a Python data and ML platform for FAERS/VAERS.
2. Five Spring Boot 4.1 services (Java 21) on Oracle Database Free, portable to SQL Server; React 19 UI.
3. Kafka carries the internal pipeline through a transactional outbox with idempotent consumers and dead-letter
   topics; JMS (Artemis) carries the hand-over to a mock regulator that answers ACK/NACK.
4. A pharmacist's report becomes a de-duplicated, coded, triaged, reviewed case and a schema-valid E2B(R3)
   submission; this runs end to end in CI against the official schemas.
5. The duplicate, coder and seriousness models run inside Java with ONNX Runtime, with a parity test against
   Python; the committed bundle is a synthetic fixture.
6. The data platform (FAERS ETL, RxNorm, reference sets), signal baselines (PRR, ROR, IC, EBGM), SafetyFormer,
   the MedDRA coder and the triage models are written and tested on fixtures and synthetic data.
7. **Real-data model results are pending:** FAERS, VAERS, RxNav and Hugging Face are unreachable from the build
   environment; the notebooks and owner steps to produce them are ready.
8. Operations: Prometheus metrics, OpenTelemetry traces through the outbox, three Grafana dashboards, nine
   alert rules, k6 load tests that found and fixed two bottlenecks (p95 intake→coded 103 s → 7.5 s, local stack).
9. Delivery: GitHub Actions CI on every push, Helm charts with Strimzi on kind, a Jenkins pipeline as code, and
   Azure Terraform (AKS, ACR, Key Vault, budget, OIDC) that is validated and Checkov-clean but never applied.
10. Every UI page carries the disclaimers; AI outputs are suggestions recorded next to the reviewer's decision.

## 2. Milestones

| M | Milestone | Status | Key commits | Evidence |
|---|---|---|---|---|
| M0 | Bootstrap, context, rules | ✅ | `6ce5906`, `99b7ea9` | `analysis/M0_REVIEW.md`, CI workflows |
| M1 | Legacy audit | removed by owner | — | Amendment 1 |
| M2 | Data platform | code ✅, real run pending | `92dc908` | `data/tests` (43), `analysis/m2/refsets.json` |
| M3 | Signal baselines | code ✅, real AUCs pending | `562425e` | `ml/tests`, ADR-005 |
| M4 | SafetyFormer | code ✅, real training pending | `b75616b` | `ml/tests`, model card, ADR-006 |
| M5 | Coder, triage, duplicates, ONNX | code ✅, real metrics pending | `2facc9c` | `ml/tests` (49 in `ml/`), model cards, ADR-007 |
| M6 | Java platform core | ✅ | `7f4b640`, `f2070e7` | ADR-003, Oracle + SQL Server CI |
| M7 | E2B(R3) and SOAP | ✅ | `59302f8` (+ `0195fb0`, `1d0f78c`, `ce8658a`, `d3641a0`) | `docs/E2B_R3.md`, ADR-004, ADR-009 |
| M8 | Kafka, ONNX in Java, JMS, narrative | ✅ | `d293aa3`, `85a53f1`, `e9f86d7`, `20aad47`, `fdcb825`, `c4b372a` | ADR-001, ADR-002, ADR-008, `EndToEndTest` |
| M9 | Frontend | ✅ | `70044bf` | 33 Vitest tests, Playwright smoke test (CI) |
| M10 | Operations | ✅ (kind e2e run 40059501640, Jenkins run 40059501594) | `10b6958`, `2b2647a`, `5ab1744`, `58a898b`, `1c14a94` | `docs/OBSERVABILITY.md`, `docs/PERFORMANCE.md`, `docs/KUBERNETES.md`, `docs/JENKINS.md`, `analysis/m10/` (incl. `jenkins-run.log`) |
| M11 | Azure infrastructure (code only) | ✅ validated, not applied | `871cc45` | `infra/azure`, `docs/AZURE.md` |
| M12 | Docs, evidence, report | ✅ | `c7b64fb` and this commit | README, ADRs 1–10, `RESUME_EVIDENCE.md`, `INTERVIEW_PREP.md` |

## 3. Numbers (each with its scope)

**Tests** (CI run 40053742919 on `2b2647a`, GitHub `ubuntu-latest`, official E2B(R3) schemas present):

| Module | Tests | Failures | Skipped | Line coverage (local run, gate ≥ 70%) |
|---|---|---|---|---|
| common | 22 | 0 | 0 | 86.5% (581/672) |
| pharmacy-service | 25 | 0 | 0 | 94.8% (490/517) |
| intake-gateway | 11 | 0 | 0 | 87.1% (243/279) |
| case-service | 35 | 0 | 0 | 92.2% (1248/1354) |
| signal-service | 7 | 0 | 0 | 91.2% (248/272) |
| submission-gateway | 3 | 0 | 0 | 83.8% (171/204) |
| e2e-tests | 3 | 0 | 0 | n/a (no main code) |
| **Java total** | **106** | 0 | 0 | gate passed in CI for every service |
| Python `data` | 43 | 0 | — | pytest + ruff |
| Python `ml` | 49 | 0 | — | pytest + ruff |
| Frontend | 33 Vitest + 2 Playwright | 0 | — | typecheck, lint, build |
| Terraform | 2 `terraform test` runs | 0 | — | Checkov 16 passed / 0 failed / 20 skipped with reasons (local) |

**Delivery pipelines** (GitHub `ubuntu-latest`, PR head `1c14a94`): *Kubernetes e2e (kind)* run 40059501640 green in 9 min
(kind cluster, Strimzi Kafka, Helm install of the whole stack, Playwright smoke test 2/2). *Jenkins pipeline
(headless)* run 40059501594 green in 13.5 min: 103 integration tests on Testcontainers with 0 failures (3 skipped that need the
E2B(R3) schemas, which only the main CI fetches), coverage gate met for all six services, six images, Helm lint,
11 pods Running on kind, smoke test 2/2 (`analysis/m10/jenkins-run.log`).

Coverage figures are from the local full `mvn verify` run in the build container (no schema set there, so a
few schema-only branches are not covered); CI enforces the same gate with the schemas present.

**Pipeline and operations** (scope: local docker compose stack on 4 vCPU / 15.7 GiB, synthetic reports, fixture
models; `analysis/m10/`):

| Measure | Run 1 (baseline) | Run 3 (after fixes) |
|---|---|---|
| Intake requests (failed), HTTP p95 | 5,730 (0), 21.1 ms | 5,730 (0), 22.4 ms |
| Peak reports accepted / min | 2,400 | 2,400 |
| Peak cases coded / min | 628 | 2,303 |
| Intake → coded p50 / p95 / max | 27.0 s / 103.1 s / 100.4 s | 0.94 s / 7.5 s / 10.8 s |
| Max consumer lag / outbox backlog | 592 / 46 | 0 / 73 |

Dashboards and alerts: 31 queries checked against the running stack, 0 failed, 19 returned data
(`analysis/m10/dashboard_queries.json`). Tracing: one trace with 182 spans across three services
(`analysis/m10/trace_example.json`).

**Data and models:** reference sets are real (OMOP 399 pairs: 165 positive / 234 negative; EU-ADR 93 pairs: 43 /
50; OHDSI MethodEvaluation commit `d7305910f6c6a390a9e423ffc1685a97c017183e`). Everything else that depends on
FAERS/VAERS is **pending full data**; the only model-side measurement is SafetyFormer's CPU throughput on
synthetic data (about 750 reports/s, 4 cores), and Java/Python parity on the fixture bundle (scores within 1e-5,
coder 1e-4).

## 4. Claims (§9)

Full table with code and evidence: [`analysis/RESUME_EVIDENCE.md`](RESUME_EVIDENCE.md).

| Claim | Status |
|---|---|
| C1 SafetyFormer trained on FAERS | pending full data |
| C2 SafetyFormer vs PRR/ROR AUC | pending full data |
| C3 label changes flagged early | pending (label-change source unreachable) |
| C4 MedDRA coder F1 / top-5 | pending full data + GPU |
| C5 triage PR-AUC, duplicate P/R | pending full data |
| C6 models in Java via ONNX, parity | ✅ (synthetic fixture bundle) |
| C7 report → E2B(R3) → ACK over JMS | ✅ |
| C8 outbox, idempotency, DLQ, replay | ✅ |
| C9 SOAP intake with WS-Security | ✅ |
| C10 Oracle + SQL Server | ✅ |
| C11 Jenkins, Helm on kind, Terraform | ✅ Jenkins pipeline and Helm on kind green in CI; Terraform validated, tested, Checkov-clean (never applied) |
| C12 dashboards, k6 numbers | ✅ (local stack scope) |
| C13 legacy fixes | removed by owner |

## 5. Deviations

The full table, with reasons, is in `analysis/PROGRESS.md` ("Deviations from the build instructions"). The
important ones: no real FAERS/VAERS data (blocked hosts, §6 fallback); M1 removed by the owner; TypeScript 6.0.3
(newest the toolchain supports); Kafka broker 4.3.2 in compose and 4.2.0 under Strimzi (4.4.0 only as release
candidates); E2B(R3) written with DOM and validated against EMA's copy of the official schemas in CI instead of
generated JAXB classes; signal-service serves nothing until a real export exists; a real JCasC Jenkins instead of
Jenkinsfile Runner; managed identities instead of an app registration for Azure OIDC; Key Vault secret values
typed by the owner, never by Terraform.

## 6. Problems hit and how they were solved

| Problem | Fix |
|---|---|
| A regulator answer could arrive before the gateway had recorded its own message | record SENDING before sending; lock the row while answering (STAR 1) |
| All five services shipped Flyway scripts at the same classpath path, breaking the one-JVM end-to-end test | per-service locations `db/<service>/{vendor}` |
| Artemis blocked producers above 90% host disk use in the shared container | disk check off for test brokers only |
| Duplicate check loaded each candidate in separate queries; outbox relay waited per event | one query plus batch fetching; pipelined batch sends (STAR 2) |
| Triage could overwrite a case a reviewer had already opened | triage skips cases already in review; new test |
| Oracle on Kubernetes never opened (volume hid the pre-built database; ORA-01081 on restart) | no volume over `oradata`; init container waits for dependencies (STAR 3) |
| The first SafetyFormer ONNX export passed parity only at the traced shape | a second-shape parity test for every Transformer export |
| k6's summary export contained sign-in tokens | stripped before any summary is kept (`collect_results.py --strip-summary`) |
| The mock azurerm provider cannot fill AKS's computed kubelet identity | explicit user-assigned kubelet identity (also lets AcrPull be granted before nodes pull) |
| Jenkins: the job could not find a pull request's merge commit; images built with the legacy builder lost the OpenTelemetry agent; the cluster was deleted before diagnostics ran | temporary branch at HEAD; buildx in the Jenkins image (reproduced with `DOCKER_BUILDKIT=0`); teardown in `post { cleanup }` |
| Build container: blocked hosts, Docker Hub rate limits, disk allowance, two container restarts | `mirror.gcr.io` locally, filesystem mirror for Terraform, cache clean-up; CI is the source of truth for anything that needs the internet |

## 7. Owner steps still open (in order)

From `OWNER_STEPS.md` (step 3, Dependency graph, is done): 1 network access re-check → 13 confirm the two CodeQL
alerts are closed → 4 FAERS on Databricks → 5 SafetyFormer on
Kaggle → 6 coder on Kaggle → 7 label 50 narratives → 8 MedDRA licence → 9 Azure for a day (`docs/AZURE.md`) →
10 optional Claude narratives → 11 optional Grafana Cloud → 12 v1.0 release after merge.

## 8. Suggested next work

1. Run owner steps 4–7 and fill the pending claims with measured numbers (then regenerate the signal export so the
   counter shows real evidence).
2. Check the E2B(R3) code values and OIDs against the ICH implementation guide and its example instances.
3. Run the Azure deployment once (owner step 9) and record the cost.
4. Add a second Kafka broker and a partition-count test; measure the pipeline on Kubernetes with k6.
5. Replace the duplicate-detection proxy label with a hand-labelled duplicate set.
