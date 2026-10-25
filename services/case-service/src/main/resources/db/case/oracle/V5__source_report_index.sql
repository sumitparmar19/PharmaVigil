-- Demo mode (Phase 2): the live demo counts replayed cases by their source report id prefix; this index keeps
-- those lookups and the demo auto-review's sweep from scanning the whole table.
CREATE INDEX ix_case_source_report ON safety_case (source_report_id);
