-- pharmacy-service schema (SQL Server). Mirrors db/migration/oracle/V1__pharmacy_schema.sql.
-- Free text uses a UTF-8 collation so VARCHAR columns keep any language.

CREATE TABLE patient (
    id            BIGINT IDENTITY(1,1) PRIMARY KEY,
    synthetic_id  VARCHAR(64) NOT NULL CONSTRAINT uq_patient_synthetic UNIQUE,
    given_name    VARCHAR(100) COLLATE Latin1_General_100_CI_AS_SC_UTF8 NOT NULL,
    family_name   VARCHAR(100) COLLATE Latin1_General_100_CI_AS_SC_UTF8 NOT NULL,
    birth_date    DATE NOT NULL,
    sex           VARCHAR(8) NOT NULL,
    synthetic     BIT NOT NULL DEFAULT 1
);

CREATE TABLE app_user (
    id            BIGINT IDENTITY(1,1) PRIMARY KEY,
    username      VARCHAR(64) NOT NULL CONSTRAINT uq_app_user_username UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(32) NOT NULL,
    display_name  VARCHAR(100) COLLATE Latin1_General_100_CI_AS_SC_UTF8 NOT NULL,
    patient_id    BIGINT REFERENCES patient (id),
    active        BIT NOT NULL DEFAULT 1
);

CREATE TABLE prescriber (
    id            BIGINT IDENTITY(1,1) PRIMARY KEY,
    code          VARCHAR(32) NOT NULL CONSTRAINT uq_prescriber_code UNIQUE,
    name          VARCHAR(200) COLLATE Latin1_General_100_CI_AS_SC_UTF8 NOT NULL
);

CREATE TABLE drug (
    id            BIGINT IDENTITY(1,1) PRIMARY KEY,
    product_code  VARCHAR(32) NOT NULL CONSTRAINT uq_drug_product UNIQUE,
    ndc           VARCHAR(16),
    rxcui         VARCHAR(16) NOT NULL,
    name          VARCHAR(200) COLLATE Latin1_General_100_CI_AS_SC_UTF8 NOT NULL,
    strength      VARCHAR(64),
    dose_form     VARCHAR(64),
    rx_only       BIT NOT NULL,
    unit_price_cents INT NOT NULL CONSTRAINT ck_drug_price CHECK (unit_price_cents >= 0)
);

CREATE TABLE lot (
    id            BIGINT IDENTITY(1,1) PRIMARY KEY,
    drug_id       BIGINT NOT NULL REFERENCES drug (id),
    lot_number    VARCHAR(64) NOT NULL,
    expiry_date   DATE NOT NULL,
    quantity      INT NOT NULL CONSTRAINT ck_lot_quantity CHECK (quantity >= 0),
    received_at   DATETIME2(6) NOT NULL,
    CONSTRAINT uq_lot UNIQUE (drug_id, lot_number)
);
CREATE INDEX ix_lot_fefo ON lot (drug_id, expiry_date);

CREATE TABLE prescription (
    id                 BIGINT IDENTITY(1,1) PRIMARY KEY,
    patient_id         BIGINT NOT NULL REFERENCES patient (id),
    prescriber_id      BIGINT NOT NULL REFERENCES prescriber (id),
    drug_id            BIGINT NOT NULL REFERENCES drug (id),
    written_date       DATE NOT NULL,
    quantity           INT NOT NULL CONSTRAINT ck_rx_quantity CHECK (quantity > 0),
    remaining_quantity INT NOT NULL CONSTRAINT ck_rx_remaining CHECK (remaining_quantity >= 0),
    status             VARCHAR(16) NOT NULL
);

CREATE TABLE dispense_order (
    id              BIGINT IDENTITY(1,1) PRIMARY KEY,
    patient_id      BIGINT NOT NULL REFERENCES patient (id),
    prescription_id BIGINT REFERENCES prescription (id),
    drug_id         BIGINT NOT NULL REFERENCES drug (id),
    quantity        INT NOT NULL CONSTRAINT ck_order_quantity CHECK (quantity > 0),
    total_cents     BIGINT NOT NULL,
    pharmacist      VARCHAR(64) NOT NULL,
    created_at      DATETIME2(6) NOT NULL
);

CREATE TABLE dispense_line (
    id        BIGINT IDENTITY(1,1) PRIMARY KEY,
    order_id  BIGINT NOT NULL REFERENCES dispense_order (id),
    lot_id    BIGINT NOT NULL REFERENCES lot (id),
    quantity  INT NOT NULL CONSTRAINT ck_line_quantity CHECK (quantity > 0)
);

CREATE TABLE side_effect_report (
    id                 BIGINT IDENTITY(1,1) PRIMARY KEY,
    patient_id         BIGINT NOT NULL REFERENCES patient (id),
    drug_id            BIGINT NOT NULL REFERENCES drug (id),
    reporter           VARCHAR(64) NOT NULL,
    narrative          VARCHAR(4000) COLLATE Latin1_General_100_CI_AS_SC_UTF8 NOT NULL,
    onset_date         DATE,
    status             VARCHAR(24) NOT NULL,
    external_report_id VARCHAR(64),
    created_at         DATETIME2(6) NOT NULL
);
