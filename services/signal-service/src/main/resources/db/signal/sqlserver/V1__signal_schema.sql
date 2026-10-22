-- signal-service schema (SQL Server); see the Oracle migration for the design.
CREATE TABLE signal_snapshot (
    id             BIGINT IDENTITY(1,1) PRIMARY KEY,
    scope          NVARCHAR(200) NOT NULL,
    source_sha256  VARCHAR(64) NOT NULL CONSTRAINT uq_snapshot_source UNIQUE,
    pairs          INT NOT NULL,
    with_sf        BIT NOT NULL,
    evaluation     NVARCHAR(MAX),
    loaded_at      DATETIME2(6) NOT NULL
);

CREATE TABLE drug_event_signal (
    snapshot_id   BIGINT NOT NULL CONSTRAINT fk_signal_snapshot REFERENCES signal_snapshot (id),
    rxcui         VARCHAR(16) NOT NULL,
    ingredient    NVARCHAR(200),
    pt            NVARCHAR(200) NOT NULL,
    a             INT NOT NULL,
    n_drug        INT,
    n_event       INT,
    n_total       INT,
    prr           FLOAT,
    prr_lo        FLOAT,
    prr_hi        FLOAT,
    ror           FLOAT,
    ror_lo        FLOAT,
    ror_hi        FLOAT,
    ic            FLOAT,
    ic025         FLOAT,
    ic975         FLOAT,
    ebgm          FLOAT,
    eb05          FLOAT,
    eb95          FLOAT,
    safetyformer  FLOAT,
    CONSTRAINT pk_drug_event_signal PRIMARY KEY (snapshot_id, rxcui, pt)
);
CREATE INDEX ix_signal_eb05 ON drug_event_signal (snapshot_id, eb05);

CREATE TABLE signal_trend (
    snapshot_id  BIGINT NOT NULL CONSTRAINT fk_trend_snapshot REFERENCES signal_snapshot (id),
    rxcui        VARCHAR(16) NOT NULL,
    pt           NVARCHAR(200) NOT NULL,
    year         INT NOT NULL,
    reports      INT NOT NULL,
    CONSTRAINT pk_signal_trend PRIMARY KEY (snapshot_id, rxcui, pt, year)
);
