> **Note:** Amendment 1 in `docs/BUILD_INSTRUCTIONS.md` overrides this plan where they conflict.

# PharmaVigil: master plan v3 (healthcare and pharma only)
### An AI drug-safety (pharmacovigilance) platform: from the pharmacy counter to the safety signal and back

**For:** Sumit (review), then Claude Code (build)
**Repo:** `sumitparmar19/Pharmacy-Managment`, renamed **PharmaVigil** (GitHub redirects the old link). The 2024 JSP app stays in `legacy/`.
**Replaces:** v1 (RxGuard) and v2 (RxIntegrity). v2 was dropped because it leaned on finance (payments and fraud), and Sumit already has two finance projects (Nivesh-Path and the analytics pipeline). This project is **pure healthcare and pharma**.

---

## 1. Research: real, current problems in drug safety

| Problem | Evidence | Who feels it |
|---|---|---|
| **Drugs harm patients after approval** | US outpatient adverse drug events cause about **4 ED visits per 1,000 people a year**, and **27%** of them lead to a hospital stay; anticoagulants, diabetes drugs and antibiotics cause about half (CDC data, JAMA) | patients, pharmacies, hospitals |
| **The report flood** | FDA's adverse event database (FAERS) holds **32.8 million** reports (March 2026) and gets about **2.1 million a year** | FDA, every drug maker |
| **Case processing is slow, manual and expensive** | case volumes grow **10–15% a year**; **40–85%** of drug-safety budgets go to case processing; about **$90 per case** (Deloitte) | pharma safety teams, CROs |
| **Coding free text by hand** | every report's symptoms must be coded to MedDRA (**27,163 Preferred Terms**, 90,471 lowest-level terms). Even Oracle Argus, the market leader (about 60% share by Oracle's own estimate, running on an Oracle database), auto-codes only exact matches; AI narrative handling is "still evolving" | the same |
| **Signal detection is weak** | classic statistics (PRR/ROR) reach only **AUC 0.64–0.73** on the OMOP reference set of known drug–event pairs; learned embeddings reach 0.72–0.84, so there is room to improve | FDA, EMA, pharma signal teams |
| **A new regulation, right now** | from **October 1, 2026**, FDA accepts postmarketing safety reports **only in ICH E2B(R3) XML**; E2B(R2) ended September 30, 2026 | every drug maker and safety vendor |
| **Pharmacies rarely report** | pharmacists see side effects at the counter, but reporting to FDA MedWatch is voluntary and manual | community pharmacies |

**The gap PharmaVigil closes:**
1. A pharmacist reports a side effect in one click.
2. The report is coded, de-duplicated and triaged automatically by **Sumit's own models**, then exported as valid **E2B(R3) XML**.
3. Signals learned from **32 million FAERS reports** come back to the pharmacy counter as a warning the next time the drug is dispensed.

---

## 2. The data (public and free; no real patients)

| Dataset | What it gives | Size |
|---|---|---|
| **FDA FAERS** quarterly extracts (ASCII and XML) | drugs, reactions (MedDRA terms), outcomes, seriousness, report dates | **32.8M reports**, about 2.1M a year, 2004–2026 |
| **CDC/FDA VAERS** | free-text symptom **narratives** plus the **MedDRA terms human coders assigned**, ready-made labels for training a coder | 1990–2026; about 576 MB zipped (all years) |
| **OMOP and EU-ADR reference sets** | known true and false drug–event pairs for testing signal detection | OMOP: 165 positive / 234 negative; EU-ADR: 44 / 50 |
| **FDA Drug Safety-related Labeling Changes (SrLC)** | the date FDA added a safety warning to a drug label | since January 2016 |
| **NLM RxNorm, openFDA labels / NDC / recalls** | drug-name normalization (FAERS drug names are messy free text) and label facts | APIs |
| **ICH E2B(R3) schema and FDA regional guide** | the official XML schemas (XSD) for safety reports | public downloads |
| **Synthea** | synthetic patients and prescriptions for the pharmacy demo | generated |

**Rules:**
- **MedDRA:** free for academic use. Sumit requests an academic licence with his UB email before Phase 3; until then, only the terms already printed in public FAERS/VAERS files are used.
- **No causation claims:** a report or signal is not proof that a drug caused a reaction. Every screen and the README say so.
- **Not for regulatory or clinical use.** Synthetic patients only on the pharmacy side.

---

## 3. Sumit's own AI models (the core)

### Model A: signal detection, trained from scratch on FAERS
- **Baselines** (the methods regulators use):
  - PRR;
  - ROR;
  - Bayesian information component (IC/BCPNN);
  - MGPS/EBGM, the method FDA itself uses.
- **SafetyFormer** (Sumit's model, PyTorch, trained from scratch):
  - each FAERS report becomes a set of tokens: drugs (normalized to RxNorm ingredients), reactions (MedDRA PTs), age band, sex and outcome;
  - it is **self-supervised**: it learns by hiding one reaction and predicting it from the rest (masked prediction, the same idea as BERT and the attention work in his Deep Learning course);
  - the learned drug–reaction association becomes the signal score;
  - training on tens of millions of reports needs no hand labels.
- **Evaluation:**
  1. **AUC** on the OMOP and EU-ADR reference sets, against the baselines and published numbers.
  2. **The time-travel test** (the new part): train only on reports *before* a label change, then measure how many of the drug–event pairs FDA later added to labels (SrLC, 2016+) the model ranked highly, and **how many months earlier** each method would have flagged them.
  3. A shuffled vs time-ordered comparison, to show leakage honestly.

### Model B: MedDRA auto-coder (fine-tuned)
- Fine-tune a **pretrained biomedical text encoder** (PubMedBERT-family or ModernBERT, open weights, run on free GPUs) on **VAERS narratives → human-assigned MedDRA PTs**.
- **Method:** multi-label classification for frequent terms, plus embedding retrieval for rare ones.
- **Metrics:**
  - micro and macro F1;
  - top-5 recall (the coder suggests and the human picks);
  - errors broken down by System Organ Class.
- **Honest limit:** VAERS is about vaccines. So the model is also checked on a small, hand-coded set of drug narratives that Sumit writes, and the domain-shift gap is reported.
- **Training data comes from human coders, never from Claude.** Anthropic's commercial terms forbid using its services to train competing AI models.

### Model C: seriousness triage and duplicate detection
- A LightGBM model predicts **serious outcome** (hospitalization, death and so on) from report fields, so serious cases are processed first. FDA's deadline for serious unexpected reports is 15 days.
- Duplicate detection uses record-linkage features (same drugs, reactions, age, sex, dates) plus a trained match classifier, measured on FAERS's own case-version links.

**All models** are tracked in **MLflow**, have model cards, and are exported to **ONNX**, so the Java services run them with ONNX Runtime (no Python in the live path). Python and Java scores are parity-tested.

**Claude's role:** small and bounded. It drafts the E2B case narrative paragraph from the structured, already-coded case; a human edits and approves it. Numbers come from code only.

---

## 4. Every requested skill, and the real reason it's here

| Skill | Use in PharmaVigil | Why it's authentic |
|---|---|---|
| **Spring / Spring Boot** | 5 services (pharmacy, intake gateway, case processing, signal service, submission gateway) | pharma IT and safety vendors run Java services |
| **Kafka** | the case pipeline: `report.received → report.deduped → report.coded → case.triaged → case.ready`; outbox; dead-letter topic; replay | high-volume intake with replay and audit |
| **JMS** (ActiveMQ Artemis) | the **regulatory submission gateway**: E2B(R3) files go out on a JMS queue to a mock FDA gateway that returns ACK/NACK messages, the way real ESG submissions get acknowledgements | point-to-point delivery with guaranteed, acknowledged hand-off |
| **SOAP and XML** | **E2B(R3) XML** import and export, contract-first with the official **ICH XSDs**, JAXB and schema validation; a **SOAP partner-intake service** (Spring Web Services, WSDL) for licensing partners and CROs that exchange cases | safety-report exchange really is XML, and many partners still use SOAP |
| **JPA / Hibernate** | the case model (case → versions → drugs → reactions → narratives), optimistic locking for reviewers, an audit trail | standard enterprise persistence |
| **Oracle** (with SQL Server checked too) | main database: **Oracle Database Free (23ai)** in Docker locally and on Kubernetes; Flyway. CI also runs the repository tests on **SQL Server** to prove the JPA layer is portable | **Argus Safety, the industry-standard safety database, runs on Oracle** |
| **JUnit 5, Mockito** | everywhere, plus **Testcontainers** (Oracle, SQL Server, Kafka, Artemis) | real integration tests |
| **Jenkins** | `Jenkinsfile` pipeline (build → unit and integration tests → coverage gate → images → Helm deploy), Jenkins configured as code (JCasC), runs in Docker | common in regulated pharma IT |
| **Kubernetes** | Helm charts; **kind** for end-to-end tests; **AKS** on Azure; **Strimzi** operator for Kafka | production-style deployment |
| **Azure** | AKS, Azure Container Registry, Key Vault, Azure Monitor; Terraform; GitHub OIDC (no stored keys); Azure for Students credit | adds a second cloud (Nivesh-Path already shows AWS) |
| **Grafana** | Grafana Cloud with Prometheus and OpenTelemetry:<br>1. **pipeline health** (reports/min, Kafka lag, JMS queue depth, gateway ACK/NACK rate);<br>2. **AI quality** (coder acceptance rate by reviewers, drift in model inputs);<br>3. **safety signals** (top new drug–event signals and their trend) | how safety operations teams monitor intake and SLAs |

**Also used:**
- **Data:** PySpark and Delta on Databricks Free Edition; PyTorch, Hugging Face Transformers, LightGBM, MLflow and ONNX.
- **Apps:** React and TypeScript for the pharmacy app and the safety reviewer console.
- **Testing:** k6 load tests.

---

## 5. Architecture

```
 PHARMACY (rebuilt 2024 app)                       DRUG-SAFETY PLATFORM (pharma side)
 ┌───────────────────────────┐  REST: ADE report  ┌──────────────────────────────┐   SOAP/WSDL
 │ pharmacy-service (Boot)   │ ─────────────────▶ │ intake-gateway (Boot +       │◀── partner / CRO
 │ dispensing, FEFO lots,    │                    │ Spring-WS): REST, SOAP, E2B  │    case exchange
 │ one-click side-effect     │                    │ XML import (XSD-validated)   │
 │ report, safety warnings ◀─┼──── signals ───┐   └──────────────┬───────────────┘
 └───────────────────────────┘                │       Kafka: report.received
                                              │                  ▼
 ┌───────────────────────────┐                │   ┌──────────────────────────────┐
 │ signal-service (Boot)     │                │   │ case-service (Boot, JPA,     │
 │ serves SafetyFormer +     │────────────────┘   │ Oracle): dedup → MedDRA coder│
 │ baseline scores per       │                    │ (ONNX) → seriousness triage →│
 │ drug-event (batch-built)  │                    │ reviewer console → E2B(R3)   │
 └───────────────────────────┘                    └──────────────┬───────────────┘
                                                      JMS (Artemis): E2B(R3) out
                                                                 ▼
                                                  ┌──────────────────────────────┐
                                                  │ mock regulator gateway       │
                                                  │ (validates XML, ACK / NACK)  │
                                                  └──────────────────────────────┘
 OFFLINE: Databricks (FAERS 32.8M, VAERS) → PyTorch / HF / LightGBM (MLflow) → ONNX → services
 OPS: Jenkins → images → Helm → kind / AKS · Strimzi Kafka · Oracle Free · Prometheus + OTel → Grafana
```

**Correctness rules:**
- **Outbox pattern** and **idempotent consumers:** a resent report never creates a second case.
- **Case versions are append-only**, with a full audit trail (who changed which code, and when). This is required in real safety systems.
- **Every AI output is a suggestion:** codes, seriousness, duplicates and narratives are always confirmed by a reviewer. Reviewer corrections are saved as new training data.
- **E2B(R3) files must pass XSD validation before they can be queued.**
- **The pharmacy warning shows evidence, not verdicts:** the signal strength, report counts and the label text, plus "reports do not prove causation".

**The legacy story:** `docs/LEGACY_AUDIT.md` documents the 11 problems in the 2024 app (login bypass, expired drugs on sale, oversell trigger, plain-text passwords and the rest). Each one has a demonstration and a test in the new pharmacy-service.

---

## 6. Phases

One phase = one PR + report + tests. The ML phases come early so the resume gets real numbers soon.

| # | Phase | Main work | Done when |
|---|---|---|---|
| 0 | **Legacy audit** | tag `legacy-2024`; `legacy/` running in Docker (Tomcat + MySQL); `LEGACY_AUDIT.md`; repo rename | old app runs; all 11 problems demonstrated |
| 1 | **Data platform** | Databricks bronze/silver/gold for FAERS 2004–2026 and VAERS; drug names → RxNorm ingredients; case-version de-duplication; data-quality checks; dataset cards. **First step:** check that Free Edition can download the files; if not, download in Kaggle and upload Parquet | report counts match FDA's published totals (within the documented caveats); normalization coverage measured |
| 2 | **Signal research (Model A)** | PRR, ROR, IC, EBGM baselines; SafetyFormer training; OMOP and EU-ADR AUC; time-travel test against SrLC; MLflow; `EVALUATION.md` | real AUCs and lead-time numbers, with charts, compared with the baselines and published results |
| 3 | **Coder and triage (Models B and C)** | fine-tune the MedDRA coder; seriousness LightGBM; duplicate classifier; ONNX exports with parity tests; model cards | F1, top-5 recall and AUC reported; ONNX outputs match Python |
| 4 | **Core Java platform** | pharmacy-service rebuild (FEFO, dispensing, report capture, warnings); case-service (JPA/Hibernate, Oracle, Flyway, audit); E2B(R3) import/export with the ICH XSDs; SOAP partner intake; SQL Server portability run; JUnit/Mockito/Testcontainers | a real E2B(R3) sample round-trips and validates; every legacy problem has a passing test; a resent report never makes a second case |
| 5 | **Streaming and AI in production** | Kafka pipeline, outbox, DLQ; ONNX models in Java; reviewer console (accept/correct codes, approve the Claude narrative draft); JMS submission gateway with ACK/NACK; signals shown at the pharmacy counter | end-to-end: a pharmacist's report becomes a coded, triaged, validated E2B(R3) file that is acknowledged by the gateway; replaying events changes nothing |
| 6 | **Operations** | Jenkinsfile + JCasC; Helm; kind end-to-end in the pipeline; Prometheus + OpenTelemetry; **3 Grafana dashboards**; k6 load test (reports/min, p95 intake-to-coded time, consumer lag) | pipeline green from a clean clone; dashboards live; load-test numbers published |
| 7 | **Azure, docs, resume** | Terraform (AKS, ACR, Key Vault, Strimzi, Oracle Free on AKS); on-demand deploy, test and destroy; README design doc, ADRs, demo video | deployed, tested and destroyed with numbers recorded; README complete; resume bullets filled with measured numbers |

---

## 7. Cost plan
- **Local and CI:** Docker / kind (Oracle Free, Kafka, Artemis, SQL Server Developer): **$0**.
- **Data and training:** Databricks Free Edition, plus free Kaggle or Colab GPUs: **$0**.
- **Grafana Cloud:** free tier: **$0**.
- **Azure:** Azure for Students, **$100 credit for 12 months**. AKS runs only for demos and load tests, then is destroyed; budget alert at $20.
- **Claude API:** a few dollars (narrative drafts only).

---

## 8. Resume target (fill in only with measured numbers)

> **PharmaVigil: AI Drug-Safety (Pharmacovigilance) Platform** (Java, Spring Boot, Kafka, Oracle, Kubernetes, Azure, PyTorch) · GitHub
> - Trained a self-supervised Transformer from scratch on [N]M FDA adverse event (FAERS) reports to detect drug safety signals; it reached AUC [x] on the OMOP reference set vs [y] for the PRR/ROR methods regulators use, and flagged [k]% of later FDA label changes a median [m] months early.
> - Fine-tuned a biomedical language model to code free-text side-effect narratives to MedDRA ([F1] micro-F1, [r]% top-5 recall), cutting manual coding in the reviewer workflow, and served all models in Java through ONNX Runtime.
> - Built Spring Boot microservices that turn a pharmacist's one-click report into a de-duplicated, triaged case and a schema-valid ICH E2B(R3) XML submission: Kafka pipeline with outbox and replay, SOAP partner intake, JMS gateway with ACK/NACK, JPA/Hibernate on Oracle; shipped with Jenkins, Helm on Kubernetes (AKS), Terraform on Azure, and Grafana dashboards.

**Skills it adds:** Spring Boot, Spring Web Services (SOAP/WSDL), XML/XSD/JAXB, ICH E2B(R3), Kafka, JMS (ActiveMQ Artemis), JPA/Hibernate, Oracle, SQL Server, Flyway, JUnit 5, Mockito, Testcontainers, Jenkins, Kubernetes, Helm, Strimzi, AKS, Terraform on Azure, Prometheus, Grafana, PyTorch (Transformer from scratch), Hugging Face fine-tuning, LightGBM, MLflow, ONNX, pharmacovigilance (FAERS, MedDRA, RxNorm, signal detection).

---

## 9. Sumit's part
1. **Approve this plan.** Then the planning chat writes the full build instruction file for Claude Code.
2. **Sign up** (click-by-click steps come with the instruction file):
   - Azure for Students;
   - Databricks Free Edition;
   - Kaggle (for GPUs);
   - the MedDRA academic subscription (UB email).
3. **After each phase:** read the report, explain it back in your own words, then merge. Phases 2–3 double as interview prep for AI roles.

---

## Sources
- FAERS totals, 32.8M reports (March 2026): https://assets.cureus.com/uploads/original_article/pdf/476442/20260401-218735-7f5oxg.pdf
- Pharmacovigilance cost and volume (Deloitte): https://www.deloitte.com/content/dam/assets-shared/legacy/docs/insights/2022/us-lshc-transforming-pharmacovigilance.pdf
- FDA E2B(R3) deadline, Oct 1, 2026 (RAPS): https://www.raps.org/resource/fda-sets-october-deadline-for-new-safety-reporting-format.html
- Signal detection on OMOP/EU-ADR, PRR/ROR vs embeddings: https://arxiv.org/pdf/2008.03340
- MedDRA licensing and size: https://en.wikipedia.org/wiki/MedDRA
- Oracle Argus market position and Oracle backend: https://intuitionlabs.ai/articles/pharmacovigilance-software-systems-overview
- VAERS datasets: https://vaers.hhs.gov/DATA/DATASETS.HTML
- FDA SrLC database: https://www.fda.gov/drugs/drug-safety-and-availability/drug-safety-labeling-changes-slc-database
- ADE ED visits (CDC data, JAMA): https://jamanetwork.com/journals/jama/article-abstract/2585977
- Azure for Students: https://learn.microsoft.com/en-us/azure/education-hub/faq
- Databricks Free Edition limits: https://docs.databricks.com/aws/getting-started/free-edition-limitations
- Anthropic commercial terms: https://www.anthropic.com/legal/commercial-terms
