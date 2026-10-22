# ADR-005: Signal score definition and reference-set mapping

- **Status:** accepted
- **Milestone:** M3 (baselines), M4 (SafetyFormer)

## Context

Signal methods must be compared on the same footing: the same reports, the same drug identities and the
same outcome definitions. The OMOP and EU-ADR reference sets name drugs with OMOP concept IDs and
define outcomes as clinical conditions (for example "acute liver failure"), while FAERS stores free-text
drug names and MedDRA Preferred Terms (PTs). The SafetyFormer score also has to be fixed before its
results are seen, so it cannot be tuned to the test set.

## Options considered

1. **Map outcomes with a licensed MedDRA hierarchy or SMQs.** Most faithful, but needs the MedDRA
   licence, which has not arrived yet (owner step), and MedDRA files may never be committed.
2. **Hand-made PT lists per outcome, using only PT strings printed in public FAERS files.** Transparent
   and licence-safe; coarser than an SMQ.
3. **Drop outcome mapping and evaluate PT by PT.** Not comparable with the published reference-set work.

## Decision

Option 2, with these rules:

- **Drugs:** reference-set `exposureName` is matched to the RxNorm ingredient names present in the gold
  tables (exact normalized match, then a singular form). Unmatched drugs are reported, never dropped.
- **Outcomes:** a report has the outcome if it lists any PT of the outcome's list below (case-insensitive).
  The lists are the author's, built from MedDRA PT names that describe the condition; they are not SMQs.
  `pv-data quality` reports any listed PT that never appears in FAERS.
- **Counts:** unique cases; primary-suspect-only and all-roles variants; background = cases with at
  least one mapped drug in the chosen roles.
- **Baseline scores:** PRR, ROR (Haldane correction for zero cells), IC with Norén shrinkage, EBGM with a
  two-gamma prior fitted on all observed PT-level pairs (zero-truncated likelihood, at most 2M pairs
  sampled with a fixed seed). Point estimates and lower bounds (PRR/ROR 95% CI lower limit, IC025, EB05)
  are both evaluated.
- **SafetyFormer score** (fixed before training): for drug d and outcome O with PT set S,

      score(d, O) = log Σ_{pt∈S} p_θ(pt | drug = d) − log Σ_{pt∈S} p_θ(pt | no drug)

  where p_θ(pt | ·) is the model's masked-reaction distribution when the report contains only the given
  drug token (or the "no drug" background token) and unknown age, sex and seriousness. It is the model's
  pointwise mutual information between the drug and the outcome; positive means "predicted more often
  with this drug than without".

### Outcome → PT lists

| Outcome (reference-set name) | MedDRA PTs (lower-case, as printed in FAERS) |
|---|---|
| OMOP Acute Liver Failure 1 | acute hepatic failure, subacute hepatic failure, hepatic failure, hepatitis fulminant, hepatic necrosis, hepatotoxicity, drug-induced liver injury, liver injury, hepatocellular injury, cholestatic liver injury, mixed liver injury, hepatitis acute, hepatitis cholestatic |
| OMOP Acute myocardial Infarction  1 | acute myocardial infarction, myocardial infarction, silent myocardial infarction |
| OMOP Acute Renal Failure 1 | acute kidney injury, renal failure, renal tubular necrosis, nephropathy toxic, prerenal failure, anuria |
| HOI Upper GI #3 | upper gastrointestinal haemorrhage, gastrointestinal haemorrhage, gastric haemorrhage, gastric ulcer haemorrhage, duodenal ulcer haemorrhage, peptic ulcer haemorrhage, gastroduodenal haemorrhage, oesophageal haemorrhage, haematemesis, melaena |
| Anaphylaxis #1 | anaphylactic reaction, anaphylactic shock, anaphylactoid reaction, anaphylactoid shock |
| OMOP Aplastic Anemia 1 | aplastic anaemia, bone marrow failure |
| Leukopenia Including Neutropenia and Agranulocytosis | leukopenia, neutropenia, agranulocytosis, febrile neutropenia, neutrophil count decreased, white blood cell count decreased |
| Stevens-Johnson Syndrome #1 | stevens-johnson syndrome, toxic epidermal necrolysis, sjs-ten overlap |
| Rhabdomyolysis #1 | rhabdomyolysis, myoglobinuria |
| Cardiac Valve Fibrosis #1 | cardiac valve fibrosis, cardiac valve disease, aortic valve incompetence, mitral valve incompetence, tricuspid valve incompetence |

## Consequences

- Results are licence-safe and reproducible from the repository alone.
- Hand-made lists can miss PTs a MedDRA SMQ would include, which lowers recall for some outcomes; when
  the licence arrives, an SMQ-based mapping can be added as a second, clearly labelled variant.
- Comparisons with published AUCs must state these choices, since papers differ in data years, outcome
  definitions and drug normalization.
