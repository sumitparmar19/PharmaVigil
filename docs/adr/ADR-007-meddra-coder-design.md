# ADR-007: MedDRA coder design (classification + retrieval)

- **Status:** accepted
- **Milestone:** M5

## Context

A coder must suggest PTs for free text. Label frequency is extremely skewed: a few hundred PTs cover most
codings, while thousands appear only a handful of times. Reviewers pick from a short list, so top-5
recall matters more than exact-set accuracy. Training labels must come from humans (VAERS coders), not
from an AI model, and no MedDRA dictionary files can be used before the licence arrives.

## Options considered

1. **Pure multi-label classifier over every PT seen.** Simple, but rare PTs get too few examples and
   unseen PTs can never be suggested.
2. **Pure retrieval (embed narrative and PT names, nearest neighbours).** Covers any PT name, but is
   weaker than supervised learning on frequent PTs.
3. **Classifier for the frequent head + retrieval for the tail**, merged into one ranked list.
4. **A generative LLM.** Not allowed for labels, harder to serve in Java, and unnecessary.

Base encoders considered: BiomedBERT (PubMedBERT) base, a biomedical vocabulary pretrained from scratch
on PubMed, max 512 tokens; ModernBERT base, newer with long context but a general-domain vocabulary;
clinical ModernBERT variants. BiomedBERT is chosen for its biomedical vocabulary and its long record on
biomedical classification; narratives are truncated to 256 tokens, which covers most VAERS narratives
(the share truncated is reported with the results).

## Decision

Option 3 with BiomedBERT base: mean-pooled encoder output → linear head over the PTs covering 95% of
label occurrences (at most 2,000); the same encoder embeds PT names for the tail; the tail score is
`w · (cos + 1)/2` with `w` tuned on validation for top-5 recall. Time split by VAERS receipt year.

## Consequences

- Rare and unseen PT names can still be suggested; the tail's quality is reported separately.
- One ONNX graph serves both parts (probabilities and embedding outputs); PT-name embeddings are
  precomputed once and shipped with the model.
- The domain shift from vaccines to drugs is measured on Sumit's labelled set, not assumed away.
