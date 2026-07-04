-- V1__ledger_core.sql
-- The financial source of truth. Design notes:
--   * ledger_journal_entry + ledger_posting are append-only. A DB trigger blocks
--     UPDATE and DELETE so no application bug (or DBA slip) can rewrite history.
--   * (organization_id, idempotency_key) is UNIQUE: the database is the final
--     backstop for idempotency even if two app instances race past the read check.
--   * balances live on ledger_account with an optimistic-lock `version`.

CREATE TABLE ledger_account (
    id               UUID PRIMARY KEY,
    organization_id  UUID           NOT NULL,
    code             VARCHAR(128)   NOT NULL,
    type             VARCHAR(16)    NOT NULL CHECK (type IN ('ASSET','LIABILITY','EQUITY','REVENUE','EXPENSE')),
    asset            VARCHAR(16)    NOT NULL,
    allow_negative   BOOLEAN        NOT NULL DEFAULT FALSE,
    balance_amount   NUMERIC(38,8)  NOT NULL DEFAULT 0,
    version          BIGINT         NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uq_account_org_code UNIQUE (organization_id, code)
);

CREATE TABLE ledger_journal_entry (
    id                 UUID PRIMARY KEY,
    organization_id    UUID          NOT NULL,
    idempotency_key    VARCHAR(255)  NOT NULL,
    description        TEXT          NOT NULL,
    -- kind is fixed at creation; entries never change state. Whether an entry
    -- has been reversed is a query over reverses_entry_id, not a status flag.
    kind               VARCHAR(16)   NOT NULL CHECK (kind IN ('STANDARD','REVERSAL')),
    reverses_entry_id  UUID          REFERENCES ledger_journal_entry(id),
    effective_at       TIMESTAMPTZ   NOT NULL,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_entry_idempotency UNIQUE (organization_id, idempotency_key)
);

CREATE TABLE ledger_posting (
    id          UUID PRIMARY KEY,
    entry_id    UUID           NOT NULL REFERENCES ledger_journal_entry(id),
    account_id  UUID           NOT NULL REFERENCES ledger_account(id),
    direction   VARCHAR(8)     NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
    amount      NUMERIC(38,8)  NOT NULL CHECK (amount > 0),
    asset       VARCHAR(16)    NOT NULL
);

CREATE INDEX idx_posting_account ON ledger_posting(account_id);
CREATE INDEX idx_entry_org_effective ON ledger_journal_entry(organization_id, effective_at);

-- Append-only enforcement: history is immutable at the storage layer.
CREATE OR REPLACE FUNCTION reject_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger rows are immutable: % on % is forbidden', TG_OP, TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_entry_immutable
    BEFORE UPDATE OR DELETE ON ledger_journal_entry
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE TRIGGER trg_posting_immutable
    BEFORE UPDATE OR DELETE ON ledger_posting
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- Transactional outbox for reliable event relay to Kafka.
CREATE TABLE ledger_outbox (
    id              UUID PRIMARY KEY,
    aggregate_id    UUID          NOT NULL,
    organization_id UUID          NOT NULL,
    event_type      VARCHAR(64)   NOT NULL,
    payload         JSONB         NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ
);
CREATE INDEX idx_outbox_unpublished ON ledger_outbox(created_at) WHERE published_at IS NULL;
