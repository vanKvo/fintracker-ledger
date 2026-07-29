-- Adds 'CASH' as a valid ledger.accounts.account_type — lets a user log cash purchases/income
-- as manual transactions against a real account row (account_id stays NOT NULL everywhere)
-- instead of needing a "no account" special case throughout the ledger.
ALTER TABLE ledger.accounts DROP CONSTRAINT accounts_account_type_check;
ALTER TABLE ledger.accounts ADD CONSTRAINT accounts_account_type_check
    CHECK (account_type IN ('CHECKING', 'SAVINGS', 'CREDIT', 'CASH'));
