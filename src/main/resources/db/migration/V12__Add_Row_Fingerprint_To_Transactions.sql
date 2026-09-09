-- =====================================================================
-- V12__Add_Row_Fingerprint_To_Transactions.sql
-- Managed by Flyway. DO NOT edit directly — create a new migration instead.
--
-- REQ-STMT-02: idempotency key for statement-uploaded transactions.
-- row_fingerprint is a hash of the row's own date/merchant/amount, computed
-- by the data-pipeline before dispatching the bulk-create call. A retried
-- batch (network hiccup, Lambda redrive) targets this index with
-- INSERT ... ON CONFLICT (statement_id, row_fingerprint) DO NOTHING and is a
-- safe no-op for every row already recorded, rather than an error.
--
-- Nullable: manual/bank-sync rows have no fingerprint. The index is partial
-- (WHERE row_fingerprint IS NOT NULL) so nulls neither bloat the index nor
-- collide with each other.
-- =====================================================================
ALTER TABLE ledger.transactions ADD COLUMN row_fingerprint CHAR(64);

CREATE UNIQUE INDEX idx_unique_statement_row_fingerprint
    ON ledger.transactions(statement_id, row_fingerprint)
    WHERE row_fingerprint IS NOT NULL;
