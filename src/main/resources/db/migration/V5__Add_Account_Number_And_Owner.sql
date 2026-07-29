-- REQ-3.1/REQ-3.2 "Accounts" module: the Accounts table shows the last 4 digits of the account
-- number and an owner name, and lets both be set on create/inline-edit. Nullable since existing
-- accounts predate this and have neither value yet.
ALTER TABLE ledger.accounts
    ADD COLUMN account_number VARCHAR(50),
    ADD COLUMN owner          VARCHAR(255);
