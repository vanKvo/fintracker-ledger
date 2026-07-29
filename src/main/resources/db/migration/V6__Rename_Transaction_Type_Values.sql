-- Rename transactions.type values for clarity: 'SALE' -> 'PURCHASE', 'RETURN' -> 'CREDIT'.
-- Constraint must be dropped before the UPDATEs — the old CHECK (type IN ('SALE','RETURN'))
-- would otherwise reject 'PURCHASE'/'CREDIT' as invalid values on the way in.
ALTER TABLE ledger.transactions DROP CONSTRAINT transactions_type_check;

UPDATE ledger.transactions SET type = 'PURCHASE' WHERE type = 'SALE';
UPDATE ledger.transactions SET type = 'CREDIT' WHERE type = 'RETURN';

ALTER TABLE ledger.transactions ADD CONSTRAINT transactions_type_check
    CHECK (type IN ('PURCHASE', 'CREDIT'));
