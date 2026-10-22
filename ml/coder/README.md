# MedDRA coder: documents and hand-labelled data

What this folder is: the coder's model card and the hand-labelled drug-narrative set used for the
domain-shift check (the coder is trained on vaccine reports from VAERS, then scored on drug reports).

- `MODEL_CARD.md`: what the coder is, how it was trained and evaluated, its limits.
- `drug_narratives_template.csv`: 50 short **invented** drug side-effect narratives (no real patients;
  written as test inputs, not labels). The `sumit_pts` column is filled in by Sumit with the MedDRA
  Preferred Terms he would code, separated by `;` (owner step). Labels never come from an AI model.
- Score after labelling: `cd ml && .venv/bin/pv-ml coder-drug-narratives` →
  `analysis/m5/coder_drug_narratives.json`.
