# ADR-004: E2B(R3) mapping scope, DOM writer and schema validation in CI

<!-- Records which part of ICH E2B(R3) PharmaVigil maps, why the XML is written with DOM rather than generated
     JAXB classes, and how correctness is checked. Field-by-field detail lives in docs/E2B_R3.md. -->

- **Status:** accepted
- **Milestone:** M7

## Context

An ICH E2B(R3) ICSR is an HL7 v3 message with hundreds of data elements. PharmaVigil needs enough of it to
receive partner reports (SOAP, file upload) and to send an approved case to a (mock) regulator, and engineering
rule 7 says nothing may be queued unless it passes the official XSDs. Constraints: the schemas may not be
committed (licence terms are not stated with the files) and cannot be downloaded in the build container (EMA's
copy is reachable from GitHub's runners); no personal identifiers are kept (synthetic patients only); MedDRA
codes cannot be used before the academic licence arrives.

## Options considered

1. **Full mapping with JAXB classes generated from the HL7 v3 schemas.** Pros: type-safe, complete. Cons: the
   build would need the schemas, which cannot be committed or fetched in every build environment; a very large
   generated model for a small mapped subset.
2. **A mapped subset with a DOM writer and a hardened DOM reader, validated against the official schemas in
   CI.** Pros: small, builds offline, the schema check keeps it honest. Cons: structure is not guaranteed by
   types, so tests must cover it.
3. **Hand-built XML strings.** Cons: escaping and namespaces go wrong; rejected.

## Decision

Option 2. `common/e2b` holds `IcsrCase` (the mapped subset), `E2bWriter`, `E2bReader` (no DTDs, no external
entities), `E2bValidator` (VALID / INVALID / SCHEMA_UNAVAILABLE) and `IcsrMapping`. The mapped subset covers the
batch and message headers, C.1 identification, reporter qualification and country, patient age and sex, reactions
(reported term, seriousness criteria, onset), drugs (characterization, name, RxCUI as product code, dose text) and
the approved narrative. Reporter and patient identifiers, literature, study, medical history, tests, structured
dosage and FDA regional elements are left out (listed in docs/E2B_R3.md). MedDRA code fields stay empty; the PT
text from public FAERS/VAERS strings travels as `displayName`.

## Consequences

- CI fetches EMA's schema set (`scripts/fetch-e2b-xsd.sh`, 50 files) and checks that exports validate and that a
  broken message is rejected (so the check is not vacuous); the end-to-end test ACKs an exported case through the
  mock regulator.
- Without the schemas, intake still accepts well-formed messages (marked `schema_checked = false`, with a warning
  to the sender) and exports are never queueable, so the service degrades instead of stopping.
- XSD validation proves structure, not business rules: OIDs and code values in `E2bCodes` still need checking
  against the ICH implementation guide and its examples (open item in docs/E2B_R3.md).
- Adding a field means extending `IcsrCase`, the writer, the reader and the round-trip test together.
