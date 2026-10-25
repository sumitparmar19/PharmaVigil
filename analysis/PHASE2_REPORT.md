# Phase 2 report: real data, live demo, proof

<!-- The report the planning chat asked for at the end of PHARMAVIGIL_PHASE2_LIVE.md (P0–P9): what was done, every
     number with its scope and the committed file it comes from, what was not done and why, the bugs real data
     exposed, and the owner steps in the order to do them. Written for verification: each claim names its file. -->

Not for clinical or regulatory use. Spontaneous reports do not prove that a drug caused a reaction.

**Branch** `claude/determined-wozniak-8tf7pa`, pull request [sumitparmar19/PharmaVigil#2](https://github.com/sumitparmar19/PharmaVigil/pull/2),
41 commits after the v1.0 merge (`ce5f66e`), each prefixed with its step (`P0:` … `P9:`). CI was green on every
pushed head at the end of each step (17 checks, including the new *Demo stack on linux/arm64*).

**Data scope of every number below: subset: FAERS 2019Q1–2026Q2** unless the line says otherwise.

## Summary

| Step | Result | Evidence |
|---|---|---|
| P0 | Network re-tested with real downloads; FAERS, RxNav, Hugging Face, PyTorch reachable; FDA's SrLC and EMA refused | `analysis/PROGRESS.md` ("Start of Phase 2 (P0)") |
| P1 | 30 FAERS quarters → **10,848,477 unique cases**; RxNorm covers 98.5% of drug mentions; 5,000-case demo slice | `analysis/p1/data_counts.json`, `analysis/m2/*.json` |
| P2 | PRR/ROR/IC/EBGM on OMOP and EU-ADR with bootstrap CIs; best on OMOP: **IC 0.821**; time travel not computable | `analysis/p2/README.md`, `baselines.json` |
| P3 | SafetyFormer (CPU-sized) **does not beat the classic methods** (OMOP 0.765, EU-ADR 0.813); leakage check +0.061 recall@10 | `analysis/p3/*.json`, `ml/MODEL_CARD_safetyformer.md` |
| P4 | Triage ROC 0.960 (**0.924 within US reports**); duplicates PR-AUC 0.998 on a proxy label; zero-shot coder bundle, not evaluated | `analysis/p4/*.json`, model cards, `analysis/models/MANIFEST.md` |
| P5 | Demo mode: landing page, two public accounts, "Run the pipeline" replay of 200 real FAERS reports, signals and evidence pages, limits | `services/*/demo`, `frontend/src/pages/`, `ops/demo/nginx.demo.conf` |
| P6 | Oracle Cloud Always Free stack; measured **6,055 MiB peak** with the real data (200/200 acknowledged); arm64 green in CI | `docs/DEMO_HOSTING.md`, `analysis/p6/*.json` |
| P7 | Daily live smoke test (Playwright) with README badge; public `/api/status` | `.github/workflows/live-smoke.yml`, `StatusController` |
| P8 | README top section (badges, headline numbers, 4 screenshots and a GIF from the real-data stack), project page, demo script, interview questions 26–40 | `README.md`, `docs/site/`, `docs/DEMO_SCRIPT.md`, `analysis/INTERVIEW_PREP.md` |
| P9 | Azure day deploys the GHCR images; resume evidence with measured numbers only; this report | `docs/AZURE.md`, `analysis/RESUME_EVIDENCE.md` |

## P1: real FAERS

- 30 quarterly ASCII zips from fis.fda.gov, 2019Q1–2026Q2, each zip's SHA-256 recorded (`data_counts.json`).
- 12,902,877 report rows → 12,902,673 unique report keys → 12,837,383 after FDA's deleted-case lists →
  **10,848,477 unique cases** (latest version of each case). 12,837,383 report versions feed the duplicate model.
- RxNorm: the 20,000 most frequent drug-name keys (99.2% of 46,107,190 mentions) looked up in RxNav (7,196 exact,
  8,221 approximate, 4,583 not found). In the de-duplicated cases 40,904,129 of 41,528,250 mentions (**98.5%**)
  map to an ingredient; primary-suspect drugs **99.96%**.
- Gold: 4,182 ingredients, 22,231 PTs, 1,921,617 primary-suspect drug–event pairs; 57.1% of cases serious.
- Demo slice: 5,000 de-duplicated 2026Q2 cases, structured fields only, SHA-256 in `replay_meta.json`.
- **Not done: VAERS 2019–2025.** vaers.hhs.gov serves the data files only after a CAPTCHA, which was not bypassed
  (owner step 14).

## P2: signal baselines

AUC with 95% bootstrap CI (1,000 resamples), primary-suspect drugs (`analysis/p2/README.md`):

| | PRR | ROR | IC | EBGM | lower-bound PRR / ROR |
|---|---|---|---|---|---|
| OMOP, 388 scorable pairs (161 / 227) | 0.759 | 0.759 | **0.821** (0.779–0.860) | 0.765 | 0.814 / 0.818 |
| EU-ADR, 92 scorable pairs (42 / 50) | 0.916 | 0.916 | 0.919 | 0.910 | 0.930 / 0.930 |

Counting every drug role is clearly worse on OMOP (IC 0.688). Of the six reference drugs without an exact name match,
five are matched through a documented, label-blind name table; Endopeptidases (a drug class) stays unscorable.
**Time-travel test: not computed.** The reference sets carry no dates, and FDA's SrLC server answers automated
requests with an "abuse detection" page; that protection was not worked around.

## P3: SafetyFormer

CPU-sized (d = 128, 2 layers, 5,327,814 parameters), 10,000 steps × 256 reports on 4 CPU cores in 36 minutes;
10,302,667 training / 542,900 validation reports (time split); validation recall@10 0.477.

| Same scorable pairs | SafetyFormer | best classic method |
|---|---|---|
| OMOP (388) | 0.765 (0.719–0.811) | IC 0.821 (0.779–0.860) |
| EU-ADR (92) | 0.813 (0.721–0.897) | lower-bound PRR/ROR 0.930 |

**It loses**, and the README, model card, evidence page, demo script and resume evidence say so. Leakage check
(two more models, 5,000 steps each, 8,664,869 train / 2,180,698 test reports): time-ordered recall@10 0.437,
shuffled 0.498, so a random split overstates by **0.061**. ONNX export: SHA-256 `fbf4d411…`, parity 5.7e-6;
checkpoint and ONNX hashes in `analysis/models/MANIFEST.md`. Owner steps 4–5 now run from `main`.

## P4: case models

| Model | Result | Notes |
|---|---|---|
| Seriousness triage (LightGBM, random 1,000,000 cases, test from 2025-01-01, 201,450 test reports) | ROC AUC 0.960, PR-AUC 0.969, Brier 0.078; **US only 0.924 / 0.883**, non-US 0.923 ROC | Reporter country is the top feature: FDA receives foreign reports mostly when serious (95.4% vs 35.2%). Both numbers are reported together. |
| Duplicates (LightGBM, versions of a random 300,000 cases) | PR-AUC 0.998; P 0.984 / R 0.979 at best F1 | Proxy label (follow-up versions of one case): an easy task, stated in the card. |
| MedDRA coder | **not evaluated** | Zero-shot retrieval with `NeuML/pubmedbert-base-embeddings` (Apache-2.0, pinned revision) over the 3,000 most reported PTs, int8 ONNX. Exact wording scores 1.0, lay wording is weak; examples recorded as produced. No training labels from any LLM. Evaluation needs VAERS (owner step 14). |

Trained bundle: eight files, each SHA-256 in `analysis/models/MANIFEST.md`; Python ONNX parity 2.3e-7 (triage)
and 8.2e-8 (duplicates).

## P5–P7: the live demo

- Demo mode is in the code (case-service auto-review as `demo-auto-review` through the normal review calls,
  intake replay over SOAP with WS-Security and REST, stage counters from case-service's audit trail, public
  `/api/auth/demo`, signals page with methods side by side, search and reference badges, evidence page built
  from committed files). Limits: per-IP rate limits, one run per account, 2-minute cooldown, 200 reports at most,
  two runs at once, 16 KB bodies, no public route to uploads, SOAP, reload or actuator.
- Hosting: one Oracle Cloud Always Free Ampere A1 VM (2 OCPU / 12 GB as of 15 June 2026), docker compose,
  Caddy with Let's Encrypt on `<ip>.sslip.io`, cloud-init, *Deploy demo* workflow with rollback, nightly reset,
  multi-arch GHCR images tagged with the commit SHA and `demo`.
- **Memory, measured** (`analysis/p6/`):

| Run | Idle | Peak | Result |
|---|---|---|---|
| Build container (x86_64), **real demo data** (trained models, 810,403 signal pairs), 200 replayed FAERS reports | 5,748 MiB | 6,055 MiB | 200 / 200 acknowledged in 42 s; 158 coded by the model, 42 manual |
| GitHub arm64 runner (native arm64), fixture models, 40 invented records | 5,428 MiB | 5,560 MiB | 40 / 40 acknowledged in 11 s |

  Limits total 8,464 MiB (target under 9 GB). The real-data run used a permissive stand-in schema (EMA is
  refused from the build container; CI and the server use the official set) and lifted Artemis's disk limit
  because the build container's disk was 95% full; `DEMO_HOSTING.md` says both.
- Demo data: `pharmavigil-demo-data.tar.gz`, 145,042,617 bytes, SHA-256
  `e97066696eb2fa3abda2ad2d7113b5d0abf0de1c4214d0b4eed7ee15c3cee021` (reproducible build, pinned in
  `ops/demo/demo-data.lock`), sent to Sumit in the chat for owner step 15. No MedDRA dictionary file inside.
- P7: *Live smoke test* runs daily (06:17 UTC) and on demand against `DEMO_URL`; it signs in with the account
  the landing page shows, replays 10 reports, waits for an ACK, opens the E2B(R3) XML and checks Signals and
  Evidence. `/api/status` reports each service, Kafka lag and the loaded model versions (checked locally:
  `UP`, lag 0, bundle `trained`).

## Bugs real data exposed (all fixed, with tests)

1. EBGM prior depended on row order: identical data gave different priors (NLL 4,432,544 vs 4,499,742). Fixed with
   sorted input, five starts, best likelihood kept; every start recorded.
2. Out of memory four times on the 15 GiB build machine (the dense triage matrix, the SafetyFormer corpus as
   Python tuples, scoring the full drug × PT table, and DuckDB's own error under a 4 GB cap); the duplicate loader
   was changed before it could fail the same way. Fixed with sampling recorded in the results, Arrow-based loading,
   vectorized subsets, a 7 GB DuckDB cap and pair-only scoring.
3. The E2B(R3) schema folder was unreadable by the services' non-root user, so **no case could be submitted**
   on the server; fixed in `scripts/fetch-e2b-xsd.sh`, found by the new arm64 check.
4. The demo compose had no health checks for the Java services; Grafana downloaded plugins at every start;
   signal-service read its 139 MB export whole to hash it.
5. `data_counts` called gold's (mention, ingredient) rows "drug mentions"; caught before committing.

## Not done, and why

| Item | Why | Who / next |
|---|---|---|
| VAERS 2019–2025 | CAPTCHA on the data files; not bypassed | Owner step 14 |
| Coder accuracy | needs VAERS narratives with human-assigned PTs | after step 14 (and GPU step 6) |
| Time-travel lead times | no dated label changes reachable (SrLC refuses automated downloads) | an SrLC export would unblock it |
| Live URL, live smoke badge green | the server is the owner's | Owner steps 15–18 |
| Full FAERS history (2004 onward), full-size SafetyFormer | needs Databricks and a GPU | Owner steps 4–5 |

## Owner steps, in order

1. **Live demo:** merge the Phase 2 pull request, then step 15 (publish the demo-data release with the file sent
   in the chat), 16 (make the GHCR packages public), 17 (Oracle Cloud account and the VM), 18 (connect GitHub to
   the server, which runs *Deploy demo*), 19 (turn on GitHub Pages).
2. **Data and GPU:** step 14 (the three VAERS files), step 4 (Databricks, full FAERS history), steps 5–6 (Kaggle
   GPU: SafetyFormer full size, MedDRA coder).
3. **Labels and licence:** step 7 (label the 50 drug narratives), step 8 (MedDRA academic licence).
4. **Azure:** step 9 (one day on AKS from the GHCR images, then destroy).

Steps 2, 3, 12 and 13 are marked DONE. Every step is click-by-click with one path and ends with what to send back;
none asks for a secret in chat.

## Rules kept

- No invented numbers: every number above is in a committed file under `analysis/` with its scope; the evidence
  page and README are generated from or checked against those files.
- No secrets in git, chat, logs or images: the demo `.env` is generated on the server; the README screenshots
  hide even the public demo password; `.gitignore` covers DuckDB spill files and capture frames.
- No real patient data on the pharmacy side; FAERS is public and de-identified; the replay keeps structured
  fields only.
- No MedDRA files committed or served; only PT strings from FAERS.
- No training labels from an LLM (the coder is zero-shot; the triage and duplicate labels come from FAERS).
- Negative results are reported as such (SafetyFormer, the triage country effect, the duplicate proxy).
