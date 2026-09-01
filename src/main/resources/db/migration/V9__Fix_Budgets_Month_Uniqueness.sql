-- =====================================================================
-- V9__Fix_Budgets_Month_Uniqueness.sql
-- Managed by Flyway. DO NOT edit directly — create a new migration instead.
--
-- Bug fix: the V1 unique constraint was (user_id, effective_month, version),
-- which does not enforce REQ-5.1 "one budget per user per month" — two rows
-- for the same month can coexist as long as their version differs. Worse, it
-- makes budget creation racy: two concurrent PUT /budgets requests for a
-- month with no existing budget both insert version=1, and the loser throws
-- DuplicateKeyException, which surfaces as an unhandled 500.
-- =====================================================================

ALTER TABLE ledger.budgets
    DROP CONSTRAINT budgets_user_id_effective_month_version_key;

ALTER TABLE ledger.budgets
    ADD CONSTRAINT budgets_user_id_effective_month_key UNIQUE (user_id, effective_month);
