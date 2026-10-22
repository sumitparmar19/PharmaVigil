# ICH E2B(R3) in PharmaVigil

<!-- What PharmaVigil reads and writes in ICH E2B(R3) Individual Case Safety Reports, where the official
     schemas come from, what is checked automatically and what is not, and which fields are left out. -->

E2B(R3) is the ICH standard message for sending an Individual Case Safety Report (ICSR) between companies
and regulators. A file is an HL7 v3 batch (`MCCI_IN200100UV01`) that wraps one or more ICSR messages
(`PORR_IN049016UV`). PharmaVigil:

- **imports** E2B(R3) files: partner SOAP `SubmitCase` (one ICSR per call) and reviewer file uploads
  (`POST /api/intake/e2b`, batches allowed) in `intake-gateway`;
- **exports** an approved case as an E2B(R3) file (`GET /api/cases/{id}/e2b` in `case-service`), and only an
  export that passed the official schema check can be queued for submission (engineering rule 7).

The code lives in `services/common/src/main/java/io/pharmavigil/common/e2b/`: `IcsrCase` (the mapped subset
as plain records), `E2bWriter`, `E2bReader` (hardened parser: no DTDs, no external entities), `E2bValidator`
and `IcsrMapping` (E2B code lists ↔ the `report.received` event).

## Official schemas

- **Source:** the copy of the ICH E2B(R3) schema set that the European Medicines Agency publishes for
  EudraVigilance, at `https://eudravigilance.ema.europa.eu/XSD/` (the `schemaLocation` used in ICSR files).
  The ICH site and FDA hosts are not reachable from the build container, and FDA's pages are also refused
  from GitHub's runners; EMA's copy is reachable from GitHub's runners.
- **Fetching:** `scripts/fetch-e2b-xsd.sh <dir>` downloads `multicacheschemas/MCCI_IN200100UV01.xsd` and
  follows every `xs:include` / `xs:import` (50 files in CI), refusing any path that would leave the schema
  root. It writes the set only when every file arrived. Then set `PV_E2B_XSD_DIR=<dir>`.
- **Not committed:** the schemas stay out of git (licence terms are not stated with the files); `.gitignore`
  excludes the local copy.
- **CI:** the job *E2B(R3) export against the official schemas* fetches the set and runs the `common` E2B
  tests with it. The main Java job fetches it too (best effort), so the intake and export tests run against
  it as well.
- **Without the schemas** (local runs in this container): `E2bValidator` returns `SCHEMA_UNAVAILABLE`. Intake
  still accepts well-formed messages and records `schema_checked = false` with a warning to the sender;
  export is never `queueable`.

## What is verified, and what is not

| Check | How | Status |
|---|---|---|
| Exported files are schema-valid | `E2bRoundTripTest.exportValidatesAgainstOfficialSchemaWhenAvailable` and the case-service export test, against the EMA-hosted set | passes in CI (see `analysis/PROGRESS.md`, M7) |
| The schema check is not vacuous | `officialSchemaRejectsABrokenMessage`: an element the standard lacks is reported as an error | passes in CI |
| Partner messages with schema errors are refused | `SoapIntakeContractTest.schemaInvalidIcsrIsRejectedWhenSchemasAreInstalled` | runs in CI |
| Round trip | write → read gives the same mapped fields; re-export is XML-identical apart from generated ids (XMLUnit) | passes locally and in CI |
| Hostile input | DOCTYPE / external entity, malformed XML, foreign root, batch without ICSR are rejected | passes |
| Code values and OIDs | constants in `E2bCodes` follow the ICH E2B(R3) implementation guide as written from the guide's tables; **not yet checked against the guide or official example instances**, which could not be downloaded here | open: see "Known gaps" |
| Official sample messages | the build instructions ask for round trips on the ICH package's samples "if it includes them"; EMA's schema folder has none, and the ICH package could not be downloaded | not done |

XSD validation proves structure and data types. It does **not** prove business rules (which code list a
`code` attribute must come from, conditional fields, EU or FDA regional rules). Those need the ICH
implementation guide's business rules or a regulator's test system.

## Field mapping

`IcsrCase` holds the mapped subset. Data element numbers are E2B(R3) ones.

| E2B(R3) | Meaning | PharmaVigil source / target |
|---|---|---|
| N.1.2, N.2.r.1 | batch number, message id | `B-<case number>-V<version>`, `M-<case number>-V<version>` on export |
| N.1.3/N.2.r.2, N.1.4/N.2.r.3 | sender, receiver | `pv.e2b.sender-id` (PHARMAVIGIL), `pv.e2b.receiver-id` (MOCK-REGULATOR) |
| N.1.5, N.2.r.4 | creation time | export time (UTC) |
| C.1.1 | sender's safety report id | export: `<country>-PHARMAVIGIL-<case number>`; import: the case's source report id |
| C.1.8.1 | worldwide unique case id | pharmacy cases: same as C.1.1; imported cases: the id the first sender gave |
| C.1.3 | type of report | 1 (spontaneous) |
| C.1.4, C.1.5 | first received, most recent information | case received date; date of the exported version |
| C.2.r.3 | reporter country | case country (ISO 3166-1 alpha-2) |
| C.2.r.4 | reporter qualification | 1 physician, 2 pharmacist, 3 other HCP, 4 lawyer, 5 consumer ↔ `reporterType` |
| D.2.2 | age at onset | years (`a`) |
| D.5 | sex | ISO 5218: 1 male, 2 female; anything else is left out (unknown) |
| E.i.1.1a | reaction as reported | `reportedTerm` |
| E.i.2.1a/b | MedDRA version and code | **empty** until the MedDRA licence is active (CLAUDE.md domain rules); the PT text from public FAERS/VAERS strings travels as the code element's `displayName` |
| E.i.3.2a–f | seriousness criteria | `seriousCriteria` (DEATH, LIFE_THREATENING, HOSPITALIZATION, DISABILITY, CONGENITAL_ANOMALY, OTHER_MEDICALLY_IMPORTANT); on export every reaction carries the case-level criteria |
| E.i.4 | reaction onset | case onset date |
| G.k.1 | characterization | 1 suspect (PS/SS), 2 concomitant (C), 3 interacting (I); on import the first suspect becomes PS |
| G.k.2.2 | medicinal product name | drug name |
| G.k.2.1 (product id) | RxNorm RxCUI | carried as the product code; RxNorm is a US vocabulary, not an E2B identifier |
| G.k.4.r.8 (dosage text) | dose as text | `doseText` |
| H.1 | case narrative | the **approved** narrative (export requires one) |

### Not mapped (left out on export, ignored on import)

- Reporter name, address and contact (C.2.r.1–2) and patient initials (D.1): PharmaVigil keeps no personal
  identifiers (synthetic patients only).
- Literature (C.4), study (C.5), sender organisation details (C.3).
- Patient weight, height, last menstrual period, medical history, past drug history, death details, parent
  data (D.3–D.10).
- Reaction outcome, duration, medical confirmation (E.i.7–E.i.9), tests and procedures (F).
- Structured dosage, route codes, indication, action taken, drug–reaction relatedness matrix, additional
  information (G.k.4–G.k.11).
- Sender's diagnosis and comments (H.2–H.4).
- **FDA regional elements** (FDA E2B(R3) regional implementation): not included; FDA's guidance and schema
  pages are refused from both the build container and GitHub's runners.

## Import rules

- A message must be well-formed, have the `hl7:MCCI_IN200100UV01` root and at least one ICSR, and carry
  C.1.1. With the schemas installed it must also pass them; failures are returned as a SOAP Client fault or
  a 422 with up to five schema errors (line, column, message).
- Every accepted ICSR gets a receipt and exactly one `report.received` outbox event, in one transaction.
- **Resend** (same source, same C.1.1, byte-identical message): the original receipt is returned with
  status `DUPLICATE`; no event.
- **Follow-up** (same C.1.1, changed message): accepted with a warning; case-service writes a new case
  version, keeps reviewer- or model-assigned codes for reaction terms that did not change, and moves a case
  that was approved, rejected or acknowledged back to review.

## Known gaps

1. OIDs and code values in `E2bCodes` need checking against the ICH E2B(R3) implementation guide and its
   example instances; the schema accepts any OID string, so a wrong OID would pass XSD validation.
2. JAXB classes are not generated from the HL7 v3 schemas (the build instructions suggest it). The writer
   and reader use DOM with the mapped subset instead: the schemas cannot be downloaded in the build
   container and are not committed, so code generation would break offline builds. Schema validation in CI
   keeps the output honest.
3. No regulator business-rule validation (EMA or FDA test systems need registration).
