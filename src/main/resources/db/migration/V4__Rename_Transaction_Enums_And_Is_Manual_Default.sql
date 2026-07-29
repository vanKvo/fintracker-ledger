-- is_manual was previously always derived from source = 'MANUAL_ENTRY'. Going forward it
-- represents "created via the user-facing manual Add Transaction action" specifically, which
-- is a narrower condition than "source happens to be MANUAL_ENTRY" — so it can no longer be a
-- computed column. DROP EXPRESSION converts it to a normal column while preserving each row's
-- already-computed value, so existing data is unaffected by this change.
ALTER TABLE ledger.transactions
    ALTER COLUMN is_manual DROP EXPRESSION;

ALTER TABLE ledger.transactions
    ALTER COLUMN is_manual SET DEFAULT FALSE;

ALTER TABLE ledger.transactions
    ALTER COLUMN is_manual SET NOT NULL;

-- Rename transactions.status: 'PENDING_APPROVAL' -> 'PENDING'.
UPDATE ledger.transactions SET status = 'PENDING' WHERE status = 'PENDING_APPROVAL';

ALTER TABLE ledger.transactions DROP CONSTRAINT transactions_status_check;
ALTER TABLE ledger.transactions ADD CONSTRAINT transactions_status_check
    CHECK (status IN ('PENDING', 'POSTED', 'DELETED'));

-- Rename transactions.source: 'TELLER_SYNC' -> 'BANK_SYNC'.
UPDATE ledger.transactions SET source = 'BANK_SYNC' WHERE source = 'TELLER_SYNC';

ALTER TABLE ledger.transactions DROP CONSTRAINT transactions_source_check;
ALTER TABLE ledger.transactions ADD CONSTRAINT transactions_source_check
    CHECK (source IN ('STATEMENT_UPLOAD', 'BANK_SYNC', 'MANUAL_ENTRY'));
