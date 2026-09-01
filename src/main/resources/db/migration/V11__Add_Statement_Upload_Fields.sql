-- REQ-DP-01 (data-pipeline): the "initiate statement upload" endpoint needs to
-- record the source format and the bank institution the user selected — the
-- data-pipeline's Gatekeeper uses bank_id to look up (or start collecting) a
-- column mapping for CSV imports. Both are nullable: PDF/Image uploads never
-- have a bank_id (no CSV mapping involved), and existing rows predate this
-- column entirely.
ALTER TABLE ledger.statements
    ADD COLUMN source_format VARCHAR(20) CHECK (source_format IN ('PDF', 'CSV', 'IMAGE')),
    ADD COLUMN bank_id VARCHAR(100);
