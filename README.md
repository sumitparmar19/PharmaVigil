# PharmaVigil

**An AI drug-safety (pharmacovigilance) platform: from the pharmacy counter to the safety signal and back.**

> **Not for clinical or regulatory use.** Spontaneous reports do not prove that a drug caused a
> reaction. The pharmacy side uses synthetic patients only.

PharmaVigil turns a pharmacist's one-click side-effect report into a de-duplicated, MedDRA-coded,
triaged case that a human reviewer approves and exports as a schema-valid ICH E2B(R3) XML file. Safety
signals learned from public FDA adverse event reports (FAERS) come back to the pharmacy counter as
evidence the next time the drug is dispensed.

This README becomes the full design document in milestone M12. Until then:

- What and why: [`docs/PLAN.md`](docs/PLAN.md)
- How it is built: [`docs/BUILD_INSTRUCTIONS.md`](docs/BUILD_INSTRUCTIONS.md)
- Progress: [`analysis/PROGRESS.md`](analysis/PROGRESS.md)
- Architecture and versions: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)

## AI assistance

Work in this repository is built with AI pair-programming (Claude Code). The design review and
decisions are Sumit's; commits carry `Co-Authored-By` trailers.

## Licence

MIT. See [`LICENSE`](LICENSE).
