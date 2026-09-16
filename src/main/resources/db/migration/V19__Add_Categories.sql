-- =====================================================================
-- V19__Add_Categories.sql
-- Managed by Flyway. DO NOT edit directly — create a new migration instead.
--
-- REQ-TS-01: user-created custom categories, alongside a fixed set of system-level
-- categories (previously only the Java-side TransactionCategory enum). See
-- docs/fintracker-ledger-doc/ledger-transaction-spec-01.md.
--
-- Scope note: this migration adds ledger.categories and a NEW, nullable
-- transactions.category_id column, and backfills it for existing rows. It deliberately
-- does NOT drop transactions.category (the existing free-text column) or touch
-- budget_lines/budget_template_lines — REQ-TS-01 item #8 (Budget matching by categoryId)
-- and the full Transaction DTO migration are a separate, larger follow-up (see the
-- "Known scope boundary" note in ledger-transaction-tests-01.md).
-- =====================================================================

CREATE TABLE ledger.categories (
    category_id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    category_name VARCHAR(100) NOT NULL,
    level         VARCHAR(6)   NOT NULL CHECK (level IN ('SYSTEM', 'USER')),
    -- NULL for SYSTEM rows, required for USER rows — no FK to a "users" table because
    -- identity is owned by the User Profile Service, not the Ledger's own schema (same
    -- reasoning as every other bare user_id column in this schema, e.g. accounts.user_id).
    user_id       UUID NULL,
    CHECK ((level = 'SYSTEM' AND user_id IS NULL) OR (level = 'USER' AND user_id IS NOT NULL))
);

-- Requested Changes #5 (name collisions), enforced at the data layer: one SYSTEM row per
-- normalized name, and one USER row per (user, normalized name) — category_name always
-- stores the normalized form (see Requested Changes #3), never the display form.
CREATE UNIQUE INDEX uq_categories_system_name
    ON ledger.categories (category_name) WHERE level = 'SYSTEM';
CREATE UNIQUE INDEX uq_categories_user_name
    ON ledger.categories (user_id, category_name) WHERE level = 'USER';

-- Row-Level Security, consistent with every other ledger table (V3) — with one addition:
-- SYSTEM rows (user_id IS NULL) must be visible to every session, not just their "owner"
-- (they have none), per Requested Changes #1 ("system-level categories, which is always
-- available for the user").
ALTER TABLE ledger.categories ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.categories FORCE ROW LEVEL SECURITY;

CREATE POLICY categories_isolation ON ledger.categories
    USING (level = 'SYSTEM' OR user_id = current_setting('app.current_user_id', true)::uuid);

-- Seed the 17 SYSTEM categories that TransactionCategory.java previously hardcoded as the
-- only categories available. TransactionCategory.java becomes dead code once the
-- application layer reads from this table instead and should be removed at that point.
INSERT INTO ledger.categories (category_name, level, user_id) VALUES
    ('groceries', 'SYSTEM', NULL),
    ('dining', 'SYSTEM', NULL),
    ('transportation', 'SYSTEM', NULL),
    ('shopping', 'SYSTEM', NULL),
    ('entertainment', 'SYSTEM', NULL),
    ('utilities', 'SYSTEM', NULL),
    ('housing', 'SYSTEM', NULL),
    ('healthcare', 'SYSTEM', NULL),
    ('insurance', 'SYSTEM', NULL),
    ('subscriptions', 'SYSTEM', NULL),
    ('travel', 'SYSTEM', NULL),
    ('education', 'SYSTEM', NULL),
    ('personal_care', 'SYSTEM', NULL),
    ('income', 'SYSTEM', NULL),
    ('transfer', 'SYSTEM', NULL),
    ('fees', 'SYSTEM', NULL),
    ('others', 'SYSTEM', NULL);

-- transactions.category_id: nullable for now (see scope note above) — populated going
-- forward via CategoryService/TransactionRepository.reassignCategory, and backfilled here
-- for existing rows by matching each row's free-text category (case-insensitively) to the
-- seeded SYSTEM row of the same name, falling back to OTHERS for anything unrecognized —
-- mirroring TransactionCategory.resolve()'s existing fallback behavior exactly.
ALTER TABLE ledger.transactions
    ADD COLUMN category_id UUID REFERENCES ledger.categories(category_id) ON DELETE RESTRICT;

UPDATE ledger.transactions t
   SET category_id = COALESCE(
       (SELECT c.category_id FROM ledger.categories c
         WHERE c.level = 'SYSTEM' AND c.category_name = LOWER(REPLACE(TRIM(t.category), ' ', '_'))),
       (SELECT c.category_id FROM ledger.categories c
         WHERE c.level = 'SYSTEM' AND c.category_name = 'others'));

CREATE INDEX idx_tx_category_id ON ledger.transactions(category_id);
