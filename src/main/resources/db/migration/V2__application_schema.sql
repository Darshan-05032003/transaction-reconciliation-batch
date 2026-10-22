-- Internal book of record: one row per transaction the bank itself has posted.
CREATE TABLE ledger_transaction (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    txn_reference  VARCHAR(64)    NOT NULL,
    account_number VARCHAR(34)    NOT NULL,
    amount         NUMERIC(19, 2) NOT NULL,
    currency       CHAR(3)        NOT NULL,
    booking_date   DATE           NOT NULL,
    value_date     DATE           NOT NULL,
    description    VARCHAR(255),
    created_at     TIMESTAMPTZ    NOT NULL,
    CONSTRAINT uk_ledger_transaction_reference UNIQUE (txn_reference),
    CONSTRAINT ck_ledger_transaction_currency CHECK (currency ~ '^[A-Z]{3}$')
);

-- Drives the "missing in bank" side of matching: all ledger rows for the statement's
-- account inside the statement's value-date window.
CREATE INDEX ix_ledger_transaction_account_value_date
    ON ledger_transaction (account_number, value_date);

-- One row per statement file received from a counterparty bank. Header fields are
-- nullable because they are only known once the header has been parsed and validated.
CREATE TABLE statement_file (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    file_name        VARCHAR(255)   NOT NULL,
    checksum_sha256  CHAR(64)       NOT NULL,
    bank_code        VARCHAR(20),
    account_number   VARCHAR(34),
    statement_date   DATE,
    declared_count   INTEGER,
    declared_total   NUMERIC(19, 2),
    status           VARCHAR(20)    NOT NULL,
    rejection_reason VARCHAR(500),
    received_at      TIMESTAMPTZ    NOT NULL,
    CONSTRAINT uk_statement_file_checksum UNIQUE (checksum_sha256),
    CONSTRAINT ck_statement_file_checksum_hex CHECK (checksum_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_statement_file_status
        CHECK (status IN ('RECEIVED', 'VALIDATED', 'REJECTED', 'RECONCILED', 'FAILED')),
    CONSTRAINT ck_statement_file_declared_count CHECK (declared_count IS NULL OR declared_count >= 0)
);

CREATE INDEX ix_statement_file_account_statement_date
    ON statement_file (account_number, statement_date);

-- Parsed data records of a statement file. Duplicated bank references are allowed on
-- purpose: detecting them is a reconciliation break, not an ingestion error.
CREATE TABLE statement_line (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    statement_file_id BIGINT         NOT NULL,
    line_number       INTEGER        NOT NULL,
    bank_reference    VARCHAR(64)    NOT NULL,
    account_number    VARCHAR(34)    NOT NULL,
    amount            NUMERIC(19, 2) NOT NULL,
    currency          CHAR(3)        NOT NULL,
    value_date        DATE           NOT NULL,
    description       VARCHAR(255),
    CONSTRAINT fk_statement_line_file FOREIGN KEY (statement_file_id) REFERENCES statement_file (id),
    CONSTRAINT uk_statement_line_file_line UNIQUE (statement_file_id, line_number),
    CONSTRAINT ck_statement_line_line_number CHECK (line_number > 0),
    CONSTRAINT ck_statement_line_currency CHECK (currency ~ '^[A-Z]{3}$')
);

-- Drives the reference join and the duplicate-reference check, both scoped to one file.
CREATE INDEX ix_statement_line_file_reference
    ON statement_line (statement_file_id, bank_reference);

-- One reconciliation attempt for a statement file. job_execution_id points at
-- batch_job_execution without a foreign key so that batch metadata can be purged
-- independently of business history.
CREATE TABLE recon_run (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    statement_file_id BIGINT      NOT NULL,
    job_execution_id  BIGINT,
    started_at        TIMESTAMPTZ NOT NULL,
    finished_at       TIMESTAMPTZ,
    status            VARCHAR(20) NOT NULL,
    matched_count     INTEGER     NOT NULL DEFAULT 0,
    break_count       INTEGER     NOT NULL DEFAULT 0,
    CONSTRAINT fk_recon_run_file FOREIGN KEY (statement_file_id) REFERENCES statement_file (id),
    CONSTRAINT ck_recon_run_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_recon_run_counts CHECK (matched_count >= 0 AND break_count >= 0),
    CONSTRAINT ck_recon_run_finished_after_started
        CHECK (finished_at IS NULL OR finished_at >= started_at)
);

CREATE INDEX ix_recon_run_statement_file ON recon_run (statement_file_id);
CREATE INDEX ix_recon_run_job_execution ON recon_run (job_execution_id);

-- Every difference found by a run. A break always has at least one side; a missing
-- side is exactly what MISSING_IN_BANK and MISSING_IN_LEDGER record.
CREATE TABLE recon_break (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    recon_run_id       BIGINT         NOT NULL,
    break_type         VARCHAR(30)    NOT NULL,
    ledger_txn_id      BIGINT,
    statement_line_id  BIGINT,
    ledger_amount      NUMERIC(19, 2),
    bank_amount        NUMERIC(19, 2),
    details            VARCHAR(500),
    status             VARCHAR(20)    NOT NULL DEFAULT 'OPEN',
    resolved_by        VARCHAR(100),
    resolved_at        TIMESTAMPTZ,
    resolution_comment VARCHAR(500),
    CONSTRAINT fk_recon_break_run FOREIGN KEY (recon_run_id) REFERENCES recon_run (id),
    CONSTRAINT fk_recon_break_ledger_txn FOREIGN KEY (ledger_txn_id) REFERENCES ledger_transaction (id),
    CONSTRAINT fk_recon_break_statement_line FOREIGN KEY (statement_line_id) REFERENCES statement_line (id),
    CONSTRAINT ck_recon_break_type CHECK (break_type IN (
        'AMOUNT_MISMATCH', 'VALUE_DATE_MISMATCH', 'MISSING_IN_BANK',
        'MISSING_IN_LEDGER', 'DUPLICATE_IN_STATEMENT')),
    CONSTRAINT ck_recon_break_status CHECK (status IN ('OPEN', 'RESOLVED', 'WRITTEN_OFF')),
    CONSTRAINT ck_recon_break_has_side CHECK (ledger_txn_id IS NOT NULL OR statement_line_id IS NOT NULL),
    CONSTRAINT ck_recon_break_resolution CHECK (
        (status = 'OPEN' AND resolved_by IS NULL AND resolved_at IS NULL)
        OR (status <> 'OPEN' AND resolved_by IS NOT NULL AND resolved_at IS NOT NULL))
);

-- Serves the operations views: breaks of a run filtered by type and status, paged.
CREATE INDEX ix_recon_break_run_type_status ON recon_break (recon_run_id, break_type, status);

-- Partial indexes on the nullable foreign keys: PostgreSQL does not index FK columns
-- automatically, and rows where the side is NULL would only bloat the index.
CREATE INDEX ix_recon_break_ledger_txn ON recon_break (ledger_txn_id) WHERE ledger_txn_id IS NOT NULL;
CREATE INDEX ix_recon_break_statement_line ON recon_break (statement_line_id) WHERE statement_line_id IS NOT NULL;
