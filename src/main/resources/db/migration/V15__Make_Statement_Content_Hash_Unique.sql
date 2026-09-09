-- =====================================================================
-- V15__Make_Statement_Content_Hash_Unique.sql
-- Managed by Flyway. DO NOT edit directly — create a new migration instead.
--
-- REQ-STMT-03: close the check-then-insert race on exact-file duplicates.
--
-- initiateUpload runs its duplicate check and its INSERT as separate
-- statements (no surrounding transaction), so two concurrent uploads of the
-- same file can BOTH pass the check before either inserts. V13's
-- idx_statements_account_content_hash was non-unique, so both rows would be
-- stored and REQ-STMT-03 violated silently. Making the index unique turns the
-- race into a hard constraint violation for the loser (Postgres blocks the
-- second insert until the winner commits, then raises unique_violation),
-- which StatementServiceImpl catches and translates into the contractual 409
-- by re-running the duplicate lookup against the now-visible winner.
--
-- This is the same backstop the same-month check already had via
-- idx_unique_account_statement_month (V1, recreated by V14); V15 gives the
-- exact-file check equal footing rather than relying on the pre-check alone.
--
-- The index stays partial (WHERE content_hash IS NOT NULL): rows predating
-- V13 have nothing to deduplicate, and Postgres unique indexes already treat
-- NULLs as distinct, but keeping the predicate preserves V13's intent of not
-- indexing rows that can never participate in the check.
-- =====================================================================
DROP INDEX IF EXISTS ledger.idx_statements_account_content_hash;

CREATE UNIQUE INDEX idx_unique_account_content_hash
    ON ledger.statements(account_id, content_hash) WHERE content_hash IS NOT NULL;
