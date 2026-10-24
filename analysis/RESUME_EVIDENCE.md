# Resume evidence

<!-- The claims table from BUILD_INSTRUCTIONS §9: for each claim, the code, the test or run that proves it, the
     result with its scope, and its status. A claim is ✅ only with evidence in this repository; everything else
     is "pending" with the reason. Never round up, never invent. -->

Status key: **✅** proven here · **✅ (scope)** proven, but only in the scope named · **pending** (why)

| # | Claim (as it may appear on a resume) | Code | Evidence | Result and scope | Status |
|---|---|---|---|---|---|
| C1 | Trained a self-supervised Transformer from scratch on **[N]M** FAERS reports | `ml/pharmavigil_ml/safetyformer/` | `ml/tests/test_safetyformer.py`; Kaggle notebook `ml/notebooks/kaggle/safetyformer_full.ipynb` | trains on synthetic reports in tests; CPU throughput about 750 reports/s (synthetic, 4 cores) | **pending**: FAERS unreachable from the build environment (owner steps 4–5) |
| C2 | SafetyFormer AUC **[x]** on OMOP vs **[y]** for PRR/ROR (and EU-ADR) | `ml/.../signals/`, `evaluate.py` | `ml/tests/` (hand-computed PRR/ROR/IC, EBGM, AUC with bootstrap CI) | reference sets are real (OMOP 399 pairs, EU-ADR 93; `analysis/m2/refsets.json`); no real AUC yet | **pending**: needs the FAERS gold tables |
| C3 | Flagged **[k]%** of later FDA label changes a median **[m]** months early | `safetyformer/timetravel.py`, ADR-006 | unit tests of snapshots and lead times | — | **pending**: label-change source (SrLC, accessdata.fda.gov, DailyMed) unreachable; no dates are invented |
| C4 | Fine-tuned MedDRA coder: **[F1]** micro-F1, **[r]%** top-5 recall | `ml/pharmavigil_ml/coder/`, ADR-007 | `ml/tests/`; Kaggle notebook `meddra_coder.ipynb` | — | **pending**: VAERS and Hugging Face unreachable; GPU run is owner step 6 |
| C5 | Seriousness triage PR-AUC **[p]**; duplicate detection P/R **[a]/[b]** | `ml/pharmavigil_ml/triage/` | `ml/tests/` (outcome-leakage guard, metrics) | — | **pending**: needs FAERS |
| C6 | All models served in Java via ONNX Runtime, parity < **ε** | `case-service/.../models/`, ADR-008 | `ModelParityTest` (CI) | identical token ids and feature vectors; scores within 1e-5 (coder 1e-4); identical top-5 rankings | **✅ (synthetic fixture bundle)**: real models pending C1/C4/C5 |
| C7 | Pharmacist report → coded, triaged, schema-valid E2B(R3) submission, ACKed over JMS | all five services, `services/e2e-tests` | `EndToEndTest` 3/3 in CI against the official E2B(R3) schemas (run 39763594556); Playwright smoke test on the compose stack (CI) | report → case → review → approve → E2B(R3) → mock regulator ACK; invalid file NACKed and back to review | **✅** (mock regulator) |
| C8 | Kafka pipeline with outbox, idempotent consumers, DLQ and replay-safety | `common/outbox`, `case-service/.../pipeline`, ADR-001 | `KafkaPipelineTest`, `EndToEndTest`, `OutboxRelayTest` (CI) | full replay of a case's events changes nothing; poison messages land in `<topic>.dlq`; broker down keeps rows pending | **✅** |
| C9 | SOAP partner intake (Spring-WS, WSDL) with faults and WS-Security | `intake-gateway/.../soap`, ADR-009 | `SoapIntakeContractTest` 7/7 in CI | UsernameToken + Timestamp; Client faults for bad XML, foreign roots, batches, schema errors | **✅** |
| C10 | JPA/Hibernate on Oracle, portable to SQL Server | Flyway `db/<service>/{oracle,sqlserver}`, ADR-003 | CI jobs *Java (Maven verify)* (Oracle Free) and *Java on SQL Server* | both green on every push | **✅** |
| C11 | Jenkins pipeline, Helm on Kubernetes (kind e2e), Terraform for Azure (AKS, ACR, Key Vault) | `ops/jenkins`, `ops/helm`, `ops/k8s`, `infra/azure`, ADR-010 | *Jenkins pipeline (headless)* run 40059501594 and `analysis/m10/jenkins-run.log`; *Kubernetes e2e (kind)* run 40059501640; *Azure Terraform* CI job | Jenkins (JCasC, pipeline as code): tests, coverage gate, images, Helm lint, kind deploy, smoke test 2/2, all green; kind: whole stack with Strimzi Kafka, 11 pods Running, smoke test 2/2; Terraform: fmt, validate, 2 tests, Checkov 16 passed / 0 failed / 20 skipped with reasons (locally) | **✅ (Jenkins and kind on a GitHub runner; Terraform validated, never applied)**: Azure apply is owner step 9 |
| C12 | Grafana dashboards for pipeline, AI quality and signals; k6 throughput **[r] reports/min**, p95 **[t] s** | `ops/grafana`, `ops/k6` | `docs/OBSERVABILITY.md`, `docs/PERFORMANCE.md`, `analysis/m10/*.json`, screenshots | 3 dashboards, 9 alert rules, 31 queries checked (0 failed); load: peak **2,400 reports/min accepted**, **2,303 cases coded/min**, intake→coded **p95 7.5 s**, p50 0.94 s, 0 failed requests | **✅ (local compose stack, 4 vCPU / 15.7 GiB, synthetic reports, fixture models)** |
| C13 | Fixed all 11 problems in the 2024 JSP/MySQL app, each with a test | — | — | — | **removed** by the owner (Amendment 1: M1 dropped, project started fresh) |

## Wording that is true today

- "Built an event-driven pharmacovigilance platform (5 Spring Boot services, Kafka with a transactional outbox,
  JMS regulator gateway, Oracle with SQL Server portability) that turns a pharmacy report into a schema-valid
  ICH E2B(R3) submission; end-to-end tested with Testcontainers."
- "Served ML models inside Java with ONNX Runtime, with a parity test against the Python pipeline."
- "Load-tested with k6 and fixed two bottlenecks the metrics exposed (batched duplicate lookup, pipelined outbox
  relay): p95 intake-to-coded latency from 103 s to 7.5 s on a local 4-vCPU stack."
- "Instrumented the pipeline with Micrometer/Prometheus and OpenTelemetry (trace context carried through the
  outbox) and built three Grafana dashboards with alert rules."

Not yet true (do not use until the pending rows above are ✅): any model accuracy, AUC, F1, lead time or FAERS
volume; "deployed on Azure" (until owner step 9 is done).
