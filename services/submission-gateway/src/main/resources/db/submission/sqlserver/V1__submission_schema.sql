-- submission-gateway schema (SQL Server); see the Oracle migration for the design.
CREATE TABLE submission_message (
    id               BIGINT IDENTITY(1,1) PRIMARY KEY,
    message_id       VARCHAR(100) NOT NULL CONSTRAINT uq_submission_message UNIQUE,
    case_id          BIGINT NOT NULL,
    case_number      VARCHAR(40) NOT NULL,
    version_no       INT NOT NULL,
    safety_report_id VARCHAR(100) NOT NULL,
    status           VARCHAR(16) NOT NULL,
    reason           NVARCHAR(2000),
    queued_at        DATETIME2(6) NOT NULL,
    answered_at      DATETIME2(6)
);

CREATE TABLE processed_event (
    event_id      VARCHAR(64) NOT NULL,
    consumer      VARCHAR(64) NOT NULL,
    processed_at  DATETIME2(6) NOT NULL,
    CONSTRAINT pk_processed_event PRIMARY KEY (event_id, consumer)
);

CREATE TABLE outbox_event (
    id              BIGINT IDENTITY(1,1) PRIMARY KEY,
    event_id        VARCHAR(64) NOT NULL CONSTRAINT uq_outbox_event UNIQUE,
    aggregate_type  VARCHAR(32) NOT NULL,
    aggregate_id    VARCHAR(64) NOT NULL,
    topic           VARCHAR(64) NOT NULL,
    payload         VARCHAR(MAX) COLLATE Latin1_General_100_CI_AS_SC_UTF8 NOT NULL,
    created_at      DATETIME2(6) NOT NULL,
    published_at    DATETIME2(6),
    attempts        INT NOT NULL
);
CREATE INDEX ix_outbox_unpublished ON outbox_event (published_at, id);
