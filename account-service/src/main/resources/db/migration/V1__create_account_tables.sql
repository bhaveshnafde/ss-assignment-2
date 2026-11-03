-- Account service database. Owned by this service only; transfers are referenced by
-- transfer_id, never by foreign key to another service.

CREATE TABLE accounts (
    id           VARCHAR(64)    PRIMARY KEY,
    holder_name  VARCHAR(100)   NOT NULL,
    currency     VARCHAR(3)     NOT NULL,
    -- money really in the account
    balance      NUMERIC(19, 2) NOT NULL,
    -- balance minus money on hold; what can still be spent
    available    NUMERIC(19, 2) NOT NULL,
    status       VARCHAR(20)    NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    version      BIGINT         NOT NULL,
    -- BR2 as a last line of defence: even buggy code cannot make these negative
    CONSTRAINT chk_accounts_balance_not_negative   CHECK (balance >= 0),
    CONSTRAINT chk_accounts_available_not_negative CHECK (available >= 0),
    CONSTRAINT chk_accounts_available_le_balance   CHECK (available <= balance)
);

-- One hold per transfer. The primary key on transfer_id makes "place hold" safe to repeat:
-- a second request for the same transfer cannot create a second hold.
CREATE TABLE holds (
    transfer_id            VARCHAR(64)    PRIMARY KEY,
    source_account_id      VARCHAR(64)    NOT NULL REFERENCES accounts (id),
    destination_account_id VARCHAR(64)    NOT NULL REFERENCES accounts (id),
    amount                 NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency               VARCHAR(3)     NOT NULL,
    status                 VARCHAR(20)    NOT NULL,
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    version                BIGINT         NOT NULL
);

CREATE INDEX idx_holds_source_status ON holds (source_account_id, status);
