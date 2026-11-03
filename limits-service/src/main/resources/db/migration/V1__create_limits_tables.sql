-- Limits service database. Owned by this service only. Account and transfer IDs from other
-- services are stored as plain values, never as foreign keys to another service's database.

-- How much each account has committed to send on each day (UTC). This row is the contended
-- resource: every reservation for the account changes it with ONE conditional UPDATE, so two
-- transfers can never both squeeze under the limit.
CREATE TABLE daily_usage (
    account_id VARCHAR(64)    NOT NULL,
    usage_day  DATE           NOT NULL,
    used       NUMERIC(19, 2) NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (account_id, usage_day),
    CONSTRAINT chk_usage_not_negative CHECK (used >= 0)
);

-- One reservation per transfer. The primary key on transfer_id makes "reserve" safe to repeat:
-- a second request for the same transfer cannot reserve the amount twice.
CREATE TABLE limit_reservations (
    transfer_id VARCHAR(64)    PRIMARY KEY,
    account_id  VARCHAR(64)    NOT NULL,
    amount      NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency    VARCHAR(3)     NOT NULL,
    usage_day   DATE           NOT NULL,
    status      VARCHAR(20)    NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    released_at TIMESTAMP WITH TIME ZONE,
    version     BIGINT         NOT NULL,
    CONSTRAINT chk_reservations_status CHECK (status IN ('RESERVED', 'RELEASED'))
);

-- The velocity (fraud) rule counts an account's recent reservations
CREATE INDEX idx_reservations_account_created ON limit_reservations (account_id, created_at);

-- Optional per-account limits that replace the defaults (e.g. a customer with a lower limit)
CREATE TABLE account_limits (
    account_id         VARCHAR(64)    PRIMARY KEY,
    daily_limit        NUMERIC(19, 2) NOT NULL CHECK (daily_limit > 0),
    per_transfer_limit NUMERIC(19, 2) NOT NULL CHECK (per_transfer_limit > 0),
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    version            BIGINT         NOT NULL,
    CONSTRAINT chk_limits_per_transfer_le_daily CHECK (per_transfer_limit <= daily_limit)
);
