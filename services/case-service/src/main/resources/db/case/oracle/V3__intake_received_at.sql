-- When intake-gateway accepted the latest report (first or follow-up) for the case (UTC). Used for the intake-to-coded latency
-- metric; NULL for cases created before this column existed.
ALTER TABLE safety_case ADD intake_received_at TIMESTAMP(6);
