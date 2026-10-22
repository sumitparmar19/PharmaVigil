# M0 review: build environment and plan check

What this file is: the pre-build review Claude Code did before writing any PharmaVigil code, kept as
the record of what was checked and which plan corrections came out of it (Amendment 1, items 3–5).
Sections about the earlier application in this repository were removed, per Amendment 1.

## 1. The build container

| Area | Finding |
|---|---|
| Hardware | 4 CPUs, 15 GB RAM, about 30 GB free disk, **no GPU** |
| Toolchain | Java 21 (OpenJDK 21.0.12), Maven 3.9.11, Python 3.13, uv, Node 22.22.0, npm 10.9 |
| Docker | CLI and daemon binaries are installed; the daemon is not running at start and must be started (`dockerd &`). After that, Testcontainers works locally, so the §6 "Docker unavailable" fallback is not needed |
| Registries | Maven Central, PyPI, npm, Docker Hub (intermittent 429 rate limits), Microsoft Container Registry (SQL Server), releases.hashicorp.com, raw.githubusercontent.com: reachable |
| Missing tools | kind, kubectl, helm, terraform, checkov, gitleaks, k6 (installed per milestone or run as containers) |
| `ANTHROPIC_API_KEY` | not set (the narrative draft falls back to the deterministic template) |

## 2. Data and model hosts

First check (pre-build) and re-test (start of M0, after the owner changed the network setting):

| Host | Needed for | First check | M0 re-test |
|---|---|---|---|
| fis.fda.gov | FAERS quarterly ASCII files | blocked (403) | blocked |
| api.fda.gov, download.open.fda.gov | openFDA events, labels | blocked | blocked |
| vaers.hhs.gov | VAERS narratives | blocked | blocked |
| rxnav.nlm.nih.gov, download.nlm.nih.gov | RxNorm normalization | blocked | blocked |
| www.accessdata.fda.gov | Safety-related labelling changes (SrLC) | blocked | blocked |
| www.fda.gov | FDA E2B(R3) regional files | blocked | blocked |
| ich.org, database.ich.org, admin.ich.org | ICH E2B(R3) XSDs | blocked | blocked |
| huggingface.co | pretrained biomedical encoder | blocked | blocked |
| download.pytorch.org | CPU-only PyTorch wheels | blocked | blocked |
| quay.io | Strimzi images | blocked | blocked |
| get.helm.sh | Helm binary | blocked | blocked |
| github.com release downloads | kind, gitleaks, Synthea | blocked | blocked |
| raw.githubusercontent.com | OHDSI reference sets | reachable | reachable |

`analysis/PROGRESS.md` records any later re-test. While a host is blocked, the matching §6 fallback is
used and every dependent number is marked *pending full data*.

## 3. Plan corrections (adopted in Amendment 1)

- **Reference sets.** Both live in the OHDSI `MethodEvaluation` repository as R data files
  (`data/omopReferenceSet.rda`, `data/euadrReferenceSet.rda`), readable in Python with `rdata`.
  - OMOP: 399 pairs, 165 positive / 234 negative, 4 outcomes (acute liver failure, acute myocardial
    infarction, acute renal failure, upper GI bleeding).
  - EU-ADR: 93 pairs, **43 positive / 50 negative** (not 44/50), 10 outcomes.
  - Drugs are identified by OMOP concept IDs, not RxCUIs, so they are mapped to RxNorm by ingredient
    name. Outcomes are condition definitions, so each needs a hand-made MedDRA PT list (ADR-005).
- **FAERS formats.** 2004Q1–2012Q3 files are the older AERS layout (ISR and CASE numbers, no
  `caseversion`). "Keep the latest `caseversion` per `caseid`" applies from 2012Q4; the older files
  need their own mapping. Counts are reported before and after de-duplication; resume numbers use
  unique cases.
- **Time-travel test.** One SafetyFormer snapshot per year; each label change is scored with the latest
  snapshot trained only on reports received before it.
- **Duplicate detector.** Versions of the same case are follow-ups, not true duplicates (true
  duplicates are different case IDs describing one event). The model card calls the training signal a
  proxy.
- **Seriousness model.** Must not see outcome fields (OUTC) or outcome-revealing terms such as death or
  hospitalisation, with a test that proves it.
- **VAERS narratives.** Check during M2 whether the non-domestic files include `SYMPTOM_TEXT` rather
  than assuming they don't.

## 4. Versions

The instructions said "Spring Boot 3.x" and "React 18" but also "pin the latest stable". Amendment 1
chose the latest: Spring Boot 4.1.x, Spring-WS 5, Testcontainers 2, Kafka 4.4, React 19, latest Vite
and Tailwind, and the newest TypeScript the whole toolchain supports. The pinned versions are in
`docs/ARCHITECTURE.md`.

## 5. Process notes

- Branch: this session's branch (Amendment 1, item 2).
- Pushing `.github/workflows/` may need the GitHub App's workflow permission; Amendment 1, item 6
  covers the fallback.
