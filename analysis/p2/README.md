# P2: signal-detection baselines on real FAERS

<!-- What the files in analysis/p2/ are, how they were made and what they show. Every number below is copied
     from baselines.json (written by `pv-ml baselines`); nothing here is typed in by hand. -->

Spontaneous reports do not prove that a drug caused a reaction. Not for clinical or regulatory use.

**Scope: subset: FAERS 2019Q1–2026Q2** (10,848,477 de-duplicated cases, `analysis/p1/data_counts.json`).

```bash
cd ml && .venv/bin/pv-ml baselines --scope "subset: FAERS 2019Q1–2026Q2" --out ../analysis/p2
```

11 minutes on the build container (4 CPUs). Inputs: the gold FAERS tables, the OMOP and EU-ADR reference sets
(`analysis/m2/refsets.json`) and the outcome → PT map in ADR-005. 1,000 bootstrap resamples per AUC (positives and
negatives resampled separately); 95% percentile intervals.

## Files

| File | What it is |
|---|---|
| `baselines.json` | AUC with 95% CI for every method, both drug-role variants, both reference sets; pair counts; the EBGM prior and how it was fitted |
| `scores_<set>_<variant>.csv` | every reference pair with its counts (a, drug, event, total) and all scores, scorable or not |
| `roc_<set>_<variant>.png` | ROC curves of PRR, ROR, IC and EBGM on the scorable pairs |

## Pairs that could be scored

A pair is scorable when its drug appears in at least one analysed report. Unscorable pairs are counted and kept in
the CSVs, never dropped silently.

| Reference set | Pairs (pos / neg) | Scorable, primary suspect (pos / neg) | Scorable, any role (pos / neg) | Drugs not found in gold |
|---|---|---|---|---|
| OMOP | 399 (165 / 234) | 388 (161 / 227) | 395 (165 / 230) | Endopeptidases (a drug class, not one ingredient) |
| EU-ADR | 93 (43 / 50) | 92 (42 / 50) | 93 (43 / 50) | none |

Five reference drugs are written differently from their RxNorm ingredient (Chlorazepate, Olmesartan medoxomil,
Tetrahydrocannabinol, Thyroxine, "Regular Insulin, Human"); they are matched through a documented name table
(`REFERENCE_NAME_OVERRIDES` in `ml/pharmavigil_ml/signals/evaluate.py`), chosen from the names alone without
looking at the pairs' labels. Before that table, 380 OMOP and 88 EU-ADR pairs were scorable and the AUCs were
within 0.01 of the ones below.

## AUC (95% CI), primary-suspect drugs

The main result: a report counts towards a drug only when the drug is the primary suspect.

| Method | OMOP (388 pairs) | EU-ADR (92 pairs) |
|---|---|---|
| PRR | 0.759 (0.707–0.807) | 0.916 (0.849–0.969) |
| PRR lower 95% bound | 0.814 (0.772–0.855) | 0.930 (0.859–0.981) |
| ROR | 0.759 (0.707–0.807) | 0.916 (0.851–0.969) |
| ROR lower 95% bound | 0.818 (0.776–0.858) | 0.930 (0.859–0.981) |
| IC | **0.821 (0.779–0.860)** | 0.919 (0.852–0.970) |
| IC025 | 0.783 (0.736–0.827) | 0.920 (0.846–0.979) |
| EBGM | 0.765 (0.712–0.815) | 0.910 (0.829–0.971) |
| EB05 | 0.784 (0.737–0.828) | 0.920 (0.847–0.979) |

## AUC (95% CI), any drug role

The same evaluation counting every drug on a report (suspect, concomitant or interacting):

| Method | OMOP (395 pairs) | EU-ADR (93 pairs) |
|---|---|---|
| PRR | 0.690 (0.639–0.740) | 0.813 (0.735–0.891) |
| ROR | 0.689 (0.638–0.739) | 0.815 (0.737–0.892) |
| IC | 0.688 (0.639–0.736) | 0.809 (0.729–0.888) |
| EBGM | 0.684 (0.634–0.733) | 0.805 (0.723–0.890) |

(The lower-bound variants are in `baselines.json`.)

## What this shows

- On OMOP, counting only primary-suspect drugs is clearly better than counting every drug on the report (IC 0.821
  against 0.688; the intervals do not overlap). Concomitant drugs add reports that have nothing to do with the
  reaction.
- On OMOP the methods' intervals overlap heavily. IC has the highest point estimate, and the lower-bound variants
  of PRR and ROR beat their point estimates (they penalise pairs with few reports). No method is declared the
  winner on this evidence.
- EU-ADR scores are higher (about 0.92), but with 92 pairs the intervals are wide.

## The EBGM prior fit

The gamma-mixture prior is fitted to all 1,921,617 primary-suspect pairs (5,255,008 any-role pairs). Two earlier
runs on identical data gave different priors: L-BFGS-B from a single start stopped in different local optima
depending on the order the database returned the pairs in. The fit now sorts the pairs, tries five starts on a
fixed random 200,000-pair sample, and refines the best on all pairs; `ebgm_prior_fit` in `baselines.json` lists
every start's likelihood. The final primary-suspect fit (negative log-likelihood 4,432,552) matches the best
optimum seen in the earlier runs. It is an unusual prior: 98% of the weight (1 − p = 0.982) sits on a gamma with
a very small shape (alpha2 = 0.00018, beta2 = 0.0095), and 2% on Gamma(0.39, 0.58). EBGM still ranks the reference
pairs much like the other methods, but this prior is worth a closer look before EBGM is preferred for anything.
The stored signal tables (810,403 primary-suspect pairs and 2,519,509 any-role pairs with at least 3 reports)
contain no missing or infinite scores.

## Time-travel test: not computed

The plan asks how many months before a label change each method first flagged the pair. That needs a date for when
each drug–reaction pair became known:

- The OMOP and EU-ADR reference sets have no dates, and both were published before this data window begins (2019),
  so every positive pair was already known at the window's start; no lead time can be measured with them.
- Dated label changes come from FDA's Safety-related Labeling Changes database (SrLC). Its server answers automated
  requests from the build container with an "abuse detection" page (`analysis/PROGRESS.md`, P0). That protection
  was not worked around. DailyMed is reachable, but it gives label versions, not dated safety-change records, and
  turning it into a reference set would be a new, unvalidated method.

The code for the test is written and tested on synthetic data (`ml/pharmavigil_ml/safetyformer/timetravel.py`,
`pv-ml time-travel --label-changes <SrLC export>`). It runs once an SrLC export is available.
