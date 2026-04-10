-- =====================================================================
-- V1__Initial_Schema.sql
-- Managed by Flyway. DO NOT edit directly — create a new migration instead.
-- =====================================================================
CREATE SCHEMA IF NOT EXISTS ledger;

-- ==========================================
-- TABLE: accounts
-- ==========================================
CREATE TABLE ledger.accounts (
    account_id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID         NOT NULL,
    account_name VARCHAR(100) NOT NULL,
    account_type VARCHAR(50)  NOT NULL CHECK (account_type IN ('CHECKING', 'SAVINGS', 'CREDIT')),
    current_balance DECIMAL(15, 2) NOT NULL DEFAULT 0.00,
    sync_mode    VARCHAR(20)  NOT NULL CHECK (sync_mode IN ('MANUAL', 'AUTOMATED')),
    last_watermark_date TIMESTAMP WITH TIME ZONE,
    created_at   TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- ==========================================
-- TABLE: statements
-- ==========================================
CREATE TABLE ledger.statements (
    statement_id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id     UUID NOT NULL REFERENCES ledger.accounts(account_id) ON DELETE CASCADE,
    s3_object_key  VARCHAR(1024) NOT NULL,
    statement_month DATE NOT NULL,
    status         VARCHAR(50) NOT NULL CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED')),
    upload_date    TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_unique_account_statement_month
    ON ledger.statements(account_id, statement_month);

-- ==========================================
-- TABLE: transactions
-- ==========================================
CREATE TABLE ledger.transactions (
    transaction_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id            UUID NOT NULL REFERENCES ledger.accounts(account_id) ON DELETE CASCADE,
    statement_id          UUID REFERENCES ledger.statements(statement_id) ON DELETE SET NULL,
    parent_transaction_id UUID REFERENCES ledger.transactions(transaction_id) ON DELETE CASCADE,
    external_tx_id        VARCHAR(255),
    amount                DECIMAL(15, 2) NOT NULL CHECK (amount != 0),
    merchant              VARCHAR(255)   NOT NULL,
    category              VARCHAR(100)   NOT NULL,
    tags                  TEXT[],
    tx_date               DATE           NOT NULL,
    source                VARCHAR(50)    NOT NULL CHECK (source IN ('STATEMENT_UPLOAD', 'TELLER_SYNC', 'MANUAL_ENTRY')),
    type                  VARCHAR(50)    NOT NULL CHECK (type IN ('SALE', 'RETURN')),
    status                VARCHAR(50)    NOT NULL CHECK (status IN ('PENDING_APPROVAL', 'POSTED', 'DELETED')),
    is_excluded           BOOLEAN        NOT NULL DEFAULT FALSE,
    is_manual             BOOLEAN GENERATED ALWAYS AS (source = 'MANUAL_ENTRY') STORED,
    created_at            TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_unique_external_tx
    ON ledger.transactions(account_id, external_tx_id) WHERE external_tx_id IS NOT NULL;
CREATE INDEX idx_tx_account_date     ON ledger.transactions(account_id, tx_date DESC);
CREATE INDEX idx_tx_status_date      ON ledger.transactions(status, tx_date) WHERE status = 'POSTED';
CREATE INDEX idx_tx_tags             ON ledger.transactions USING GIN (tags);
CREATE INDEX idx_tx_statement_status ON ledger.transactions(statement_id, status);

-- ==========================================
-- TABLE: budgets & budget_lines
-- ==========================================
CREATE TABLE ledger.budgets (
    budget_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID NOT NULL,
    effective_month DATE NOT NULL,
    version        INT  DEFAULT 1,
    created_at     TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (user_id, effective_month, version)
);

CREATE TABLE ledger.budget_lines (
    line_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    budget_id      UUID NOT NULL REFERENCES ledger.budgets(budget_id) ON DELETE CASCADE,
    category       VARCHAR(100)   NOT NULL,
    limit_amount   DECIMAL(15, 2) NOT NULL CHECK (limit_amount >= 0),
    UNIQUE (budget_id, category)
);

-- ==========================================
-- TABLE: upcoming_bills
-- ==========================================
CREATE TABLE ledger.upcoming_bills (
    bill_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL,
    name         VARCHAR(100)   NOT NULL,
    amount       DECIMAL(15, 2) NOT NULL,
    due_date_day INT            NOT NULL CHECK (due_date_day BETWEEN 1 AND 31),
    category     VARCHAR(100),
    status       VARCHAR(50)    NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'PAUSED')),
    created_at   TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_upcoming_bills_user ON ledger.upcoming_bills(user_id) WHERE status = 'ACTIVE';

-- ==========================================
-- TABLE: bill_payments
-- ==========================================
CREATE TABLE ledger.bill_payments (
    payment_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bill_id         UUID NOT NULL REFERENCES ledger.upcoming_bills(bill_id) ON DELETE CASCADE,
    paid_for_month  DATE NOT NULL,
    transaction_id  UUID REFERENCES ledger.transactions(transaction_id) ON DELETE SET NULL,
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (bill_id, paid_for_month)
);
