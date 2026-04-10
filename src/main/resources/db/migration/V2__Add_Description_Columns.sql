-- =====================================================================
-- V2__Add_Description_Columns.sql
-- Managed by Flyway.
-- =====================================================================

ALTER TABLE ledger.statements ADD COLUMN description VARCHAR(500);
ALTER TABLE ledger.transactions ADD COLUMN description VARCHAR(500);
ALTER TABLE ledger.budgets ADD COLUMN description VARCHAR(500);
ALTER TABLE ledger.budget_lines ADD COLUMN description VARCHAR(500);
ALTER TABLE ledger.upcoming_bills ADD COLUMN description VARCHAR(500);
