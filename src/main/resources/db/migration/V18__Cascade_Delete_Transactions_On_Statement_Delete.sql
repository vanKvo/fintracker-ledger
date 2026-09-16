-- =====================================================================
-- V18__Cascade_Delete_Transactions_On_Statement_Delete.sql
-- Managed by Flyway. DO NOT edit directly — create a new migration instead.
--
-- REQ-STMT-05: fixes a pre-existing bug, not just a gap for the new feature.
--
-- V1 declared transactions.statement_id as ON DELETE SET NULL. Deleting a
-- statement therefore ORPHANED its transactions — statement_id became NULL
-- and the rows stayed in the account, still counted in balances and budgets —
-- even though StatementServiceImpl.deleteStatement has always logged
-- "Cascaded transactions removed". Today's manual statement delete is
-- affected by this too, independently of anything REQ-STMT-05 adds.
--
-- REQ-STMT-05's overwrite is defined as a clean replacement: the old
-- statement "and everything that came from it" is removed, then the new file
-- is processed as if it were the first upload. Under SET NULL, an overwrite
-- would silently double every transaction — the old orphaned rows plus the
-- freshly imported ones.
--
-- Enforced by the database rather than by an application-level
-- delete-children-then-parent step: a DB cascade is atomic and cannot be
-- bypassed by a future code path that deletes a statement without
-- remembering its transactions.
-- =====================================================================
ALTER TABLE ledger.transactions DROP CONSTRAINT transactions_statement_id_fkey;

ALTER TABLE ledger.transactions
    ADD CONSTRAINT transactions_statement_id_fkey
    FOREIGN KEY (statement_id) REFERENCES ledger.statements(statement_id) ON DELETE CASCADE;
