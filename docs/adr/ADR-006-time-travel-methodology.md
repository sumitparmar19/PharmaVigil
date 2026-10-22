# ADR-006: Time-travel methodology

- **Status:** accepted (the label-change source is pending; see below)
- **Milestone:** M4

## Context

Reference-set AUCs say whether a method ranks known pairs well, but not whether it would have
*warned earlier* than the regulator. The time-travel test asks: if each method had been run only on
reports available at some date, how many reactions later added to a drug's label would it already have
flagged, and how many months before the label change? Two traps must be avoided: using reports from after
the snapshot date (leakage) and inventing label-change dates.

## Options considered

1. **Retrain SafetyFormer for every label-change date.** Exact, but thousands of trainings.
2. **One snapshot per year** (Amendment 1). Each snapshot sees only reports received before 1 January of
   that year; a label change is scored by the latest snapshot before its date. Lead times have yearly
   granularity.
3. **One model on all data.** Simple, but leaks the future into every score.

## Decision

Option 2:

- Snapshots: 1 January of each year in the data window. Baselines recompute counts and the EBGM prior with
  the same cutoff; a fresh SafetyFormer is trained on that window only (its vocabulary too).
- A pair is **flagged** at a snapshot if its PT ranks in the drug's top N by that method's score
  (N = 10, 20, 50 reported).
- **Lead time** = label date − earliest snapshot before the label date at which the pair was flagged,
  in months (30.44-day months). Pairs whose drug or PT never appears before the label date are not
  evaluable and are counted separately.
- **Label changes** are rows of (drug name, label date, changed text). Drugs are matched to RxNorm
  ingredient names; PTs are matched as whole words in the changed text (PTs of at least 5 characters).
  Rows without a date are skipped and counted; a pair added several times keeps its earliest date.
- **Source of label changes**, in order: (1) the FDA Safety-related Labeling Changes (SrLC) database
  export; (2) if SrLC cannot be exported, DailyMed SPL version history for the evaluation drugs, comparing
  the Boxed Warning / Warnings and Precautions / Adverse Reactions sections between versions. The fetcher
  is written once one of these hosts is reachable, because neither API could be checked from the build
  container. Until then the test is **pending**, and no dates are invented.

## Consequences

- Fair comparison: every method sees exactly the same reports at each snapshot.
- Yearly granularity understates lead times by up to a year; the report states this.
- Text matching can add PTs that were only mentioned (not newly added) in a changed section; the match
  statistics are published with the results so the reader can judge.
- Separately, `analysis/m4/leakage.json` quantifies how much a shuffled split would overstate held-out
  performance compared with a time-ordered split.
