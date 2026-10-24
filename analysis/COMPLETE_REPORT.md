<!-- The complete hand-over report for this build phase (M0–M12): what was built in each milestone and how, every
     technology with its version and role, the state of the pull request, and everything Sumit has to do himself
     (settings, accounts, data runs, deployment, connections). Written to be sent to the planning chat for review.
     Every number here comes from a committed file or a named CI run. -->

# PharmaVigil: complete report of the build phase (M0–M12)

> Not for clinical or regulatory use. Spontaneous reports do not prove that a drug caused a reaction.
> Synthetic patients only.

| | |
|---|---|
| Repository | https://github.com/sumitparmar19/PharmaVigil |
| Branch | `claude/determined-wozniak-8tf7pa` |
| Pull request | https://github.com/sumitparmar19/PharmaVigil/pull/1 ("PharmaVigil v1 (M0–M12)"), **ready for review**, 30 commits, base `main` |
| Shorter companions | `analysis/PROJECT_REPORT.md` (§11 format), `analysis/PROGRESS.md` (log), `analysis/RESUME_EVIDENCE.md` (claims), `analysis/INTERVIEW_PREP.md`, `OWNER_STEPS.md` |

**How to read this report:** part A says whether the PR can be merged. Part B explains each milestone (what was
built, how it works, how it was tested, what went wrong and how it was fixed). Part C lists every technology.
Part D lists the numbers with their scope. Part E is the to-do list for Sumit, including how to run, connect and
deploy everything. Part F lists what the planning chat should check.

---

## Part A. Is the pull request ready to merge?

**Yes for the code, with two checks that only Sumit can turn green.** All code-related checks pass on the
current head. The two red checks are repository settings and owner decisions, not code failures.

### A1. Checks on the PR head (`b18a556`, plus the report commit that adds this file)

| Check | Result | Run / evidence |
|---|---|---|
| CI · Java (Maven verify, Oracle Database Free, official E2B(R3) schemas) | ✅ | run 40062510903 |
| CI · Java on SQL Server (portability profile) | ✅ | run 40062510903 |
| CI · E2B(R3) export against the official schemas | ✅ | run 40062510903 |
| CI · Python (data), Python (ml) | ✅ | run 40062510903 |
| CI · Frontend (typecheck, lint, Vitest, build) | ✅ | run 40062510903 |
| CI · Full stack (docker compose + Playwright) | ✅ | run 40062510903 |
| CI · Azure Terraform (fmt, validate, test, Checkov) | ✅ | run 40062510903 |
| CI · Secret scan (gitleaks, full history) | ✅ | run 40062510903 |
| CodeQL analysis (Java, Python, TypeScript) | ✅ | run 40062510896 |
| Kubernetes e2e (kind) | ✅ | run 40062510902 (and 40059501640 on `1c14a94`) |
| Jenkins pipeline (headless) | ✅ on `1c14a94` (run 40059501594); re-running on the newest head | `analysis/m10/jenkins-run.log` |
| Azure plan | skipped (by design until owner step 9 sets the Azure variables) | — |
| **Dependency review** | ❌ "Dependency review is not supported on this repository" | owner step 3: switch on Dependency graph |
| **CodeQL (code-scanning alerts)** | ❌ 2 open alerts, both judged false positives (A2) | owner step 13: dismiss or ask for a change |

GitHub reports the PR as mergeable with no conflicts (`mergeable_state: clean`).

### A2. The two CodeQL alerts (open since 22 Oct, replied to on the PR, left for Sumit to decide)

1. **"Clear-text logging of sensitive information"**, `data/notebooks/databricks/02_faers_silver_gold.py:56`.
   The line prints the evidence JSON that owner step 4 asks you to copy. Every value is an aggregate over public
   FAERS data (counts, a date range, `sex_unknown_rate`, `age_unknown_rate`, the PT vocabulary size). There is no
   record about a person and no secret. CodeQL reacts to field names such as `sex` and `age`.
2. **"Disabled Spring CSRF protection"**, `services/common/.../SecurityAutoConfiguration.java:78`. All APIs are
   stateless: sign-in returns a JWT, the UI keeps it in sessionStorage and sends it in the `Authorization`
   header, and no service sets a cookie (checked with a code search). CSRF attacks need a credential the browser
   sends automatically (a cookie), so CSRF tokens would protect nothing here and would break API clients. This is
   Spring Security's documented setting for bearer-token APIs. If a cookie session is ever added, CSRF must
   return.

Recommendation: dismiss both (step 13 in Part E). If the planning chat prefers code changes instead, say so and
they will be made.

### A3. What "complete" means here and what it does not

- **Complete:** all twelve milestones' code, tests, docs and CI; every software path runs end to end in
  automated tests; Kubernetes and Jenkins runs are green; Azure is written and validated.
- **Not complete, and cannot be from the build container:** model results on real data (FAERS, VAERS, RxNav,
  Hugging Face are blocked by the session's network policy). These claims stay "pending" and are never filled
  with invented numbers. The Azure deployment was never applied (it needs Sumit's subscription and approval).

---

## Part B. What was built, milestone by milestone

### Overall architecture

```
 PHARMACY (synthetic patients)                     DRUG-SAFETY PLATFORM
 pharmacy-service :8081 ── REST report ──▶ intake-gateway :8082 ◀── SOAP/WSDL (partners, WS-Security)
   FEFO dispensing, one-click report         REST + SOAP + E2B(R3) import
   safety evidence panel ◀── signals ──┐            │ Kafka (outbox): report.received
 signal-service :8084                  │            ▼
   PRR/ROR/IC/EBGM + SafetyFormer      │   case-service :8083 (Oracle, JPA)
   export snapshot                     │     dedup → MedDRA coder → triage (ONNX in Java)
                                       │     → reviewer decisions → narrative → E2B(R3) export (XSD-checked)
                                       │            │ Kafka: case.ready
                                       │            ▼
                                       │   submission-gateway :8085 ── JMS (Artemis) ──▶ mock regulator
                                       │     records SENDING → QUEUED → ACK / NACK (back to review)
 React UI :8080 (nginx routes /api/... to the right service)
 OFFLINE: FAERS/VAERS → data/ (PySpark, Delta) → ml/ (PyTorch, LightGBM) → ONNX bundle + signal export → services
```

Kafka topics: `report.received → report.deduped → report.coded → case.triaged → case.ready → submission.status`,
each with a `.dlq` twin (12 topics).

Rules applied everywhere (from `CLAUDE.md`): every new file starts with a comment; every service keeps working
when a dependency is down (cases go to "needs manual coding"); outbox pattern for every event, idempotent
consumers, dead-letter topics; append-only case versions with audit rows; AI outputs stored as suggestions with
model name, version and score next to the reviewer's final choice; the LLM sees only structured, coded facts and
never computes; E2B(R3) files must pass the official schemas before they can be queued; banners on every page;
JaCoCo ≥ 70% line coverage per service; secrets only from environment variables.

### M0. Bootstrap, context and rules ✅ (`99b7ea9`, `6ce5906`)

- **What:** removed the old JSP/MySQL app in one commit and created the new layout: `services/` (Maven parent,
  `common`, five Spring Boot services), `data/` and `ml/` (Python packages), `frontend/` (React), `docs/`,
  `analysis/`, `ops/`, `infra/`, plus `CLAUDE.md` (rules), `OWNER_STEPS.md`, `.env.example`, MIT licence.
- **CI from day one:** `ci.yml` (Maven verify, pytest + ruff, frontend checks, gitleaks over the whole history),
  `codeql.yml` (Java, Python, TypeScript), `dependency-review.yml`.
- **Checked:** Maven green (6 modules), pytest green, frontend green, gitleaks clean.

### M1. Legacy audit: removed by the owner (Amendment 1). Claim C13 dropped.

### M2. Data platform: code ✅, real run pending (`92dc908`)

- **FAERS ingestion** (`data/pharmavigil_data/faers/`): downloads one quarter at a time, writes Parquet per table
  with a manifest (zip SHA-256, row counts, repaired and dropped rows), deletes the raw zip. Handles both the
  FAERS and the legacy AERS layouts, rows broken by embedded newlines, trailing delimiters and the FDA's deletion
  lists.
- **Silver (Spark):** removes repeated report keys and deleted cases, keeps one report per case (highest case
  version, then FDA date, then report id), derives age band, sex, seriousness and outcomes, and returns counts at
  every step.
- **RxNorm** (`rxnorm.py`): RxNav client (exact, then approximate match, reduced to ingredients), SQLite cache,
  15 requests/s limit, stops after repeated errors and never caches failures.
- **Gold:** `report`, `report_drug`, `report_reaction` tables plus normalization coverage.
- **Reference sets** (`refsets.py`): OMOP and EU-ADR from OHDSI MethodEvaluation at a pinned commit. **This is
  the only real data so far:** OMOP 399 pairs (165 positive / 234 negative), EU-ADR 93 pairs (43 / 50)
  (`analysis/m2/refsets.json`).
- **VAERS** narratives joined to their coded PTs; data-quality report; CLI `pv-data`; Databricks notebooks 00–03
  for the full history; dataset cards.
- **Tests:** 43 pytest tests, including an end-to-end Spark run over hand-made FAERS and AERS fixtures that checks
  every de-duplication count, and mocked RxNav tests.

### M3. Signal baselines: code ✅, real AUCs pending (`562425e`)

- **Statistics** (`ml/pharmavigil_ml/signals/`): PRR and ROR with 95% CIs (Haldane correction for zero cells), IC
  with Norén shrinkage, and MGPS/EBGM (two-gamma prior fitted by maximum likelihood; EBGM, EB05, EB95).
- **Counting** with DuckDB over the gold tables, including a "received before" date cutoff for the time-travel
  test.
- **Evaluation:** drug-name matching to the reference sets, AUC with bootstrap 95% CI, unscorable pairs counted.
- ADR-005 fixed the outcome → PT lists and the SafetyFormer score **before** any results existed (no tuning on the
  test set).
- **Tests:** hand-computed PRR/ROR/IC values, EBGM shrinkage, and an end-to-end run on synthetic reports with
  planted associations.

### M4. SafetyFormer: code ✅, real training pending (`b75616b`)

- **Model:** a set Transformer (no positions, because a report is a set of drugs and reactions) trained
  self-supervised: hide a reaction and predict it from the drugs and the rest of the report.
- **Pipeline:** corpus to CSR arrays, vocabulary from the training window only, AdamW with warm-up and cosine
  decay, held-out recall@10, MLflow logging that never blocks training, hashed checkpoints.
- **Time-travel test** (ADR-006): yearly snapshots, "would it have flagged a later label change, and how early?";
  plus a **leakage test** (shuffled vs time-ordered split of equal size).
- **Measured:** about 750 reports/s on 4 CPU cores with synthetic data. The full run needs a GPU, so a Kaggle
  notebook is ready (owner step 5).

### M5. MedDRA coder, triage, duplicates, ONNX: code ✅, real metrics pending (`2facc9c`)

- **Seriousness triage:** LightGBM with a time-based split; PR-AUC, ROC AUC, Brier score, calibration, TreeSHAP.
  A leakage guard never reads outcome fields and drops outcome-revealing terms such as "death".
- **Duplicate detection:** pair features over case versions, hard and random negatives, case-level split. The
  model card states that the label is a proxy.
- **MedDRA coder:** BiomedBERT encoder with a classification head for frequent PTs, plus retrieval over PT names
  for the long tail, merged into one ranking (ADR-007); micro/macro F1 and top-5 recall.
- **ONNX export** for every model, with parity checks and JSON test vectors used by the Java tests.
- **Problem found:** the first SafetyFormer export only worked at the traced input shape. A second-shape parity
  test now guards every Transformer export.
- **Tests:** `ml/` 49 pytest tests in total.

### M6. Java platform core ✅ (`7f4b640`, `f2070e7`)

- **common:** HS256 JWT with a `roles` claim, a stateless security setup with a strict CSP header, RFC 7807 error
  responses that never leak internals (unexpected errors return only a reference id), shared Testcontainers
  databases.
- **pharmacy-service:** drugs with expiring lots; **FEFO dispensing** that row-locks the drug's in-date lots and
  never touches expired ones (test: 50 parallel dispenses against 30 units, exactly 30 succeed); prescriptions for
  Rx-only drugs; BCrypt logins without an HTTP session; patients see only their own history; Synthea-format
  synthetic patient import; one-click side-effect reports.
- **case-service:** case head (status machine, seriousness, 15/90-day deadline, optimistic locking) with
  **append-only, immutable versions**, audit events, review decisions that keep the model suggestion beside the
  final value (the changed ones form the feedback set), and a reviewer queue (serious → unknown → non-serious,
  then deadline).
- **Databases:** Flyway migrations per vendor; Hibernate validates against Oracle Database Free 23 and SQL Server
  2022 (ADR-003).

### M7. E2B(R3) and SOAP ✅ (`d3641a0` … `59302f8`)

- **E2B(R3)** (`common/e2b`): writer, hardened reader and schema validator (VALID / INVALID / SCHEMA_UNAVAILABLE).
  The official schema set is downloaded at build time from EMA's published copy (`scripts/fetch-e2b-xsd.sh`, 50
  files); it is never committed. CI validates exports against it, and a deliberately broken message must fail,
  so the check is not empty.
- **intake-gateway:** contract-first SOAP (Spring-WS, WSDL) with `SubmitCase` and `GetCaseStatus`, WS-Security
  UsernameToken + Timestamp, explained SOAP faults; REST intake for pharmacy reports and E2B uploads. Each accepted
  report gets a receipt and exactly one outbox event in one transaction; identical resends return the original
  receipt; changed messages become follow-ups.
- **case-service:** turns `report.received` into a new case or a follow-up version; exports approved cases to
  E2B(R3), queueable only when VALID.
- **Problems fixed:** Spring Boot's web-services auto-configuration backed off when `@EnableWs` was declared;
  Oracle 23 maps `boolean` to its native BOOLEAN type.

### M8. Kafka pipeline, models in Java, JMS gateway, narrative ✅ (`85a53f1` … `c4b372a`)

- **Kafka pipeline with outbox** (ADR-001): each publisher writes its event into its own `outbox_event` table in
  the same transaction as the change; a shared relay publishes in order (at-least-once). Each consumer records
  `(event id, consumer)` in `processed_event` in the same transaction as its work, so repeats are skipped. Failing
  messages are retried 3 times, then moved to `<topic>.dlq`; poison messages (bad JSON, unknown case) go straight
  to the DLQ. **Test:** replaying every event of a case changes nothing.
- **Models inside Java** (ADR-008): ONNX Runtime in case-service runs the duplicate check, the coder (with a Java
  WordPiece tokenizer) and triage. Each model loads on its own; a missing one sends cases to manual coding.
  Java/Python parity: identical token ids and features, scores within 1e-5 (coder 1e-4). The committed bundle is a
  tiny synthetic fixture whose scores mean nothing.
- **Reviewer API:** confirm or correct each code, seriousness and duplicate flag; write and approve the narrative;
  approve; submit (only if the export passes the official schemas).
- **Narrative:** drafted with the Anthropic Java SDK from coded facts only, when `ANTHROPIC_API_KEY` and
  `CLAUDE_MODEL` are set; otherwise, and on refusal, truncation or any error, from a fixed template. A reviewer
  always approves.
- **submission-gateway:** `case.ready` → records SENDING → sends the E2B(R3) XML over JMS (Artemis) → QUEUED. The
  mock regulator validates against the schemas and answers ACK or NACK; NACK sends the case back to review with
  the reason.
- **signal-service:** loads a signal export (top signals, trends, evaluation); returns empty, labelled answers
  when none is loaded (no invented warnings).
- **End-to-end test:** all five services in one JVM against Oracle, Kafka and Artemis (Testcontainers):
  pharmacy report → case → review → approve → E2B(R3) → ACK, plus the replay and dead-letter checks and a NACK
  path. Green in CI against the official schemas (run 39763594556).
- **Race found and fixed (STAR 1):** the regulator's answer could arrive before the gateway had recorded its own
  message. The message is now recorded before sending, and the row is locked while an answer is recorded.

### M9. Frontend and the full stack ✅ (`70044bf`)

- **UI** (React 19, TypeScript, Vite, Tailwind, TanStack Query, React Router): sign-in; **pharmacy counter**
  (synthetic patient + drug, FEFO dispense, safety-evidence panel with the causation disclaimer, one-click
  report); **review queue** (serious first, days-left clock); **case detail** (accept or correct each code,
  seriousness, duplicate flag, narrative, approve, submit, submission history); **signals page** (top signals,
  trend chart with a table view, evaluation). Banners on every page; model suggestions always show model name and
  version.
- **docker compose:** Oracle (one schema per service), Kafka, Artemis, five services (non-root images), UI on port
  8080; refuses to start without its secrets.
- **Tests:** 33 Vitest tests; Playwright smoke test (pharmacist dispenses and reports → reviewer finds the case)
  2/2, in CI on every push.

### M10. Operations ✅ (`10b6958`, `2b2647a`, `5ab1744`, `58a898b`, `1c14a94`, `b18a556`)

- **Metrics** (Micrometer → Prometheus): reports received, time per stage, intake→coded latency, model inference
  time, reviewer decisions, outbox backlog, Kafka lag, JMS queue depth, regulator answers, signal lookups.
- **Traces** (OpenTelemetry Java agent, off unless `PV_TRACING=true`): the outbox stores the writer's W3C
  traceparent and the relay restores it, so one trace runs from pharmacy through intake, Kafka and every
  case-service stage (182 spans, `analysis/m10/trace_example.json`).
- **Grafana:** 3 dashboards (pipeline health, AI quality, safety signals), 9 alert rules; 31 queries checked
  against the running stack, 0 failed.
- **k6 load test** (ramp to 40 reports/s plus a working reviewer). Three runs found and fixed two bottlenecks
  (STAR 2): the duplicate check loaded candidates one query at a time (now one query plus batch fetching), and the
  outbox relay waited for each acknowledgement (now pipelined per batch). **p95 intake→coded: 103 s → 21 s →
  7.5 s.** The load test also exposed a correctness bug (triage overwrote a case a reviewer had opened), fixed with
  a new test.
- **Kubernetes** (ADR-010): Helm library chart (probes, non-root, secrets by reference, init container that waits
  for dependencies), six service charts, Oracle and Artemis demo charts, umbrella chart; **Strimzi** runs Kafka
  with the 12 topics as code. The *Kubernetes e2e (kind)* workflow installs everything on a kind cluster and runs
  the Playwright test. **Problem fixed (STAR 3):** Oracle never opened on Kubernetes (ORA-01081 on restart, then an
  emptyDir volume hiding the image's pre-built database); fixed by removing that volume and adding init
  containers. Green since.
- **Jenkins:** `ops/jenkins/Jenkinsfile` (checkout → Java build and unit tests in parallel with frontend checks →
  integration tests → JaCoCo gate → six images → Helm lint → kind deploy → smoke test), a Jenkins LTS controller
  configured as code (JCasC + job-dsl), and a headless runner script. **Fixed in this session:** (1) Jenkins could
  not find the PR's merge commit (no branch points to it; a temporary branch now does); (2) images built with
  Docker's legacy builder lost the OpenTelemetry agent because it reads `.dockerignore` differently (reproduced
  locally with `DOCKER_BUILDKIT=0`; the Jenkins image now includes buildx); (3) the cluster was deleted before
  diagnostics ran (teardown moved to `post { cleanup }`). Green run: 103 integration tests, 0 failures, coverage
  gate met for all six services, 11 pods Running, smoke test 2/2.

### M11. Azure infrastructure, code only ✅ validated, never applied (`871cc45`)

- **Terraform** (`infra/azure`): resource group; AKS Free tier (one B4ms node, Entra ID sign-in only, Azure RBAC,
  network policy, Azure Policy, Key Vault CSI driver); ACR Basic; Key Vault with RBAC (Terraform never handles
  secret values, so they never land in the state file); Log Analytics capped at 1 GB/day; a **$20 monthly budget**
  with three e-mail alerts.
- **No stored cloud credentials:** GitHub signs in with OIDC through managed identities with federated credentials
  (plan: read-only on PRs; apply and deploy: only in the protected `azure` environment that needs Sumit's approval).
- **Workflows:** *Azure plan* (PRs, read-only), *Azure deploy* (manual, approval), *Azure destroy* (manual, typed
  confirmation). The app deploy reuses the same install script as the kind test.
- **Checked:** fmt, validate, `terraform test` 2/2 (mocked provider), Checkov 16 passed / 0 failed / 20 skipped,
  each skip with its reason.

### M12. Docs, evidence, report ✅ (`c7b64fb`, `86366ce`, this commit)

README as a design document (problem, architecture diagrams, results with scope, one-command start, limits, ethics,
AI-assistance section); ADRs 1–10; `RESUME_EVIDENCE.md` (each claim with code, evidence, scope, status);
`INTERVIEW_PREP.md` (one-page explanation, 25 Q&As, 3 STAR stories); `PROJECT_REPORT.md`; this report.

---

## Part C. Technologies, tools and frameworks

| Area | Technology (version) | Used for |
|---|---|---|
| Language/runtime | Java 21 (OpenJDK 21.0.12), Maven 3.9.11 | five services + `common` |
| Framework | Spring Boot 4.1.1 (Spring Framework 7.0.9), Spring Security 7, Spring Web Services 5.0.2 | REST, SOAP, security |
| SOAP security | Apache WSS4J 4.0.1, WSDL4J 1.6.3, JAXB plugin 4.0.16 | WS-Security UsernameToken + Timestamp, WSDL |
| Persistence | Hibernate ORM 7.4.5, Flyway 12.4.0, ojdbc11 23.26.3, SQL Server JDBC 13.4.0 | JPA, migrations per vendor |
| Databases | Oracle Database Free 23 (`gvenzl/oracle-free:23-slim-faststart`), SQL Server 2022 | primary + portability |
| Messaging | Spring for Apache Kafka 4.1.1 (client 4.2.1), Kafka 4.3.2 (compose) / 4.2.0 (Strimzi) | internal pipeline |
| Messaging | ActiveMQ Artemis (client 2.53.0, broker 2.44.0) | JMS hand-over to the mock regulator |
| AI in Java | ONNX Runtime 1.31.0, Anthropic Java SDK 3.1.0 | models in case-service; narrative drafts |
| Java tests | JUnit 6.0.3, Mockito 5.23.0, AssertJ 3.27.7, Testcontainers 2.0.5, JaCoCo 0.8.15 | tests, coverage gate |
| Data | Python ≥ 3.12, PySpark 4.2.0, Delta Lake (delta-spark 4.4.1, deltalake), PyArrow, pandas, DuckDB | FAERS/VAERS ETL, counts |
| ML | PyTorch, LightGBM, scikit-learn, Hugging Face transformers/tokenizers, MLflow, ONNX + onnxruntime, onnxmltools, skl2onnx, SciPy, NumPy | SafetyFormer, triage, duplicates, coder, export |
| Python tooling | pytest, pytest-cov, ruff, responses, uv | tests and lint |
| Notebooks | Databricks Free Edition, Kaggle (GPU T4) | full-history ETL, GPU training |
| Frontend | React 19.3.1, TypeScript 6.0.3, Vite 8.3.5, Tailwind CSS 4.3.3, TanStack Query 5.104.5, React Router 8.5.0 | UI |
| Frontend tests | Vitest 5.0.5, Testing Library 16.3.3, ESLint 10.13.0, Playwright 1.64.1 | unit and browser tests |
| Containers | Docker, docker compose, `eclipse-temurin:21-jre-noble`, `nginx:1.29-alpine` | local stack, images |
| Observability | Micrometer 1.17.1, OpenTelemetry agent 2.32.0 / API 1.62.0, Prometheus v3.15.0, Grafana 13.2.3, Jaeger 2.22.0 | metrics, traces, dashboards |
| Load testing | k6 2.3.0 | throughput and latency |
| Kubernetes | kind v0.33.0, Helm v4.3.0, kubectl v1.37.1, Strimzi 0.51.0, busybox 1.37.0 | charts, local cluster |
| CI/CD | GitHub Actions (8 workflows), Jenkins 2.580.1 LTS (JCasC, job-dsl) | CI, pipeline as code |
| Security scanning | gitleaks, CodeQL, Checkov 3.3.26, Dependency review | secrets, code, IaC |
| Cloud (code only) | Terraform 1.16.5, azurerm 5.9.0; AKS, ACR, Key Vault, Log Analytics, Budget, managed identities + GitHub OIDC | Azure deployment |
| Standards | ICH E2B(R3) (HL7 v3 ICSR), MedDRA PT strings from public files, RxNorm, W3C trace context, RFC 7807 | domain and interop |

Version rule: every pinned version was at least two weeks old when chosen; exceptions are written in
`docs/ARCHITECTURE.md` (for example TypeScript 6.0.3 because typescript-eslint does not support 7.x yet).

---

## Part D. Numbers, each with its scope

| What | Result | Scope / source |
|---|---|---|
| Java tests | 106, 0 failures, 0 skipped (common 22, pharmacy 25, intake 11, case 35, signal 7, submission 3, e2e 3) | CI, official E2B(R3) schemas present |
| Line coverage | common 86.5%, pharmacy 94.8%, intake 87.1%, case 92.2%, signal 91.2%, submission 81.9–83.8% | gate ≥ 70%; local run and Jenkins run |
| Python tests | data 43, ml 49 | CI |
| Frontend tests | 33 Vitest + 2 Playwright | CI, compose stack and kind |
| Load (after fixes) | 2,400 reports/min accepted at peak, 2,303 cases coded/min, intake→coded p50 0.94 s / p95 7.5 s, 0 failed requests | local compose stack, 4 vCPU / 15.7 GiB, synthetic reports, fixture models (`analysis/m10/`) |
| Load (baseline) | p95 intake→coded 103 s, 628 coded/min | same scope |
| Dashboards | 31 queries checked, 0 failed, 19 with data | local stack |
| Tracing | 1 trace, 182 spans, 3 services | local stack |
| Jenkins run | 103 integration tests, 0 failures (3 schema tests skipped there), 11 pods Running, smoke 2/2, 13.5 min | GitHub runner, run 40059501594 |
| kind run | whole stack + smoke 2/2, about 9 min | GitHub runner, run 40059501640 |
| Terraform | 2/2 tests; Checkov 16 / 0 / 20 skipped with reasons | CI and local |
| Reference sets | OMOP 399 pairs (165 / 234), EU-ADR 93 (43 / 50) | real, OHDSI pinned commit |
| SafetyFormer speed | about 750 reports/s | 4 CPU cores, synthetic |
| **Model accuracy (AUC, F1, PR-AUC, lead time)** | **pending** | needs FAERS/VAERS (owner steps 4–7) |

---

## Part E. Everything Sumit has to do (in order)

Each item says where the detailed click-by-click steps are. Never send a secret or a screenshot of one to anyone.

### E1. Review and merge

1. Send this report to the planning chat and wait for its review.
2. **Step 3 (Dependency graph):** open https://github.com/sumitparmar19/PharmaVigil/settings/security_analysis →
   **Dependency graph** → **Enable**. Then on the PR: **Checks** → **Dependency review** → **Re-run jobs**.
3. **Step 13 (CodeQL alerts):** if the planning chat agrees with A2, open the PR → **Files changed**, or
   **Security → Code scanning**, open each of the two alerts → **Dismiss alert** → reason **False positive**
   (logging alert) and **Won't fix** (CSRF alert, comment: "stateless bearer-token API, no cookies").
4. When the checks are green: on the PR click **Merge pull request** → **Confirm merge**.
5. **Step 12:** create the `v1.0` release (`OWNER_STEPS.md` step 12).

### E2. Run everything on your own computer (no cloud needed)

Needs Docker (with compose), Java 21 and Maven.

```bash
git clone https://github.com/sumitparmar19/PharmaVigil && cd PharmaVigil
scripts/dev-up.sh                     # or: scripts/dev-up.sh --observability   (adds Prometheus, Grafana, Jaeger)
```

The script builds the jars, fetches the E2B(R3) schemas (without them nothing can be submitted), creates `.env`
with random local passwords (never printed, never committed), and starts the stack. Then:

- UI: http://localhost:8080. Sign in as `pharmacist`, `reviewer` or `admin`; the password is `PV_DEMO_PASSWORD`
  in `.env`.
- With `--observability`: Grafana http://127.0.0.1:3000, Prometheus http://127.0.0.1:9090, Jaeger
  http://127.0.0.1:16686.
- Stop: `docker compose down` (add `-v` to delete the database).

### E3. How everything is connected (environment variables)

All values come from environment variables (`.env` locally, a Kubernetes Secret on kind/AKS, Key Vault on Azure).
The full list with comments is `.env.example`.

| Connection | Variables | Notes |
|---|---|---|
| Services → Oracle | `ORACLE_URL`, `ORACLE_USER`, `ORACLE_PASSWORD`; compose: `PV_DB_PASSWORD` (users `pv_pharmacy`, `pv_intake`, `pv_case`, `pv_signal`, `pv_submission`) | one schema per service |
| Portability run → SQL Server | `MSSQL_URL`, `MSSQL_USER`, `MSSQL_PASSWORD` | `-Pmssql` profile |
| Services → Kafka | `KAFKA_BOOTSTRAP_SERVERS` | Kafka down = events wait in the outbox |
| case-service / submission-gateway → Artemis | `ARTEMIS_BROKER_URL`, `ARTEMIS_USER`, `ARTEMIS_PASSWORD` | JMS to the mock regulator |
| All services: sign-in tokens | `JWT_SECRET` (≥ 32 random characters, the same for all services) | HS256 |
| Demo logins | `PV_DEMO_PASSWORD` (≥ 12 characters) | pharmacist, reviewer, admin |
| pharmacy-service → intake-gateway | `INTAKE_GATEWAY_URL` | retries while intake is down |
| pharmacy-service → signal-service | `SIGNAL_SERVICE_URL` | dispensing never waits for it |
| SOAP partners → intake-gateway | `SOAP_PARTNER_USER`, `SOAP_PARTNER_PASSWORD` | empty = every SOAP call refused |
| case-service models | `PV_MODEL_DIR` (compose: `MODEL_BUNDLE_DIR`) | empty = manual coding |
| E2B(R3) schemas | `PV_E2B_XSD_DIR` (compose: `E2B_XSD_DIR`) | empty = nothing can be queued |
| signal-service data | `PV_SIGNALS_DIR` (compose: `SIGNALS_DIR`) | empty = no warnings shown |
| Narratives with Claude (optional) | `ANTHROPIC_API_KEY`, `CLAUDE_MODEL` | empty = template |
| Tracing | `PV_TRACING=true` | sends traces to Jaeger |
| Data runs | `PV_LAKE`, `PV_RAW`, `PV_CATALOG`, `FAERS_FIRST_QUARTER`, `RXNAV_BASE_URL`, `OPENFDA_API_KEY`, Spark/Torch settings | Databricks or local |

### E4. The APIs (all behind the UI's nginx on port 8080 at `/api/...`)

| Service (port) | Main endpoints |
|---|---|
| pharmacy-service (8081) | `POST /api/auth/login`; `GET /api/drugs`, `GET`/`POST /api/drugs/{id}/lots`; `GET /api/patients`, `GET`/`POST /api/patients/{id}/prescriptions`; `POST /api/dispenses`, `GET /api/patients/{id}/dispenses`; `POST`/`GET /api/reports` (side-effect reports) |
| intake-gateway (8082) | `POST /api/intake/reports`, `POST /api/intake/e2b` (XML), `GET /api/intake/receipts/{id}`; SOAP `SubmitCase` / `GetCaseStatus` (WSDL from the service) |
| case-service (8083) | `GET /api/cases/queue`, `GET /api/cases/{id}`; review: `/reactions/{index}/code`, `/seriousness`, `/duplicate`, `/narratives`, `/narratives/{id}/approve`, `/approve`, `/submit`, `GET /submissions`; `GET /api/cases/{id}/e2b` and `/e2b/validation` |
| signal-service (8084) | `GET /api/signals/warnings`, `/top`, `/trend`, `/meta`; `POST /api/signals/reload` |
| submission-gateway (8085) | `GET /api/submissions/case/{caseId}` (JMS in and out: `regulatory.outbound`, `regulatory.ack`) |
| every service | `/actuator/health`, `/actuator/prometheus` |

### E5. Produce the real-data results (fills the "pending" claims)

1. **Step 1:** in this Claude Code session's settings, allow the data hosts (list in `OWNER_STEPS.md` step 1),
   then send "Network saved, please re-test the hosts."
2. **Step 4:** full FAERS history on Databricks Free Edition (notebooks 00 → 03).
3. **Step 5:** train SafetyFormer on a Kaggle GPU (`safetyformer_full.ipynb`); send back the evidence zip and its
   sha256 line.
4. **Step 6:** train the MedDRA coder on Kaggle with VAERS 2019–2025 (`meddra_coder.ipynb`).
5. **Step 7:** label the 50 drug narratives yourself (never with an AI model).
6. **Step 8:** request the MedDRA academic licence.

After these, the next build session loads the trained bundle into `PV_MODEL_DIR`, the signal export into
`PV_SIGNALS_DIR`, and writes the measured numbers into `analysis/` and `RESUME_EVIDENCE.md`.

### E6. Deploy to Azure for one day (step 9, full guide in `docs/AZURE.md`)

1. Activate **Azure for Students**.
2. Run `infra/azure/bootstrap.sh` in **Cloud Shell**: it creates the state storage and the two GitHub OIDC
   identities.
3. In GitHub: create environment `azure` with yourself as required reviewer; add the variables that the bootstrap
   prints, and the secret `BUDGET_ALERT_EMAIL`.
4. **Actions → Azure deploy → infrastructure**, approve; then add `AZURE_DEPLOY_CLIENT_ID` from the outputs.
5. Type the **six app secrets** into Key Vault in the portal (`oracle-password`, `pv-db-password`, `jwt-secret`,
   `artemis-password`, `artemis-user`, `pv-demo-password`).
6. **Actions → Azure deploy → application**, approve.
7. Test via `kubectl port-forward` and Cloud Shell **Web preview** (pharmacist report → reviewer queue).
8. **Actions → Azure destroy** the same day; record the cost from Cost analysis.

Estimated cost: about US$0.25/hour while it runs (estimate from list prices, not a measured bill); the $20
budget sends alerts.

### E7. Optional

- **Step 10:** Claude-drafted narratives (put `ANTHROPIC_API_KEY` and `CLAUDE_MODEL` in `.env`).
- **Step 11:** import the three dashboards into Grafana Cloud.
- **Jenkins on your computer:** `ops/jenkins/run-headless.sh` (or `docker compose up -d --build` in `ops/jenkins`
  with `JENKINS_ADMIN_PASSWORD` set, then http://localhost:8090), see `docs/JENKINS.md`.
- **kind on your computer:** `docs/KUBERNETES.md` (scripts in `ops/kind/`).

---

## Part F. Deviations from the build instructions and what to check

The full table with reasons is in `analysis/PROGRESS.md` ("Deviations"). The main ones:

- no real FAERS/VAERS data (blocked hosts; the §6 fallback was used);
- M1 removed by the owner;
- TypeScript 6.0.3 instead of 7 (typescript-eslint support);
- Kafka 4.3.2 in compose and 4.2.0 under Strimzi (4.4.0 exists only as release candidates);
- E2B(R3) written with DOM and validated against EMA's copy of the official schemas instead of generated JAXB
  classes;
- signal-service serves nothing until a real export exists;
- a real JCasC Jenkins instead of Jenkinsfile Runner;
- Jenkins plugins unpinned (their versions are printed in every run log);
- managed identities instead of an Entra app registration for Azure OIDC;
- Key Vault secret values typed by the owner, never by Terraform.

Suggested checks for the planning chat:

1. Every number in Part D against its file or CI run.
2. `git grep` for passwords, keys and tokens (gitleaks already runs over the full history in CI).
3. The two CodeQL judgements in A2.
4. That no claim marked ✅ in `RESUME_EVIDENCE.md` depends on real-data results.
5. The banners and the causation sentence on every UI page and in the README.

AI assistance: the code and documents were written by Claude Code under Sumit's direction, as stated in the
README's AI-assistance section and in each commit's `Co-Authored-By` trailer.
