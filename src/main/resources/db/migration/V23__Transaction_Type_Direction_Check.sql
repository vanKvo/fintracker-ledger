-- =====================================================================
-- V23__Transaction_Type_Direction_Check.sql
-- Managed by Flyway. DO NOT edit directly — create a new migration instead.
--
-- TXT-01: an EXPENSE is always money out; INCOME and REFUND are always money in.
-- TRANSFER and ADJUSTMENT may go either way.
-- =====================================================================
ALTER TABLE ledger.transactions ADD CONSTRAINT transactions_type_direction_check
    CHECK (
        (type <> 'EXPENSE' OR direction = 'DEBIT')
        AND (type NOT IN ('INCOME', 'REFUND') OR direction = 'CREDIT')
    );
