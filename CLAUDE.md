# CLAUDE.md: PharmaVigil project context and rules

PharmaVigil is an AI drug-safety (pharmacovigilance) platform. A pharmacist reports a side effect in
one click; the report is de-duplicated, MedDRA-coded and triaged by Sumit's own models, reviewed by a
human, and exported as a schema-valid ICH E2B(R3) XML file that a mock regulator acknowledges over JMS.
Safety signals learned from public FDA FAERS reports flow back to the pharmacy counter as evidence
(never as a verdict) the next time the drug is dispensed.

- Plan (the why, research, sources): `docs/PLAN.md`
- Build instructions (the how, milestones M0–M12) and **Amendment 1** (overrides): `docs/BUILD_INSTRUCTIONS.md`
- Progress log and deviations: `analysis/PROGRESS.md`
- Everything only the owner can do: `OWNER_STEPS.md`

## Who and how

- **Owner:** Sumit Parmar, MS CS at the University at Buffalo (Systems track), graduating December 2026,
  targeting new-grad software, backend/full-stack, data and AI roles. This is his healthcare/pharma
  project; it must not drift into finance.
- **Working model:** a planning chat writes instruction files, Claude Code builds, Sumit relays reports
  to the planning chat, which verifies them by cloning the repo and checking every number.
- **Owner steps** (`OWNER_STEPS.md`): one path (never two options), click-by-click, no dates. Each step
  ends with what to send back to the planning chat. Never ask for a screenshot of a secret.
- **Honesty:** nothing goes on the resume that isn't true here and that Sumit can't explain
  (`analysis/INTERVIEW_PREP.md`). No fake people, numbers or links. AI assistance is disclosed
  (keep `Co-Authored-By` trailers; the README has an AI-assistance section).

## Domain rules (non-negotiable)

- **No real patient data.** Public FAERS/VAERS only; synthetic patients on the pharmacy side.
- **No causation claims.** Every signal screen and the README say: "Spontaneous reports do not prove
  that a drug caused a reaction."
- **Not for clinical or regulatory use.** A banner on every UI page and a README section.
- **AI outputs are suggestions only.** Codes, seriousness, duplicates and narratives are confirmed by a
  human reviewer.
- **Training labels never come from Claude** (Anthropic commercial terms). Labels come from human coding
  in VAERS/FAERS and from Sumit's own hand-labelled set.
- **MedDRA:** never commit MedDRA dictionary files. Use only PT strings already in public FAERS/VAERS
  files. No SOC breakdowns until Sumit's academic licence arrives.

## Engineering rules

1. Every new file starts with a short comment saying what it does and why it exists.
2. Every service fails gracefully when an outside dependency is down (Kafka, Artemis, Oracle, RxNav,
   openFDA, Claude, model files): cases keep moving with a "needs manual coding" status.
3. Outbox pattern for every published event (DB change + event in one transaction, then a relay
   publishes). Idempotent consumers use a processed-event table. A dead-letter topic catches poison
   messages.
4. Case versions are append-only, with an audit row (who, when, what). No destructive updates.
5. AI outputs are suggestions: store model name + version, score, and the reviewer's final choice.
   Reviewer corrections go to a feedback table.
6. The LLM never does arithmetic and only sees the structured, already-coded case.
7. E2B(R3) files must pass XSD validation before they can be queued; failures are visible, explained
   errors.
8. Banners: "Not for clinical or regulatory use", "Reports don't prove causation", "Synthetic patients
   only" (pharmacy side).
9. Tests: each milestone adds tests; Java ≥ 70% line coverage per service (JaCoCo gate); Python data
   and ML code has pytest coverage of transforms and metrics.
10. Every metric is reproducible from a script in the repo; its JSON output is committed under
    `analysis/`, labelled with its data scope (e.g. "subset: FAERS 2019Q1–2026Q2").
11. Data files never go into git (only tiny fixtures under `tests/fixtures/`). Model artefacts are listed
    with hashes in `analysis/models/MANIFEST.md`.
12. Secrets only come from environment variables; every new one goes in `.env.example`. Never put a
    password, key, token or connection string in the repo, a log, a commit or a report.
13. Do not put AI model identifiers in commits, code comments or docs; the narrative model comes from
    the `CLAUDE_MODEL` environment variable.

## Layout

```
services/   Java 21, Maven multi-module, Spring Boot 4.1 (common + 5 services)
data/       Python data platform (FAERS/VAERS ingestion, dedup, RxNorm, quality) + Databricks notebooks
ml/         signal baselines, SafetyFormer, MedDRA coder, triage, duplicates, ONNX export
frontend/   React 19 + TypeScript + Vite + Tailwind: pharmacy app, reviewer console, signals page
ops/        Jenkins, Helm, kind/Strimzi, Prometheus, Grafana, k6
infra/azure Terraform (validated, applied only by the owner)
docs/       plan, instructions, architecture, ADRs, evaluation, E2B notes
analysis/   progress, reports, run outputs (metrics JSON, logs, charts) per milestone
```

## Commands

```bash
# Java (Docker needed for Testcontainers; start the daemon with `dockerd &` in a fresh container)
cd services && mvn -B verify

# Python
cd data && uv venv && uv pip install -e '.[dev]' && .venv/bin/pytest && .venv/bin/ruff check .
cd ml   && uv venv && uv pip install -e '.[dev]' && .venv/bin/pytest && .venv/bin/ruff check .

# Frontend
cd frontend && npm ci && npm run typecheck && npm run lint && npm test && npm run build
```

## Git

- Branch: this session's branch; one draft PR to `main`, updated as milestones land.
- At least one commit per milestone, message starting `M<n>:`. Keep CI green on every push.
