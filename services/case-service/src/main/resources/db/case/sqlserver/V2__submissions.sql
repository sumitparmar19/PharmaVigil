-- Submission history (M8); see the Oracle migration for the design.
CREATE TABLE submission_event (
    id               BIGINT IDENTITY(1,1) PRIMARY KEY,
    case_id          BIGINT NOT NULL CONSTRAINT fk_submission_case REFERENCES safety_case (id),
    version_no       INT NOT NULL,
    safety_report_id VARCHAR(100) NOT NULL,
    message_id       VARCHAR(100),
    status           VARCHAR(16) NOT NULL,
    reason           NVARCHAR(2000),
    occurred_at      DATETIME2(6) NOT NULL
);
CREATE INDEX ix_submission_case ON submission_event (case_id, id);
