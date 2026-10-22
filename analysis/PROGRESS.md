# PharmaVigil progress log

What this file is: the running record of each milestone (status, evidence, data scope), every deviation
from `docs/BUILD_INSTRUCTIONS.md`, and every environment re-test. The planning chat checks it against
the repo.

## Milestones

| # | Milestone | Status | Evidence |
|---|---|---|---|
| M0 | Bootstrap, context and rules | in progress | this file, `analysis/M0_REVIEW.md`, CI workflows |
| M1 | Legacy audit | **removed by owner** (Amendment 1, item 1) | n/a |
| M2 | Data platform | pending | |
| M3 | Signal baselines | pending | |
| M4 | SafetyFormer | pending | |
| M5 | Coder, triage, duplicates | pending | |
| M6 | Java platform core | pending | |
| M7 | E2B(R3) and SOAP | pending | |
| M8 | Kafka, ONNX in Java, JMS, narrative | pending | |
| M9 | Frontend | pending | |
| M10 | Operations | pending | |
| M11 | Azure infrastructure (code only) | pending | |
| M12 | Docs, evidence, report | pending | |

## M0: bootstrap

- Removed the previous application's files in one commit (`M0: start PharmaVigil fresh`).
- Created the layout from §3: `services/` (Maven parent + `common` + 5 Spring Boot service skeletons),
  `data/` and `ml/` (Python packages with pytest and ruff), `frontend/` (React 19 + TypeScript 6 +
  Vite 8 + Tailwind 4 with Vitest), `docs/` (plan, instructions with Amendment 1, architecture,
  ADR template), `analysis/`, `CLAUDE.md`, `OWNER_STEPS.md`, `.env.example`, `.gitignore`, MIT `LICENSE`.
- CI: `.github/workflows/ci.yml` (Maven verify, pytest + ruff for `data` and `ml`, frontend
  typecheck/lint/test/build, gitleaks over full history), `codeql.yml` (Java, Python, TypeScript),
  `dependency-review.yml` (pull requests).
- Local results before the first push: `mvn -B verify` green (6 modules, 6 tests); `pytest` green in
  `data` and `ml`; frontend typecheck, lint, 2 Vitest tests and build green; gitleaks found no leaks
  in the history.

## Environment re-tests

### Start of M0

All hosts that Amendment 1 item 3 lists were still refused by the session's network policy (403 at
the proxy): fis.fda.gov, api.fda.gov, vaers.hhs.gov, rxnav.nlm.nih.gov, www.accessdata.fda.gov,
www.fda.gov, ich.org (www, database), huggingface.co, download.pytorch.org, quay.io, get.helm.sh, and
GitHub release/package downloads (pkg-containers.githubusercontent.com). raw.githubusercontent.com was
reachable. Owner step 1 in `OWNER_STEPS.md` asks Sumit to re-check the setting. Hosts are re-tested at
the start of each data milestone.

## Deviations from the build instructions

| Item | Instruction | What was done | Why |
|---|---|---|---|
| Plan copies | copy both files into `docs/` | copied verbatim, each with a one-line pointer to Amendment 1 at the top | keeps the original instructions intact while making the override visible |
| TypeScript | newest version | 6.0.3, not 7.0.2 | typescript-eslint 8.72 (latest) supports TypeScript < 6.1 only (Amendment 1 item 4: "newest the whole toolchain supports") |
| Kafka 4.4 | Kafka 4.4 | broker image `apache/kafka:4.4.0`; client 4.2.1 as managed by Spring Boot 4.1.1 | overriding the client under spring-kafka 4.1.1 risks incompatibilities; Kafka clients and brokers are wire-compatible |
| Docker locally | §6 fallback: rely on GitHub Actions | the container's Docker daemon can be started, so Testcontainers also runs locally | better local verification |
| gitleaks locally | n/a | Docker Hub `zricethezav/gitleaks` image locally; CI uses `ghcr.io/gitleaks/gitleaks` | ghcr.io image layers are served from a blocked host in this container |
