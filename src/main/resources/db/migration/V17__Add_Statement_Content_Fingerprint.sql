-- =====================================================================
-- V17__Add_Statement_Content_Fingerprint.sql
-- Managed by Flyway. DO NOT edit directly — create a new migration instead.
--
-- REQ-STMT-04: an aggregate fingerprint of a statement's whole transaction
-- set, computed by the data-pipeline once the file has been fully read and
-- stored here so the NEXT upload can be recognized as the same real-world
-- statement even when the bytes differ (a corrected re-export, or the same
-- period downloaded once as CSV and once as PDF).
--
-- One combined value rather than per-row hashes: comparison stays a single
-- indexed lookup, the same shape as V13's content_hash.
--
-- Scoped per account (the index's leading column), matching REQ-STMT-04's
-- rule that a statement is only ever compared against its own account's
-- prior uploads.
--
-- Deliberately NOT unique, unlike V15's content_hash index. REQ-STMT-04 says
-- a fingerprint match is "probably the same, please confirm", not a hard
-- block — two genuinely different statements on a low-activity account can
-- legitimately collide, and a unique index would refuse the second one at
-- the database with no way for the user to override it.
--
-- Partial (WHERE content_fingerprint IS NOT NULL): the value only exists
-- after the pipeline has read the file, so every row starts NULL and rows
-- that never complete processing stay that way.
-- =====================================================================
ALTER TABLE ledger.statements ADD COLUMN content_fingerprint CHAR(64);

CREATE INDEX idx_statements_account_content_fingerprint
    ON ledger.statements(account_id, content_fingerprint) WHERE content_fingerprint IS NOT NULL;
