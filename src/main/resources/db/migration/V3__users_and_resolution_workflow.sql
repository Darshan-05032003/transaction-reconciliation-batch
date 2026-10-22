-- Operations users for the HTTP API. Password hashes carry Spring Security's
-- {bcrypt} id prefix so that the algorithm can be rotated without a migration.
CREATE TABLE app_user (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uk_app_user_username UNIQUE (username)
);

-- A user may hold several roles, so roles are rows rather than a column.
CREATE TABLE app_user_role (
    user_id BIGINT      NOT NULL,
    role    VARCHAR(20) NOT NULL,
    CONSTRAINT pk_app_user_role PRIMARY KEY (user_id, role),
    CONSTRAINT fk_app_user_role_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE,
    CONSTRAINT ck_app_user_role CHECK (role IN ('VIEWER', 'OPERATOR', 'APPROVER'))
);

-- Severity is decided by the matching rules (a value-date drift inside the tolerance is a
-- warning); storing it keeps reports and the API from parsing it out of the details text.
ALTER TABLE recon_break ADD COLUMN severity VARCHAR(10) NOT NULL DEFAULT 'ERROR';
ALTER TABLE recon_break ADD CONSTRAINT ck_recon_break_severity CHECK (severity IN ('WARNING', 'ERROR'));

-- Maker/approver workflow: a write-off above the configured threshold is requested by an
-- operator and confirmed by a different user with the APPROVER role. The version column
-- backs optimistic locking so that two operators cannot both resolve the same break.
ALTER TABLE recon_break ADD COLUMN requested_by VARCHAR(100);
ALTER TABLE recon_break ADD COLUMN requested_at TIMESTAMPTZ;
ALTER TABLE recon_break ADD COLUMN approved_by VARCHAR(100);
ALTER TABLE recon_break ADD COLUMN approved_at TIMESTAMPTZ;
ALTER TABLE recon_break ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE recon_break DROP CONSTRAINT ck_recon_break_status;
ALTER TABLE recon_break ADD CONSTRAINT ck_recon_break_status
    CHECK (status IN ('OPEN', 'PENDING_APPROVAL', 'RESOLVED', 'WRITTEN_OFF'));

ALTER TABLE recon_break DROP CONSTRAINT ck_recon_break_resolution;
ALTER TABLE recon_break ADD CONSTRAINT ck_recon_break_resolution CHECK (
    (status = 'OPEN'
        AND resolved_by IS NULL AND resolved_at IS NULL
        AND requested_by IS NULL AND requested_at IS NULL
        AND approved_by IS NULL AND approved_at IS NULL)
    OR (status = 'PENDING_APPROVAL'
        AND requested_by IS NOT NULL AND requested_at IS NOT NULL
        AND resolved_by IS NULL AND resolved_at IS NULL
        AND approved_by IS NULL AND approved_at IS NULL)
    OR (status IN ('RESOLVED', 'WRITTEN_OFF')
        AND resolved_by IS NOT NULL AND resolved_at IS NOT NULL
        AND ((requested_by IS NULL AND approved_by IS NULL)
            OR (status = 'WRITTEN_OFF' AND requested_by IS NOT NULL AND requested_at IS NOT NULL
                AND approved_by IS NOT NULL AND approved_at IS NOT NULL)))
);

-- Append-only history of every resolution action: the break row holds the current state,
-- this table holds who did what, when, and why, including rejected requests.
CREATE TABLE recon_break_action (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    break_id     BIGINT       NOT NULL,
    action       VARCHAR(30)  NOT NULL,
    performed_by VARCHAR(100) NOT NULL,
    performed_at TIMESTAMPTZ  NOT NULL,
    comment      VARCHAR(500) NOT NULL,
    CONSTRAINT fk_recon_break_action_break FOREIGN KEY (break_id) REFERENCES recon_break (id),
    CONSTRAINT ck_recon_break_action CHECK (action IN (
        'RESOLVE', 'WRITE_OFF', 'REQUEST_WRITE_OFF', 'APPROVE_WRITE_OFF', 'REJECT_WRITE_OFF'))
);

CREATE INDEX ix_recon_break_action_break ON recon_break_action (break_id);
