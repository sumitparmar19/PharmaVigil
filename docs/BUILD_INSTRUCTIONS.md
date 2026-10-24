> **Note:** Amendment 1 (end of this file) overrides the instructions above it where they conflict.

# PharmaVigil: full build instructions for Claude Code (one run, milestones M0–M12)

**From:** Sumit Parmar and the planning chat · **For:** Claude Code
**Repo:** `sumitparmar19/PharmaVigil` (formerly `Pharmacy-Managment`; Sumit renames it before this session)
**Companion file:** `PHARMAVIGIL_MASTER_PLAN_v3.md` (the why, the research and the sources). This file is the *how*. Copy both into the repo as `docs/PLAN.md` and `docs/BUILD_INSTRUCTIONS.md` in M0.

---

## 0. How to run this file (read first)

1. **Read the whole file before you write any code.** Then read the master plan and every file in the repo (the 2024 JSP app).
2. **One run, many milestones.** Work through **M0 → M12 in order, in a single session**, the way the OS-kernel project ran M0–M9:
   - put every milestone in your **task list** and tick each one off as it is done;
   - use **one branch**, `pharmavigil-v1`, with **one draft PR** to `main`, opened at the end of M0 and updated as you go;
   - make **at least one commit per milestone**, with a message starting `M<n>:`;
   - keep CI green on every push;
   - don't stop between milestones to ask permission. Sumit may not be watching. Keep going until M12 is done or you are truly blocked.
3. **Review first, then build.** If anything in this file is wrong about the real repo, a tool, a dataset or a version:
   - say so in `analysis/PROGRESS.md`;
   - fit the plan to reality;
   - continue.
   Never fake a result to match the plan.
4. **When something needs Sumit** (an account, a login, a secret, a GPU run, a GitHub setting):
   - don't wait for him;
   - write the step into `OWNER_STEPS.md` (click-by-click, one path);
   - use the fallback named in this file;
   - carry on with the next task.
5. **Measure everything.** Every number in the README, the reports or the resume evidence must come from a run whose output is committed under `analysis/`. Label every number with its data scope, for example **"subset: FAERS 2023Q1–2024Q4"** or **"full: FAERS 2004Q1–2026Q2"**.
6. **End with one report:** `analysis/PROJECT_REPORT.md` (format in §11). Also put a summary in the PR description.

---

## 1. Who Sumit is and how we work

- **Sumit:** MS CS, University at Buffalo (Systems track), graduating **December 2026**. He targets new-grad software, backend/full-stack, data and AI roles. He is on F-1 with OPT.
- **His other projects:**
  - **Nivesh-Path:** live finance platform on Node/React/FastAPI/Postgres/AWS.
  - **unix-kernel-subsystem:** Pintos kernel with VM, IPC and limits.
  - **A Databricks finance pipeline.**
  - This is his **healthcare/pharma** project. It must not drift into finance.
- **The working model:**
  - a planning chat writes instruction files;
  - **you (Claude Code) build**;
  - Sumit relays your reports to the planning chat, which verifies them by cloning the repo and checking the numbers.
- **Sumit's preferences, which also apply to `OWNER_STEPS.md`:**
  - **one path, never two options;**
  - **click-by-click steps;**
  - big chunks of work;
  - no dates.
- **Honesty rules carried over from the other projects:**
  - nothing goes on the resume that isn't true here and that Sumit can't explain (`INTERVIEW_PREP.md`);
  - no fake content (no placeholder people, numbers or links);
  - AI assistance is disclosed (keep the `Co-Authored-By` trailers; the README says post-2024 work was built with AI pair-programming and that the design review and decisions are Sumit's).
- **Security rules:**
  - never put a password, key, token or connection string in the repo, a log, a commit, a screenshot or a report; secrets only come from environment variables, and every new one goes in `.env.example`;
  - GitHub OIDC for clouds, never access keys.

---

## 2. The project in one page

**PharmaVigil** is an AI drug-safety (pharmacovigilance) platform built from Sumit's 2024 pharmacy app. It takes a side-effect report from the pharmacy counter all the way to a coded, de-duplicated, triaged, regulator-ready case, and it sends learned safety signals back to the counter.

**The problem** (full evidence and sources in the master plan):
- **Harm:** adverse drug events cause about 4 ED visits per 1,000 people a year in the US, and 27% of those lead to a hospital stay.
- **Volume:** FAERS holds **32.8M** reports and gets about **2.1M a year**.
- **Cost:** case processing takes 40–85% of drug-safety budgets (about $90 per case), and volumes grow 10–15% a year.
- **Manual coding:** reports are coded by hand to MedDRA (27,163 Preferred Terms).
- **Weak detection:** classic signal statistics score only AUC 0.64–0.73 on the OMOP reference set.
- **New rule:** since **Oct 1, 2026**, FDA accepts postmarketing ICSRs only as **ICH E2B(R3) XML**.

**What gets built:**
1. **Legacy rebuild:** the 2024 JSP/MySQL store becomes a Spring Boot pharmacy service. The 11 audited problems are fixed and each one is tested.
2. **The data platform:** FAERS + VAERS + reference sets, in PySpark/Delta (local Spark, and Databricks notebooks for the full scale).
3. **Sumit's models:**
   - **A, SafetyFormer:** a self-supervised Transformer trained from scratch on FAERS for signal detection, compared with PRR, ROR, IC and EBGM;
   - **B, a MedDRA auto-coder:** a fine-tuned biomedical encoder trained on VAERS narratives;
   - **C, seriousness triage** (LightGBM) and **duplicate detection.**
   All are exported to **ONNX** and served in Java.
4. **The Java safety platform:** Spring Boot microservices; Kafka pipeline with outbox, DLQ and replay; JPA/Hibernate on **Oracle**, with SQL Server portability tested; **E2B(R3) XML** import and export against the ICH XSDs; a **SOAP** partner-intake service; a **JMS** (ActiveMQ Artemis) submission gateway to a mock regulator that answers ACK/NACK; Claude drafts case narratives for human approval.
5. **Operations:** Jenkins pipeline, Helm on Kubernetes (kind locally, AKS later), Strimzi Kafka, Prometheus + OpenTelemetry, **3 Grafana dashboards**, k6 load tests, Terraform for Azure (validated only; Sumit applies it later).

**The non-negotiable honesty rules for this domain:**
- **No real patient data.** Public FAERS and VAERS data, and Synthea synthetic patients only.
- **No causation claims.** Every signal screen and the README say: "Spontaneous reports do not prove that a drug caused a reaction."
- **Not for clinical or regulatory use.** A banner on every UI page and a section in the README.
- **AI outputs are suggestions only.** Codes, seriousness, duplicates and narratives are always confirmed by a human reviewer in the UI.
- **Training labels never come from Claude.** Anthropic's commercial terms forbid using its services to train competing AI models. Labels come from the human coding in VAERS/FAERS and from Sumit's own hand-labelled set.
- **MedDRA:** never commit MedDRA dictionary files (licensed, though free for academic use). Use only the PT strings already present in the public FAERS/VAERS files. The PT → SOC hierarchy is added only after Sumit's academic licence arrives (owner step); until then, skip SOC breakdowns.

---

## 3. Target repository layout

```
PharmaVigil/
├── CLAUDE.md                     # project context + rules (you write it in M0, keep it updated)
├── README.md                     # design document (M12)
├── OWNER_STEPS.md                # everything only Sumit can do, click-by-click
├── LICENSE                       # MIT
├── .env.example
├── legacy/                       # the 2024 JSP app, unchanged except the DB-name fix to run it
│   ├── docker-compose.yml        # Tomcat + MySQL
│   └── ...
├── docs/
│   ├── PLAN.md  BUILD_INSTRUCTIONS.md  LEGACY_AUDIT.md  EVALUATION.md  ARCHITECTURE.md
│   ├── E2B_R3.md  AZURE.md  OBSERVABILITY.md  PERFORMANCE.md
│   └── adr/ADR-001-...md ...     # one ADR per big decision
├── data/                         # Python + PySpark data platform
│   ├── pyproject.toml
│   ├── pharmavigil_data/         # ingest (faers, vaers, refsets, srlc), normalize (rxnorm), dedup, quality
│   ├── notebooks/databricks/     # the same jobs as Databricks notebooks for full-scale runs
│   └── tests/
├── ml/                           # models
│   ├── signals/                  # PRR, ROR, IC, EBGM baselines + SafetyFormer
│   ├── coder/                    # MedDRA coder fine-tuning
│   ├── triage/                   # seriousness LightGBM, duplicate classifier
│   ├── export/                   # ONNX export + parity tests
│   ├── notebooks/kaggle/         # GPU notebooks for full-scale training
│   └── tests/
├── services/                     # Java 21, Maven multi-module, Spring Boot 3.x
│   ├── pom.xml
│   ├── common/                   # shared DTOs, events, outbox, security, E2B JAXB model
│   ├── pharmacy-service/
│   ├── intake-gateway/           # REST + SOAP (Spring-WS) + E2B import
│   ├── case-service/             # JPA/Hibernate on Oracle, pipeline consumers, reviewer API, E2B export
│   ├── signal-service/           # serves signal scores + pharmacy warnings
│   └── submission-gateway/       # JMS mock regulator: XSD-validate, ACK/NACK
├── frontend/                     # React + TS + Vite + Tailwind: pharmacy app + reviewer console
├── ops/
│   ├── jenkins/                  # Jenkinsfile, JCasC, docker-compose for Jenkins
│   ├── helm/                     # one chart per service + umbrella chart
│   ├── k8s/strimzi/  k8s/kind/
│   ├── grafana/                  # 3 dashboards (JSON) + alert rules
│   ├── prometheus/
│   └── k6/
├── infra/azure/                  # Terraform (AKS, ACR, Key Vault, budget) – validated, not applied
├── analysis/                     # PROGRESS.md, PROJECT_REPORT.md, RESUME_EVIDENCE.md, INTERVIEW_PREP.md,
│                                 # run outputs (metrics JSON, logs, charts) per milestone
└── .github/workflows/            # CI (tests, kind e2e, Jenkinsfile check, gitleaks, CodeQL)
```

---

## 4. Technology and versions

- **Pin the latest stable version available when you build.** Check Maven Central, PyPI and npm; never guess.
- **Record every version** in `docs/ARCHITECTURE.md`.

| Area | Choice |
|---|---|
| Java | **Java 21**, Maven multi-module |
| Framework | **Spring Boot 3.x** (latest stable), Spring Web, Spring Data JPA (**Hibernate**), Spring Security (JWT, roles), **Spring Web Services** (SOAP, contract-first), **Spring for Apache Kafka**, **Spring JMS** with **ActiveMQ Artemis**, Bean Validation, springdoc-openapi, Micrometer |
| XML | Jakarta XML Binding (JAXB) generated from the **ICH E2B(R3) XSDs**, plus schema validation |
| Database | **Oracle Database Free (23ai)**, container `gvenzl/oracle-free` (check the current official image name); **Flyway**; portability profile on **SQL Server 2022 Developer** |
| Messaging | Apache **Kafka** (KRaft), with Strimzi on Kubernetes; **Artemis** for JMS |
| ML serving in Java | **ONNX Runtime Java** |
| Tests | **JUnit 5, Mockito, AssertJ, Testcontainers** (Oracle, MSSQL, Kafka, Artemis), Spring Boot Test, **XMLUnit**; JaCoCo coverage |
| Data | Python 3.12, PySpark + Delta Lake (local mode), pandas, pyarrow, pytest |
| ML | PyTorch, Hugging Face Transformers, LightGBM, scikit-learn, **MLflow** (local file store), SHAP, onnx/onnxruntime |
| Frontend | React 18 + TypeScript (strict), Vite, Tailwind, TanStack Query, Vitest, Playwright |
| CI/CD | **GitHub Actions** (always-on CI) and a **Jenkinsfile** (the delivery pipeline; prove it runs, see M10) |
| Kubernetes | **Helm**, **kind**, Strimzi; AKS via Terraform |
| Observability | OpenTelemetry Java agent, Prometheus, **Grafana** dashboards (JSON in repo; Sumit imports them to Grafana Cloud) |
| Load | k6 |
| LLM | Anthropic Claude API (`ANTHROPIC_API_KEY`, model from `CLAUDE_MODEL`). **Optional:** without the key, the narrative draft falls back to a deterministic template, and nothing else depends on it |

---

## 5. Engineering rules (also copy them into `CLAUDE.md`)

1. **Every new file starts with a short comment** saying what it does and why it exists.
2. **Every service fails gracefully** when an outside dependency is down (Kafka, Artemis, Oracle, RxNav, openFDA, Claude, model files). The pipeline keeps cases moving with a "needs manual coding" status instead of crashing.
3. **Outbox pattern** for every event a service publishes: the database change and the event in one transaction, then a relay publishes it. **Idempotent consumers** use a processed-event table. A **dead-letter topic** catches poison messages.
4. **Case versions are append-only:** every change creates a new version with who, when and what (an audit table). There are no destructive updates on case data.
5. **AI outputs are always suggestions.** Store the model name and version, the score, and the reviewer's final choice. Reviewer corrections become a feedback table for future training.
6. **The LLM never does arithmetic,** and it never sees anything except the structured, already-coded case. Numbers come from code.
7. **E2B(R3) files must pass XSD validation** before they can be queued for submission. A failed validation is a visible, explained error.
8. **Domain banners:**
   - "Not for clinical or regulatory use";
   - "Reports don't prove causation";
   - "Synthetic patients only" (pharmacy side).
9. **Tests:**
   - each milestone adds tests;
   - each fixed legacy problem gets a test that fails on the old design and passes now;
   - Java ≥ 70% line coverage per service (JaCoCo gate);
   - Python data and ML code has pytest coverage of the transforms and metrics.
10. **Every metric is reproducible from a script** in the repo, and its JSON output is committed under `analysis/`.
11. **Data files never go into git** (only tiny test fixtures under `tests/fixtures/`). `.gitignore` covers data, models, `mlruns` and caches. Model artefacts are listed with their hashes in `analysis/models/MANIFEST.md`.

---

## 6. Environment realities and fallbacks (decide once, here)

| Risk | Fallback (use it without asking) |
|---|---|
| Your container can't download FAERS/VAERS (network allowlist) | Try **both** the FDA quarterly files and the openFDA API. If both are blocked, build everything against small **hand-made fixtures**, prepare the download + ETL as **Databricks/Kaggle notebooks**, and add "run notebook X" to `OWNER_STEPS.md`. Mark every number that depends on it as *pending full data* |
| No GPU in your container | Train **small configurations on a subset on CPU** to prove the code works end to end, and commit those numbers labelled **subset/CPU**. Put the full-scale runs in `ml/notebooks/kaggle/*.ipynb` with exact settings, and add them to `OWNER_STEPS.md` |
| Docker unavailable for Testcontainers locally | Rely on **GitHub Actions** (Docker is available there). Note it in PROGRESS |
| Oracle image too heavy or slow in CI | Keep Oracle; cache the image in CI. If a job still exceeds limits, run the Oracle suite in a separate workflow job and record its time |
| FDA SrLC (label changes) has no export | Try the site's search export first. If it's impossible, collect label-change dates for the drugs in the evaluation set from **openFDA label versions** (effective dates) and document the method in ADR-006; otherwise drop the time-travel test to a smaller scope. Never invent dates |
| OMOP / EU-ADR reference sets | Get them from OHDSI's public **MethodEvaluation** repository (cite the commit). Map the outcome definitions to MedDRA PTs as in the literature and document it in ADR-005 |
| ICH XSD licence or redistribution unclear | Don't commit the XSDs. Add a `scripts/fetch-e2b-xsd` step that downloads them at build time (CI caches them), and cite the source URL |
| Tag push returns 403 | Create tags locally and list them in `OWNER_STEPS.md` (Sumit creates them through GitHub Releases) |

---

## 7. Milestones (all in this one run)

Each milestone lists its **tasks** and its **done when**. Before you tick a milestone, update `analysis/PROGRESS.md`.

### M0: Bootstrap, context and rules
- Create the branch `pharmavigil-v1`. Tag the current `main` as `legacy-2024` (locally; see §6).
- Move the 2024 app into `legacy/` with history kept (`git mv`). Delete nothing that Sumit wrote. The committed `.war`, jar and IDE files stay inside `legacy/` only.
- Create the layout from §3: `CLAUDE.md` (the context from §§1–2, the rules from §5, the commands, the layout), `docs/PLAN.md`, `docs/BUILD_INSTRUCTIONS.md`, ADR template, `OWNER_STEPS.md`, `analysis/PROGRESS.md`, `.env.example`, `.gitignore`, MIT `LICENSE`.
- CI skeleton: GitHub Actions running `mvn -B verify` (empty modules), `pytest`, the frontend tests, **gitleaks** and **CodeQL** (Java, Python, JS).
- Open the **draft PR** "PharmaVigil v1 (M0–M12)".
- **Done when:** CI is green on the skeleton and the draft PR exists.

### M1: Legacy audit (the "before")
- `legacy/docker-compose.yml`: Tomcat + MySQL 8 loading `drugdatabase.sql`. Make the **single** fix needed to run it (the code uses `drugdatabase2`; the SQL creates `drugdatabase`), and document that fix.
- `docs/LEGACY_AUDIT.md`: the 11 problems below, each with a **reproduction** (a curl script or a Playwright test against the running legacy app) and a Playwright screenshot:
  1. login bypass (the session is set before the password check);
  2. no role checks on seller pages;
  3. plain-text passwords, and `root` with an empty password hard-coded in 12 pages;
  4. expired products can be sold;
  5. the stock trigger reads the newest order instead of `NEW`, and subtracts stock across all sellers;
  6. no server-side stock check (oversell);
  7. orders go to the first seller found;
  8. stored XSS through product names;
  9. exceptions printed to users;
  10. database-name mismatch, and `int` prices;
  11. no prescription/OTC concept and no pharmacist role.
- **Done when:** the legacy app runs in Docker and every problem is reproduced by a committed script, with its output saved in `analysis/legacy/`.

### M2: Data platform (FAERS, VAERS, reference sets)
- **Ingestion:**
  - FAERS quarterly ASCII (`DEMO`, `DRUG`, `REAC`, `OUTC`, `RPSR`, `THER`, `INDI`) for **2004Q1 to the latest quarter**;
  - VAERS (`VAERSDATA` with `SYMPTOM_TEXT`, `VAERSSYMPTOMS`, `VAERSVAX`), domestic files only (the non-domestic files have no narratives);
  - OMOP and EU-ADR reference sets;
  - SrLC (§6).
- **Bronze → silver → gold** in Delta (local Spark), with the same code as Databricks notebooks under `data/notebooks/databricks/`.
- **FAERS de-duplication:** keep the **latest `caseversion` per `caseid`**, as FDA recommends, then flag likely cross-case duplicates with simple rules (the learned classifier comes in M5).
- **Drug normalization:** FAERS `drugname`/`prod_ai` → **RxNorm ingredient (RxCUI)** using the NLM RxNav API, with a local cache table, rate limiting and retries. Report **normalization coverage** (the share of drug mentions mapped).
- **Data-quality checks** (pytest plus a quality job): row counts per quarter, null rates, PT vocabulary size, date ranges. Write `docs/DATASET_CARD_FAERS.md` and `DATASET_CARD_VAERS.md`.
- **Gold tables:**
  - `report(primaryid, caseid, fda_dt, age_band, sex, serious, outcomes[])`;
  - `report_drug(primaryid, rxcui, role)` (primary suspect / secondary suspect / concomitant);
  - `report_reaction(primaryid, pt)`;
  - `vaers_narrative(vaers_id, text, pts[])`.
- **Done when:**
  - counts are recorded with their scope;
  - your FAERS report counts are compared with FDA's published totals, with the differences explained;
  - normalization coverage is measured;
  - all tests pass.

### M3: Signal baselines
- Implement **PRR, ROR (with 95% CIs), IC (BCPNN) and EBGM** (gamma-Poisson shrinker, as in MGPS). Use **primary-suspect-only** and **all-roles** variants.
- An evaluation harness on **OMOP** (165+/234−) and **EU-ADR** (44+/50−), reporting **AUC** with bootstrap CIs. Compare with the published numbers (for example PRR/ROR 0.64–0.73 on OMOP) and explain any differences.
- **Done when:** `analysis/m3/baselines.json` and the `docs/EVALUATION.md` "Baselines" section exist, with charts.

### M4: SafetyFormer (Model A, trained from scratch)
- **Tokens per report:** ingredient RxCUIs (with a role embedding), MedDRA PTs, age band, sex, seriousness. Set encoding: no positional order, plus type embeddings.
- **Objective:** **masked reaction prediction** (hide one or more PTs and predict them from the rest), plus an optional masked-drug objective.
- **Architecture:** a small Transformer encoder (start with d=256, 4 layers, 8 heads; tune lightly).
- **Signal score for a drug–event pair:** the model's conditional probability of the PT given the drug alone, normalized against a background (document the exact formula in ADR-005).
- **Training:**
  - CPU **subset** run here (recent quarters);
  - full-scale Kaggle GPU notebook prepared;
  - **MLflow** tracking;
  - seeds fixed;
  - checkpoints hashed into the manifest.
- **Evaluation:**
  - same harness as M3;
  - the **time-travel test:** train on reports dated before each SrLC label-change date, then measure the rank of that drug–event pair and the **lead time** (months before the label change at which it entered the top-k), compared with the baselines;
  - a **shuffled vs time-ordered** comparison to show leakage.
- **Done when:** `analysis/m4/*.json` and the `EVALUATION.md` sections exist with real numbers (scope-labelled), plus a model card at `ml/signals/MODEL_CARD.md`.

### M5: MedDRA coder (Model B) and triage/duplicates (Model C)
- **Coder:**
  - fine-tune an open biomedical encoder (pick a PubMedBERT-family or ModernBERT checkpoint; record which and why) on VAERS `SYMPTOM_TEXT` → PTs;
  - multi-label head for the top-N PTs (N chosen from coverage, for example the PTs covering 95% of label occurrences), plus **embedding retrieval** over PT names for the long tail;
  - metrics: micro/macro F1, **top-5 recall**;
  - **domain-shift check:** a hand-coded drug-narrative test set. Write ≥ 50 short narratives with Sumit's labels: create the file `ml/coder/drug_narratives_template.csv` and an owner step for Sumit to fill in the labels. Report only after it's labelled; until then, report VAERS-only numbers.
- **Seriousness triage:** LightGBM on FAERS report features → serious (yes/no) and outcome class; PR-AUC and calibration; SHAP.
- **Duplicates:** pairwise features (shared drugs/PTs, age, sex, event date, reporter country); train on FAERS case-version pairs as positives, plus sampled negatives; precision/recall.
- **ONNX export** for all three models, plus a **parity test** (Python vs ONNX Runtime, max absolute difference recorded).
- **Done when:** the metrics JSONs, three model cards and passing parity tests exist.

### M6: Java platform core (pharmacy + case model)
- Maven multi-module with the parent POM, `common`, and the 5 services; Spring Security with JWT roles `PATIENT`, `PHARMACIST`, `SAFETY_REVIEWER` and `ADMIN`.
- **pharmacy-service** (JPA/Hibernate, Flyway, Oracle):
  - patients (synthetic), prescribers, drugs (NDC, RxCUI), **lots with expiry**, prescriptions;
  - **FEFO dispensing** with `SELECT … FOR UPDATE` and a `quantity >= 0` CHECK;
  - orders;
  - one-click **side-effect report** (free text plus chosen drug);
  - **safety warnings at dispensing** (from signal-service; shown as evidence, never as a verdict);
  - loads Synthea patients.
- **case-service:** the entities `Case`, `CaseVersion`, `CaseDrug`, `CaseReaction`, `Narrative`, `AuditEvent`, `ReviewDecision`, `ProcessedEvent`, `OutboxEvent`; optimistic locking for reviewers.
- **Portability:** the same repository tests run on **SQL Server** through a Maven profile and Testcontainers.
- **Legacy fixes:** one test per audited problem (§M1), for example "a failed login gives no session", "an expired lot is never dispensed", "50 parallel dispenses never oversell", "stored XSS is escaped".
- **Done when:** all tests pass on Oracle and SQL Server; the coverage gate passes.

### M7: E2B(R3) XML and SOAP
- Fetch the **ICH E2B(R3) XSDs** and the FDA regional elements (§6). Generate the JAXB classes into `common`.
- **Export:** an approved case → E2B(R3) ICSR XML, **XSD-validated**.
- **Import:** an E2B(R3) file → a new case, or a new version of an existing one.
- Round-trip tests on official sample messages (if the ICH package includes them) and on your own fixtures, compared with **XMLUnit**.
- **SOAP partner intake** (intake-gateway, **Spring-WS, contract-first**):
  - a WSDL with `SubmitCase` (an E2B payload inside the SOAP body) and `GetCaseStatus`;
  - WS-Security username token *or* mutual TLS (pick one, write the ADR);
  - SOAP faults for invalid XML.
- **REST intake** for the pharmacy's reports.
- Write `docs/E2B_R3.md`, explaining the fields that are mapped and the ones that aren't.
- **Done when:** export, import and SOAP contract tests pass, and a generated ICSR validates against the official XSD.

### M8: Kafka pipeline, models in Java, JMS gateway, Claude narrative
- **Topics:** `report.received → report.deduped → report.coded → case.triaged → case.ready`, plus `*.dlq`. Outbox relay in every publishing service; idempotent consumers.
- **case-service consumers:**
  1. duplicate check (ONNX);
  2. **MedDRA coder** (ONNX; suggestions with scores);
  3. seriousness triage (ONNX);
  4. the case enters the **reviewer queue**, ordered by seriousness and the 15-day clock.
- **Reviewer API:** accept or correct codes, confirm seriousness, mark duplicates, edit and approve the **narrative**. The narrative is drafted by Claude from the structured case only (or a template if no key); approval is required.
- **submission-gateway (JMS/Artemis):**
  - an approved case's E2B XML goes on the `regulatory.outbound` queue;
  - the mock regulator validates it against the XSD and returns **ACK** or **NACK** with a reason on `regulatory.ack`;
  - case-service records the submission status; NACKs reopen the case;
  - JMS **transacted sessions**, plus redelivery and a dead-letter queue.
- **signal-service:** loads the batch-computed signal scores (SafetyFormer and baselines) from the gold tables, and serves the pharmacy warnings with the evidence fields.
- **Done when** an end-to-end integration test (Testcontainers: Oracle, Kafka, Artemis) shows that a pharmacist's report becomes a coded, triaged, approved case and an ACKed E2B submission. Also:
  - replaying every event creates no duplicates;
  - a poison message lands in the DLQ;
  - an invalid XML file gets a NACK.

### M9: Frontend
- **Pharmacy app:** dispense (FEFO lot shown); the warning panel with evidence and the "not causation" note; the one-click side-effect report.
- **Reviewer console:** queue (seriousness and a due-date clock), case detail, code suggestions with scores, accept/correct, duplicate panel, narrative editor, submit, submission status (ACK/NACK).
- **Signals page:** top drug–event signals per method, with a trend and the reference-set evaluation summary.
- Banners from §5.8 on every page. API calls go through one typed client. At least 5 Vitest tests per page; a **Playwright** end-to-end smoke test against the docker-compose stack.
- **Done when:** the Playwright smoke test passes in CI.

### M10: Operations (Jenkins, Kubernetes, observability, load)
- **Jenkins:**
  - `ops/jenkins/Jenkinsfile` with the stages: checkout → build and unit tests → integration tests → coverage gate → images → Helm lint → deploy to kind → smoke test;
  - **JCasC** config and a `docker-compose.yml` that starts Jenkins with the pipeline preloaded;
  - **prove the Jenkinsfile runs:** execute it in CI with Jenkinsfile Runner (or an equivalent headless Jenkins; record what worked) and commit the log.
- **Helm:** a chart per service plus an umbrella chart; Strimzi manifests for Kafka; Artemis and Oracle Free charts for the demo. A **kind** end-to-end workflow in GitHub Actions (install everything, run the smoke test).
- **Observability:**
  - Micrometer/Prometheus metrics: reports/min, stage latencies, Kafka consumer lag, JMS queue depth, ACK/NACK counts, model inference time, reviewer acceptance rate;
  - the OpenTelemetry Java agent for traces across Kafka and JMS;
  - **3 Grafana dashboards** (pipeline health, AI quality, safety signals) plus alert rules in `ops/grafana/`; queries checked against a Prometheus fed by the running stack;
  - `docs/OBSERVABILITY.md`.
- **k6:** submit reports at increasing rates; record reports/min, p95 intake→coded time and max consumer lag. Write `docs/PERFORMANCE.md` with the numbers and the hardware they ran on.
- **Done when:**
  - the Jenkinsfile run log is committed;
  - kind e2e is green;
  - dashboard JSON is valid and shows data (screenshots from the local stack);
  - the k6 numbers are recorded.

### M11: Azure infrastructure (code only; Sumit applies it later)
- **Terraform** in `infra/azure/`:
  - resource group;
  - **AKS** (free control-plane tier, smallest node pool that fits);
  - **ACR**;
  - **Key Vault** (empty secrets; values are typed by Sumit);
  - Log Analytics;
  - a **budget alert at $20**;
  - a federated identity for GitHub OIDC;
  - `default` tags.
- Workflows: `azure-plan` (on PR, read-only) and `azure-deploy` / `azure-destroy` (manual, with an environment approval).
- `terraform fmt`, `validate` and **Checkov** must be clean (or each skip has a reason inline).
- `docs/AZURE.md`: one-path, click-by-click steps for Sumit (Azure for Students sign-up, the OIDC app registration, the GitHub environment, apply, test, **destroy**).
- **Done when:** validate and Checkov pass in CI; `AZURE.md` is complete.

### M12: Documentation, evidence, interview prep, report
- **README.md as a design document:**
  - the problem with sources;
  - an architecture diagram (Mermaid);
  - the pipeline sequence diagram;
  - data and models;
  - **results tables (scope-labelled)**;
  - how to run (one command for the local stack);
  - the before/after of the legacy app;
  - limits and ethics;
  - AI-assistance disclosure;
  - credits.
- **ADRs** (at least):
  1. outbox vs dual write;
  2. Kafka vs JMS roles;
  3. Oracle primary with SQL Server portability;
  4. E2B(R3) mapping scope;
  5. signal score definition and reference-set mapping;
  6. time-travel methodology;
  7. coder design (classification + retrieval);
  8. ONNX serving in Java;
  9. SOAP security choice;
  10. Kubernetes and Strimzi on kind/AKS.
- `analysis/RESUME_EVIDENCE.md`: the claims table from §9 with the code, test and result for each.
- `analysis/INTERVIEW_PREP.md`:
  - a one-page plain-English explanation;
  - **25 Q&As** grounded in this code (for example: PRR vs EBGM; why self-supervised; leakage; outbox; Kafka vs JMS; E2B(R3); SOAP vs REST; FEFO locking; ONNX in Java; why AI is suggestion-only);
  - **3 STAR stories** from real problems you hit.
- `analysis/PROJECT_REPORT.md` (format in §11). Mark the PR **ready for review**.
- **Done when:** every claim in §9 is ✅ with evidence, or clearly marked *pending full data / owner step*.

---

## 8. CI requirements (GitHub Actions, every push)
- **Java:** `mvn -B verify` (unit tests + Testcontainers integration tests on Oracle; the SQL Server profile as a separate job), JaCoCo gate.
- **Python:** `pytest` for `data/` and `ml/` (fixtures only, no downloads), plus a ruff/mypy-style lint.
- **Frontend:** typecheck, Vitest, build; Playwright end-to-end against docker-compose (one job).
- **Security:** gitleaks (full history), CodeQL, dependency review.
- **Kubernetes:** kind end-to-end (it can run on PRs and on `main` only, if it's slow; record the time).
- **Infrastructure:** `terraform fmt -check`, `validate`, Checkov.
- **Jenkinsfile:** the Jenkinsfile Runner job (M10).

---

## 9. Claims to make true (`RESUME_EVIDENCE.md`)

| # | Claim | Evidence required |
|---|---|---|
| C1 | Trained a self-supervised Transformer **from scratch** on **[N]M** FAERS reports | training config + MLflow run + data scope + model card |
| C2 | SafetyFormer AUC **[x]** on OMOP vs **[y]** for PRR/ROR (and EU-ADR) | `analysis/m4/*.json`, CIs |
| C3 | Flagged **[k]%** of later FDA label changes a median **[m]** months early | time-travel output + method ADR |
| C4 | Fine-tuned MedDRA coder: **[F1]** micro-F1, **[r]%** top-5 recall | metrics JSON + model card |
| C5 | Seriousness triage PR-AUC **[p]**; duplicate detection P/R **[a]/[b]** | metrics JSON |
| C6 | All models served in Java via ONNX Runtime (parity < **[ε]**) | parity tests |
| C7 | Pharmacist report → coded, triaged, schema-valid **E2B(R3)** submission, **ACKed** over **JMS** | end-to-end test + log |
| C8 | Kafka pipeline with outbox, idempotent consumers, DLQ and replay-safety | integration tests |
| C9 | **SOAP** partner intake (Spring-WS, WSDL) with faults and security | contract tests |
| C10 | **JPA/Hibernate on Oracle**, portable to **SQL Server** | both test profiles green |
| C11 | **Jenkins** pipeline, **Helm** on **Kubernetes** (kind e2e), **Terraform for Azure** (AKS, ACR, Key Vault) | Jenkins run log, kind workflow, validate/Checkov |
| C12 | **Grafana** dashboards for pipeline, AI quality and signals; k6 throughput **[r] reports/min**, p95 **[t] s** | dashboards + PERFORMANCE.md |
| C13 | Fixed all 11 problems in the 2024 JSP/MySQL app, each with a test | LEGACY_AUDIT.md + tests |

**A claim is either ✅ with evidence or listed as pending.** Never round up and never invent.

---

## 10. Owner steps (put them in `OWNER_STEPS.md`, click-by-click, one path each)

Expected entries, with exact clicks:
- create the tags `legacy-2024` and `v1.0` through GitHub Releases;
- create a Databricks Free Edition account and run the full-scale data notebooks;
- create a Kaggle account and run the GPU training notebooks;
- request a MedDRA academic licence with the UB email;
- label the 50 drug narratives in `drug_narratives_template.csv`;
- **Azure** (only when Sumit decides to deploy): Azure for Students, OIDC, environment approval, apply/test/destroy;
- import the Grafana dashboards into Grafana Cloud;
- add `ANTHROPIC_API_KEY` as a GitHub secret (optional).

**Each step ends with** "send this back to the planning chat: …" (the exact output or screenshot area to share). **Never ask for a screenshot of a secret.**

---

## 11. Final report format (`analysis/PROJECT_REPORT.md` + PR description)

1. **Summary:** what was built, in 10 lines.
2. **Milestone table:** M0–M12, status, key commit, evidence file.
3. **Numbers:** every metric with its **scope label** (subset/CPU vs full), plus the test counts per module and coverage.
4. **Claims table (§9):** ✅ / pending, with links.
5. **Deviations from this file,** and why.
6. **Problems hit and how they were solved** (these feed the STAR stories).
7. **Owner steps still open,** in order.
8. **Suggested next work.**

---

## 12. Before you finish, check
- [ ] CI green on the final commit; draft PR marked ready for review
- [ ] No secrets in git history (gitleaks clean); `.env.example` complete
- [ ] Every number in the README and reports has a committed output and a scope label
- [ ] No MedDRA dictionary files, no real patient data, and no Claude-generated training labels in the repo
- [ ] Banners present; README has the limits, ethics and AI-assistance sections
- [ ] `OWNER_STEPS.md`, `RESUME_EVIDENCE.md`, `INTERVIEW_PREP.md` and `PROJECT_REPORT.md` are complete

---

## Amendment 1: final decisions from Sumit and the planning chat

*These override the build instructions where they conflict.*

1. Build PharmaVigil as a completely new project. In M0, delete every old file (JSP, HTML, CSS, images, SQL.txt, drugdatabase.sql, the .war and jar, IDE files, Screenshots) in one commit: "M0: start PharmaVigil fresh". No legacy/ folder or tag. M1 (legacy audit) is cancelled: mark it "removed by owner" in PROGRESS.md. Don't mention the old app anywhere, and drop claim C13. The pharmacy-service is designed from scratch.
2. Branch: use this session's branch, with one draft PR to main.
3. Network: Sumit set Custom access (defaults plus fda.gov, *.fda.gov, vaers.hhs.gov, *.nlm.nih.gov, ich.org, *.ich.org, huggingface.co, *.huggingface.co, *.hf.co, download.pytorch.org, quay.io, *.quay.io, get.helm.sh, *.githubusercontent.com). Re-test every host and use real data.
   - In this container: process FAERS one quarter at a time (download, convert to Parquet, delete the raw files), aiming for 2019Q1 to the latest quarter, and record the exact scope.
   - Full 2004 onward goes in Databricks notebooks (owner step).
   - If a host is still blocked, use the §6 fallback.
4. Versions: Java 21, Spring Boot 4.1.x, Spring-WS 5, Testcontainers 2, Kafka 4.4, React 19, latest Vite and Tailwind. For TypeScript, use the newest version the whole toolchain supports. Record all versions in docs/ARCHITECTURE.md.
5. Plan corrections:
   - EU-ADR is 43 positive / 50 negative (cite the OHDSI commit).
   - Map reference-set drugs to RxNorm by name; keep the MedDRA term list for each outcome in ADR-005.
   - Map the old AERS format (2004 to 2012Q3) separately.
   - Report counts before and after de-duplication; resume numbers use unique cases.
   - Time-travel test: one SafetyFormer snapshot per year.
   - The duplicate model card says it's a proxy.
   - The seriousness model never sees outcome fields or death-like terms, with a test that proves it.
6. If pushing .github/workflows fails because of permissions, keep the files in ops/ci-pending/, add a click-by-click fix to OWNER_STEPS.md, and continue.
7. Save your review (without the old-app sections) as analysis/M0_REVIEW.md, and copy these decisions into docs/BUILD_INSTRUCTIONS.md as "Amendment 1".

Now start M0 and keep going through M12 without stopping. Finish with analysis/PROJECT_REPORT.md.
