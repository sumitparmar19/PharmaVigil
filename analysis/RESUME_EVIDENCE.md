# Resume evidence

<!-- The claims table from BUILD_INSTRUCTIONS §9: for each claim, the code, the test or run that proves it, the
     result with its scope, and its status. A claim is ✅ only with evidence in this repository; everything else
     is "pending" with the reason. Never round up, never invent. -->

Status key: **✅** proven here · **✅ (scope)** proven, but only in the scope named · **pending** (why)

| # | Claim (as it may appear on a resume) | Code | Evidence | Result and scope | Status |
|---|---|---|---|---|---|
| C1 | Trained a self-supervised Transformer from scratch on **10.3M** FAERS reports | `ml/pharmavigil_ml/safetyformer/` | `analysis/p3/safetyformer.json`, `ml/MODEL_CARD_safetyformer.md` | 10,302,667 training / 542,900 validation reports (time split), CPU-sized (5.3M parameters, 10,000 steps × 256, 36 min on 4 cores); validation recall@10 0.477 | **✅ (subset: FAERS 2019Q1–2026Q2, CPU-sized)**: full size and 2004 onward are owner steps 4–5 |
| C2 | SafetyFormer AUC on OMOP vs PRR/ROR/IC/EBGM (and EU-ADR) | `ml/.../signals/`, `evaluate.py` | `analysis/p2/baselines.json`, `analysis/p3/safetyformer.json` | same 388 OMOP pairs: SafetyFormer 0.765, PRR 0.759, ROR 0.759, EBGM 0.765, **IC 0.821**; EU-ADR (92 pairs): SafetyFormer 0.813, classic methods 0.91–0.93. **SafetyFormer does not beat the classic methods**; do not claim it does | **✅ measured (subset: FAERS 2019Q1–2026Q2)**: the result is a negative one |
| C3 | Flagged **[k]%** of later FDA label changes a median **[m]** months early | `safetyformer/timetravel.py`, ADR-006 | unit tests of snapshots and lead times | — | **pending**: needs dated label changes; the reference sets have none and FDA's SrLC server refuses automated downloads (not worked around; `analysis/p2/README.md`) |
| C4 | MedDRA coder: **[F1]** micro-F1, **[r]%** top-5 recall | `ml/pharmavigil_ml/coder/`, ADR-007 | `analysis/p4/coder.json`, `ml/coder/MODEL_CARD.md` | the demo runs a zero-shot coder (pretrained biomedical encoder, retrieval over the 3,000 most reported FAERS PTs, int8 ONNX in Java); no accuracy measured | **pending**: needs labelled VAERS narratives (owner step 14) and the GPU fine-tune (owner step 6) |
| C5 | Seriousness triage PR-AUC **0.969**; duplicate detection P/R **0.984 / 0.979** | `ml/pharmavigil_ml/triage/` | `analysis/p4/triage.json`, `analysis/p4/duplicates.json`, model cards | triage: 1,000,000 cases, test from 2025-01-01 (201,450 reports), ROC AUC 0.960; **within US reports 0.924 ROC / 0.883 PR**, non-US 0.923 ROC (reporter country is the top feature). Duplicates: proxy label (follow-up versions of one case), PR-AUC 0.998 | **✅ (subset: FAERS 2019Q1–2026Q2)**: quote the within-US triage number with the overall one; say "proxy label" for duplicates |
| C6 | All models served in Java via ONNX Runtime, parity < **ε** | `case-service/.../models/`, ADR-008 | `ModelParityTest` (CI); `analysis/p4/parity.json`; `analysis/p6/memory_amd64_real_data.json` | Java vs Python: identical token ids and feature vectors; scores within 1e-5 (coder 1e-4) on the fixture bundle; Python-side ONNX parity of the trained models 2.3e-7 (triage) and 8.2e-8 (duplicates); the trained bundle loads and runs in the demo stack (200 of 200 replayed reports coded, triaged and acknowledged) | **✅**: Java-side parity test on the fixture bundle; trained bundle verified end to end in the demo stack |
| C7 | Pharmacist report → coded, triaged, schema-valid E2B(R3) submission, ACKed over JMS | all five services, `services/e2e-tests` | `EndToEndTest` 3/3 in CI against the official E2B(R3) schemas (run 39763594556); Playwright smoke test on the compose stack (CI) | report → case → review → approve → E2B(R3) → mock regulator ACK; invalid file NACKed and back to review | **✅** (mock regulator) |
| C8 | Kafka pipeline with outbox, idempotent consumers, DLQ and replay-safety | `common/outbox`, `case-service/.../pipeline`, ADR-001 | `KafkaPipelineTest`, `EndToEndTest`, `OutboxRelayTest` (CI) | full replay of a case's events changes nothing; poison messages land in `<topic>.dlq`; broker down keeps rows pending | **✅** |
| C9 | SOAP partner intake (Spring-WS, WSDL) with faults and WS-Security | `intake-gateway/.../soap`, ADR-009 | `SoapIntakeContractTest` 7/7 in CI | UsernameToken + Timestamp; Client faults for bad XML, foreign roots, batches, schema errors | **✅** |
| C10 | JPA/Hibernate on Oracle, portable to SQL Server | Flyway `db/<service>/{oracle,sqlserver}`, ADR-003 | CI jobs *Java (Maven verify)* (Oracle Free) and *Java on SQL Server* | both green on every push | **✅** |
| C11 | Jenkins pipeline, Helm on Kubernetes (kind e2e), Terraform for Azure (AKS, ACR, Key Vault) | `ops/jenkins`, `ops/helm`, `ops/k8s`, `infra/azure`, ADR-010 | *Jenkins pipeline (headless)* run 40059501594 and `analysis/m10/jenkins-run.log`; *Kubernetes e2e (kind)* run 40059501640; *Azure Terraform* CI job | Jenkins (JCasC, pipeline as code): tests, coverage gate, images, Helm lint, kind deploy, smoke test 2/2, all green; kind: whole stack with Strimzi Kafka, 11 pods Running, smoke test 2/2; Terraform: fmt, validate, 2 tests, Checkov 16 passed / 0 failed / 20 skipped with reasons (locally) | **✅ (Jenkins and kind on a GitHub runner; Terraform validated, never applied)**: Azure apply is owner step 9 |
| C12 | Grafana dashboards for pipeline, AI quality and signals; k6 throughput **[r] reports/min**, p95 **[t] s** | `ops/grafana`, `ops/k6` | `docs/OBSERVABILITY.md`, `docs/PERFORMANCE.md`, `analysis/m10/*.json`, screenshots | 3 dashboards, 9 alert rules, 31 queries checked (0 failed); load: peak **2,400 reports/min accepted**, **2,303 cases coded/min**, intake→coded **p95 7.5 s**, p50 0.94 s, 0 failed requests | **✅ (local compose stack, 4 vCPU / 15.7 GiB, synthetic reports, fixture models)** |
| C13 | Fixed all 11 problems in the 2024 JSP/MySQL app, each with a test | — | — | — | **removed** by the owner (Amendment 1: M1 dropped, project started fresh) |
| C14 | Ran real FAERS (2019Q1–2026Q2) through a Spark + Delta medallion pipeline | `data/pharmavigil_data/` | `analysis/p1/data_counts.json`, `analysis/m2/*.json` | 30 quarters, 12,902,877 report rows → 10,848,477 unique cases; 98.5% of drug mentions mapped to RxNorm | **✅ (subset: FAERS 2019Q1–2026Q2)** |
| C15 | A live demo stack that fits a free 12 GB server | `docker-compose.demo.yml`, `ops/demo/` | `analysis/p6/*.json`, `docs/DEMO_HOSTING.md`, *Demo stack on arm64* (CI) | real demo data: 5,748 MiB idle, 6,055 MiB peak, 200 of 200 replayed FAERS reports acknowledged in 42 s; arm64 runner: 40 of 40 | **✅ (measured in the build container and on a GitHub arm64 runner)**: the public server is owner steps 15–18 |

## Wording that is true today

- "Built an event-driven pharmacovigilance platform (5 Spring Boot services, Kafka with a transactional outbox,
  JMS regulator gateway, Oracle with SQL Server portability) that turns a pharmacy report into a schema-valid
  ICH E2B(R3) submission; end-to-end tested with Testcontainers."
- "Served ML models inside Java with ONNX Runtime, with a parity test against the Python pipeline."
- "Load-tested with k6 and fixed two bottlenecks the metrics exposed (batched duplicate lookup, pipelined outbox
  relay): p95 intake-to-coded latency from 103 s to 7.5 s on a local 4-vCPU stack."
- "Instrumented the pipeline with Micrometer/Prometheus and OpenTelemetry (trace context carried through the
  outbox) and built three Grafana dashboards with alert rules."

- "Processed 12.9M FAERS adverse-event report rows (2019–2026) into 10.8M de-duplicated cases with a Spark/Delta
  medallion pipeline and RxNorm normalization (98.5% of drug mentions mapped)."
- "Benchmarked PRR, ROR, IC and EBGM signal detection on the OMOP and EU-ADR reference sets with bootstrap
  confidence intervals (best: IC, AUC 0.82 on 388 OMOP pairs), and found and fixed a non-reproducible EBGM
  prior fit."
- "Trained a self-supervised Transformer from scratch on 10.3M FAERS reports; it did not beat the classic
  statistics on the reference sets, and a leakage check showed random splits overstate its recall by 0.06."
- "Built a seriousness triage model (LightGBM, ONNX in Java): PR-AUC 0.97 overall and 0.88 within US reports on
  a time-split test set."

Not yet true (do not use until the pending rows above are ✅): coder accuracy (F1, top-5 recall), time-travel lead
times, "SafetyFormer beats PRR/ROR", "deployed on Azure" (owner step 9), "live demo at <URL>" (owner steps 15–18).
