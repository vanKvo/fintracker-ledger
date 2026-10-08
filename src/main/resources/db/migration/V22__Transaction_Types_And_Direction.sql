-- =====================================================================
-- V22__Transaction_Types_And_Direction.sql
-- Managed by Flyway. DO NOT edit directly — create a new migration instead.
--
-- TXT-01 (transaction-types spec, Appendix A): five explicit types replace PURCHASE/CREDIT, which
-- mixed money direction with intent. Direction now has its own column.
--
-- Schema only. Rows created before this migration (PURCHASE/CREDIT, no direction) must be removed
-- first with scripts/one-time/2026-10-08_TXT-01_remove_pre_txt_transactions.sql.
-- =====================================================================
ALTER TABLE ledger.transactions DROP CONSTRAINT transactions_type_check;

ALTER TABLE ledger.transactions
    ADD COLUMN direction             VARCHAR(10) NOT NULL,
    ADD COLUMN currency              CHAR(3) NOT NULL DEFAULT 'USD',
    ADD COLUMN is_recurring          BOOLEAN,
    -- SET NULL: deleting an original expense must not delete the refund that points at it.
    ADD COLUMN linked_transaction_id UUID REFERENCES ledger.transactions(transaction_id) ON DELETE SET NULL;

ALTER TABLE ledger.transactions ADD CONSTRAINT transactions_type_check
    CHECK (type IN ('EXPENSE', 'INCOME', 'REFUND', 'TRANSFER', 'ADJUSTMENT'));

ALTER TABLE ledger.transactions ADD CONSTRAINT transactions_direction_check
    CHECK (direction IN ('DEBIT', 'CREDIT'));
