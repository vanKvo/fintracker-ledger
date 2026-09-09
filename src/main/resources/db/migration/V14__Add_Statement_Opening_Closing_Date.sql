-- =====================================================================
-- V14__Add_Statement_Opening_Closing_Date.sql
-- Managed by Flyway. DO NOT edit directly — create a new migration instead.
--
-- REQ-STMT-07: statements carry their full user-declared date range
-- (opening_date..closing_date) for every source format, CSV included.
-- statement_month becomes a Postgres generated column derived from
-- closing_date, rather than a value every caller (application code, and
-- previously the client) had to compute and keep in sync by convention —
-- the database is now the only source of truth for the derivation, so it is
-- structurally impossible for statement_month to disagree with closing_date
-- going forward.
--
-- Migration precondition — no backfill. Converting an existing column to
-- GENERATED requires dropping and re-adding it, which leaves statement_month
-- NULL for every pre-existing row, because those rows have no closing_date
-- to derive it from (this migration is what introduces that column). That is
-- accepted deliberately: every environment this migration runs against has
-- its statement data reset as part of the rollout, so there are no
-- historical rows whose month matters. The migration is therefore
-- destructive-by-design for pre-existing ledger.statements rows and must not
-- be run against an environment whose statement history is being retained —
-- a backfill would be required first, and none is performed here.
-- =====================================================================
ALTER TABLE ledger.statements
    ADD COLUMN opening_date DATE,
    ADD COLUMN closing_date DATE;

-- Dropping statement_month also drops idx_unique_account_statement_month
-- (V1__Initial_Schema.sql:33-34); recreated identically below. No backfill of
-- closing_date is performed first — see the migration precondition above.
ALTER TABLE ledger.statements DROP COLUMN statement_month;

-- The ::timestamp cast is REQUIRED, not stylistic. A generation expression must be
-- IMMUTABLE, and date_trunc has two two-argument overloads: date_trunc(text, timestamp)
-- is IMMUTABLE, date_trunc(text, timestamptz) is STABLE (it depends on the TimeZone
-- setting). A bare DATE argument implicitly casts to either, and Postgres picks
-- timestamptz because it is the preferred type in the datetime category — so
-- date_trunc('month', closing_date) selects the STABLE overload and the statement is
-- rejected with "ERROR: generation expression is not immutable" (SQLSTATE 42P17).
-- Casting to timestamp explicitly pins the immutable overload.
ALTER TABLE ledger.statements
    ADD COLUMN statement_month DATE
        GENERATED ALWAYS AS (date_trunc('month', closing_date::timestamp)::date) STORED;

CREATE UNIQUE INDEX idx_unique_account_statement_month
    ON ledger.statements(account_id, statement_month);

-- New rows always populate closing_date (enforced by @NotNull on the request
-- DTO, not a DB NOT NULL constraint here, so the column conversion above does
-- not have to invent a value for rows that predate it).
