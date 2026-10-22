-- intake-gateway schema (SQL Server): same tables as the Oracle migration; see that file for the design.
CREATE TABLE intake_receipt (
    id                BIGINT IDENTITY(1,1) PRIMARY KEY,
    receipt_id        VARCHAR(36) NOT NULL CONSTRAINT uq_receipt_id UNIQUE,
    source            VARCHAR(16) NOT NULL,
    source_report_id  VARCHAR(100) NOT NULL,
    partner_reference NVARCHAR(100),
    submitted_by      NVARCHAR(100) NOT NULL,
    payload_sha256    VARCHAR(64) NOT NULL,
    follow_up         BIT NOT NULL,
    schema_checked    BIT NOT NULL,
    status            VARCHAR(16) NOT NULL,
    received_at       DATETIME2(6) NOT NULL,
    CONSTRAINT uq_receipt_payload UNIQUE (source, source_report_id, payload_sha256)
);
CREATE INDEX ix_receipt_report ON intake_receipt (source, source_report_id, id);

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
