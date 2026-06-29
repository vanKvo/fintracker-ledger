-- =====================================================================
-- V3__Add_User_Stamp_And_RLS.sql
-- Managed by Flyway. DO NOT edit directly — create a new migration instead.
--
-- Two changes in this migration:
--   1. Denormalize user_id onto every child table (statements, transactions,
--      budget_lines, bill_payments) so ownership checks are a single column
--      equality rather than a multi-hop join.
--   2. Enable PostgreSQL Row-Level Security on all ledger tables so the
--      database itself enforces tenant isolation as a second line of defence,
--      independent of application-layer checks.
--
-- The RLS policies read the session variable app.current_user_id, which is
-- set per-request by the Spring RlsExecuteListener before any jOOQ query runs.
-- =====================================================================

-- =====================================================================
-- SECTION 1 — Add user_id to child tables
-- =====================================================================

-- statements derives user_id from accounts
ALTER TABLE ledger.statements ADD COLUMN user_id UUID;

UPDATE ledger.statements s
   SET user_id = a.user_id
  FROM ledger.accounts a
 WHERE a.account_id = s.account_id;

ALTER TABLE ledger.statements ALTER COLUMN user_id SET NOT NULL;

CREATE INDEX idx_statements_user ON ledger.statements(user_id);

-- transactions derives user_id from accounts
ALTER TABLE ledger.transactions ADD COLUMN user_id UUID;

UPDATE ledger.transactions t
   SET user_id = a.user_id
  FROM ledger.accounts a
 WHERE a.account_id = t.account_id;

ALTER TABLE ledger.transactions ALTER COLUMN user_id SET NOT NULL;

CREATE INDEX idx_tx_user_date ON ledger.transactions(user_id, tx_date DESC);

-- budget_lines derives user_id from budgets
ALTER TABLE ledger.budget_lines ADD COLUMN user_id UUID;

UPDATE ledger.budget_lines bl
   SET user_id = b.user_id
  FROM ledger.budgets b
 WHERE b.budget_id = bl.budget_id;

ALTER TABLE ledger.budget_lines ALTER COLUMN user_id SET NOT NULL;

CREATE INDEX idx_budget_lines_user ON ledger.budget_lines(user_id);

-- bill_payments derives user_id from upcoming_bills
ALTER TABLE ledger.bill_payments ADD COLUMN user_id UUID;

UPDATE ledger.bill_payments bp
   SET user_id = ub.user_id
  FROM ledger.upcoming_bills ub
 WHERE ub.bill_id = bp.bill_id;

ALTER TABLE ledger.bill_payments ALTER COLUMN user_id SET NOT NULL;

CREATE INDEX idx_bill_payments_user ON ledger.bill_payments(user_id);


-- =====================================================================
-- SECTION 2 — Trigger functions: derive user_id on INSERT
--
-- The application never supplies user_id on inserts. The trigger reads it
-- from the parent row so the column cannot be forged by a caller.
-- =====================================================================

CREATE OR REPLACE FUNCTION ledger.derive_statement_user_id()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    SELECT user_id INTO NEW.user_id
      FROM ledger.accounts
     WHERE account_id = NEW.account_id;

    IF NEW.user_id IS NULL THEN
        RAISE EXCEPTION 'account_id % not found; cannot derive user_id', NEW.account_id;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_statements_set_user_id
BEFORE INSERT ON ledger.statements
FOR EACH ROW EXECUTE FUNCTION ledger.derive_statement_user_id();

-- ----

CREATE OR REPLACE FUNCTION ledger.derive_transaction_user_id()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    SELECT user_id INTO NEW.user_id
      FROM ledger.accounts
     WHERE account_id = NEW.account_id;

    IF NEW.user_id IS NULL THEN
        RAISE EXCEPTION 'account_id % not found; cannot derive user_id', NEW.account_id;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_transactions_set_user_id
BEFORE INSERT ON ledger.transactions
FOR EACH ROW EXECUTE FUNCTION ledger.derive_transaction_user_id();

-- ----

CREATE OR REPLACE FUNCTION ledger.derive_budget_line_user_id()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    SELECT user_id INTO NEW.user_id
      FROM ledger.budgets
     WHERE budget_id = NEW.budget_id;

    IF NEW.user_id IS NULL THEN
        RAISE EXCEPTION 'budget_id % not found; cannot derive user_id', NEW.budget_id;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_budget_lines_set_user_id
BEFORE INSERT ON ledger.budget_lines
FOR EACH ROW EXECUTE FUNCTION ledger.derive_budget_line_user_id();

-- ----

CREATE OR REPLACE FUNCTION ledger.derive_bill_payment_user_id()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    SELECT user_id INTO NEW.user_id
      FROM ledger.upcoming_bills
     WHERE bill_id = NEW.bill_id;

    IF NEW.user_id IS NULL THEN
        RAISE EXCEPTION 'bill_id % not found; cannot derive user_id', NEW.bill_id;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_bill_payments_set_user_id
BEFORE INSERT ON ledger.bill_payments
FOR EACH ROW EXECUTE FUNCTION ledger.derive_bill_payment_user_id();


-- =====================================================================
-- SECTION 3 — Row-Level Security
--
-- RLS is a database-layer safety net. Even if a bug in the application
-- layer attempts to fetch another user's data, Postgres silently filters
-- the rows and no error surfaces to the caller.
--
-- Policy: USING (user_id = current_setting('app.current_user_id', true)::uuid)
-- The second arg (true) makes current_setting return NULL instead of
-- throwing when the variable is unset (e.g. during Flyway migrations).
-- A NULL comparison is always FALSE, so unset sessions see no rows.
-- =====================================================================

-- accounts
ALTER TABLE ledger.accounts ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.accounts FORCE ROW LEVEL SECURITY;

CREATE POLICY accounts_isolation ON ledger.accounts
    USING (user_id = current_setting('app.current_user_id', true)::uuid);

-- statements
ALTER TABLE ledger.statements ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.statements FORCE ROW LEVEL SECURITY;

CREATE POLICY statements_isolation ON ledger.statements
    USING (user_id = current_setting('app.current_user_id', true)::uuid);

-- transactions
ALTER TABLE ledger.transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.transactions FORCE ROW LEVEL SECURITY;

CREATE POLICY transactions_isolation ON ledger.transactions
    USING (user_id = current_setting('app.current_user_id', true)::uuid);

-- budgets
ALTER TABLE ledger.budgets ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.budgets FORCE ROW LEVEL SECURITY;

CREATE POLICY budgets_isolation ON ledger.budgets
    USING (user_id = current_setting('app.current_user_id', true)::uuid);

-- budget_lines
ALTER TABLE ledger.budget_lines ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.budget_lines FORCE ROW LEVEL SECURITY;

CREATE POLICY budget_lines_isolation ON ledger.budget_lines
    USING (user_id = current_setting('app.current_user_id', true)::uuid);

-- upcoming_bills
ALTER TABLE ledger.upcoming_bills ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.upcoming_bills FORCE ROW LEVEL SECURITY;

CREATE POLICY upcoming_bills_isolation ON ledger.upcoming_bills
    USING (user_id = current_setting('app.current_user_id', true)::uuid);

-- bill_payments
ALTER TABLE ledger.bill_payments ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.bill_payments FORCE ROW LEVEL SECURITY;

CREATE POLICY bill_payments_isolation ON ledger.bill_payments
    USING (user_id = current_setting('app.current_user_id', true)::uuid);
