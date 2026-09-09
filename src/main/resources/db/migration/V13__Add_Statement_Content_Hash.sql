-- =====================================================================
-- V13__Add_Statement_Content_Hash.sql
-- Managed by Flyway. DO NOT edit directly — create a new migration instead.
--
-- REQ-STMT-03: SHA-256 of the uploaded file's exact contents, sent by the
-- client at initiate-upload time and persisted here. This column is what
-- makes the NEXT upload's exact-file duplicate check possible at all.
--
-- Scoped per account (the index's leading column): the check only ever
-- compares a file against that same account's own previous uploads. Partial
-- (WHERE content_hash IS NOT NULL) because rows created before this column
-- existed have nothing to index.
-- =====================================================================
ALTER TABLE ledger.statements ADD COLUMN content_hash CHAR(64);

CREATE INDEX idx_statements_account_content_hash
    ON ledger.statements(account_id, content_hash) WHERE content_hash IS NOT NULL;
