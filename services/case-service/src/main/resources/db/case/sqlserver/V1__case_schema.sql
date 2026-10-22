-- case-service schema (SQL Server). Mirrors db/migration/oracle/V1__case_schema.sql.
-- Free text uses a UTF-8 collation so VARCHAR columns keep any language.
-- A case head points at append-only versions; every change is audited; AI suggestions and reviewer
-- choices are kept side by side (review_decision doubles as the feedback table for retraining).

CREATE TABLE safety_case (
    id                  BIGINT IDENTITY(1,1) PRIMARY KEY,
    case_number         VARCHAR(32) NOT NULL CONSTRAINT uq_case_number UNIQUE,
    source              VARCHAR(16) NOT NULL,
    source_report_id    VARCHAR(100) NOT NULL,
    status              VARCHAR(24) NOT NULL,
    seriousness         VARCHAR(16) NOT NULL,
    serious_score       DECIMAL(6,5),
    received_date       DATE NOT NULL,
    due_date            DATE NOT NULL,
    current_version     INT NOT NULL,
    duplicate_of_id     BIGINT REFERENCES safety_case (id),
    duplicate_score     DECIMAL(6,5),
    lock_version        INT NOT NULL,
    created_at          DATETIME2(6) NOT NULL,
    updated_at          DATETIME2(6) NOT NULL,
    CONSTRAINT uq_case_source UNIQUE (source, source_report_id)
);
CREATE INDEX ix_case_queue ON safety_case (status, due_date);

CREATE TABLE case_version (
    id              BIGINT IDENTITY(1,1) PRIMARY KEY,
    case_id         BIGINT NOT NULL REFERENCES safety_case (id),
    version_no      INT NOT NULL,
    created_by      VARCHAR(64) NOT NULL,
    created_at      DATETIME2(6) NOT NULL,
    reason          VARCHAR(200) NOT NULL,
    patient_age     DECIMAL(5,1),
    patient_sex     VARCHAR(8),
    reporter_type   VARCHAR(32),
    country         VARCHAR(2),
    onset_date      DATE,
    reported_text   VARCHAR(4000) COLLATE Latin1_General_100_CI_AS_SC_UTF8 NOT NULL,
    serious_criteria VARCHAR(200),
    CONSTRAINT uq_case_version UNIQUE (case_id, version_no)
);

CREATE TABLE case_drug (
    id          BIGINT IDENTITY(1,1) PRIMARY KEY,
    version_id  BIGINT NOT NULL REFERENCES case_version (id),
    rxcui       VARCHAR(16),
    drug_name   VARCHAR(200) COLLATE Latin1_General_100_CI_AS_SC_UTF8 NOT NULL,
    role        VARCHAR(4) NOT NULL,
    dose_text   VARCHAR(200),
    route       VARCHAR(64)
);

CREATE TABLE case_reaction (
    id             BIGINT IDENTITY(1,1) PRIMARY KEY,
    version_id     BIGINT NOT NULL REFERENCES case_version (id),
    reported_term  VARCHAR(400) COLLATE Latin1_General_100_CI_AS_SC_UTF8 NOT NULL,
    pt             VARCHAR(200),
    code_source    VARCHAR(16) NOT NULL,
    model_name     VARCHAR(100),
    model_version  VARCHAR(64),
    model_score    DECIMAL(6,5)
);

CREATE TABLE narrative (
    id           BIGINT IDENTITY(1,1) PRIMARY KEY,
    case_id      BIGINT NOT NULL REFERENCES safety_case (id),
    version_no   INT NOT NULL,
    body         VARCHAR(4000) COLLATE Latin1_General_100_CI_AS_SC_UTF8 NOT NULL,
    source       VARCHAR(16) NOT NULL,
    model_name   VARCHAR(100),
    created_by   VARCHAR(64) NOT NULL,
    created_at   DATETIME2(6) NOT NULL,
    approved_by  VARCHAR(64),
    approved_at  DATETIME2(6)
);

CREATE TABLE audit_event (
    id        BIGINT IDENTITY(1,1) PRIMARY KEY,
    case_id   BIGINT NOT NULL REFERENCES safety_case (id),
    actor     VARCHAR(64) NOT NULL,
    action    VARCHAR(48) NOT NULL,
    detail    VARCHAR(2000) COLLATE Latin1_General_100_CI_AS_SC_UTF8,
    occurred_at DATETIME2(6) NOT NULL
);
CREATE INDEX ix_audit_case ON audit_event (case_id, occurred_at);

CREATE TABLE review_decision (
    id               BIGINT IDENTITY(1,1) PRIMARY KEY,
    case_id          BIGINT NOT NULL REFERENCES safety_case (id),
    version_no       INT NOT NULL,
    reviewer         VARCHAR(64) NOT NULL,
    field            VARCHAR(24) NOT NULL,
    item_ref         VARCHAR(400),
    model_suggestion VARCHAR(400),
    model_name       VARCHAR(100),
    model_version    VARCHAR(64),
    model_score      DECIMAL(6,5),
    final_value      VARCHAR(400),
    accepted         BIT NOT NULL,
    decided_at       DATETIME2(6) NOT NULL
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
