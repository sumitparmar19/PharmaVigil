-- Phase 2: the reference-set label of a drug–event pair (for example "OMOP positive", "EU-ADR negative"),
-- shown as a badge on the signals page, and an index for the drug search.
ALTER TABLE drug_event_signal ADD reference VARCHAR2(60 CHAR);
CREATE INDEX ix_signal_ingredient ON drug_event_signal (snapshot_id, ingredient);
