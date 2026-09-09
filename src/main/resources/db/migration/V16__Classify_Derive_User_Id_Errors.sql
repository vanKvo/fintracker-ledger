-- =====================================================================
-- V16__Classify_Derive_User_Id_Errors.sql
-- Managed by Flyway. DO NOT edit directly — create a new migration instead.
--
-- The five derive_*_user_id trigger functions (V3, V10) raise a bare
-- RAISE EXCEPTION when the parent row cannot be read. Under RLS that is
-- exactly what a cross-tenant write produces: the attacker cannot see the
-- victim's parent row, the SELECT finds nothing, and the trigger raises.
-- The behavior is correct — the write is refused — but a bare PL/pgSQL RAISE
-- defaults to SQLSTATE P0001 (raise_exception), which Spring's
-- SQLStateSQLExceptionTranslator does NOT classify (it maps class codes
-- 07/08/21/22/23/40/42/... — "P0" is in none of them). Translation returns
-- null and jOOQ's native, unclassified DataAccessException escapes — a type
-- no application catch-block or test can reasonably handle, and whose
-- message carries the failing SQL statement.
--
-- Reclassifying as 23503 (foreign_key_violation) puts the failure in class
-- 23, which Spring maps to DataIntegrityViolationException — the same shape
-- as every other integrity refusal this service surfaces. 23503 is preferred
-- over insufficient_privilege (42501): class 42 maps to
-- BadSqlGrammarException, which would misdescribe the failure in logs. The
-- semantic fit is honest too: the row references a parent that, as far as
-- this session can see, does not exist.
--
-- Only the RAISE statements change; every function body is otherwise
-- byte-identical to V3/V10, and the triggers themselves are untouched
-- (CREATE OR REPLACE FUNCTION swaps the body under the existing trigger).
-- =====================================================================

CREATE OR REPLACE FUNCTION ledger.derive_statement_user_id()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    SELECT user_id INTO NEW.user_id
      FROM ledger.accounts
     WHERE account_id = NEW.account_id;

    IF NEW.user_id IS NULL THEN
        RAISE EXCEPTION 'account_id % not found; cannot derive user_id', NEW.account_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION ledger.derive_transaction_user_id()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    SELECT user_id INTO NEW.user_id
      FROM ledger.accounts
     WHERE account_id = NEW.account_id;

    IF NEW.user_id IS NULL THEN
        RAISE EXCEPTION 'account_id % not found; cannot derive user_id', NEW.account_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION ledger.derive_budget_line_user_id()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    SELECT user_id INTO NEW.user_id
      FROM ledger.budgets
     WHERE budget_id = NEW.budget_id;

    IF NEW.user_id IS NULL THEN
        RAISE EXCEPTION 'budget_id % not found; cannot derive user_id', NEW.budget_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION ledger.derive_bill_payment_user_id()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    SELECT user_id INTO NEW.user_id
      FROM ledger.upcoming_bills
     WHERE bill_id = NEW.bill_id;

    IF NEW.user_id IS NULL THEN
        RAISE EXCEPTION 'bill_id % not found; cannot derive user_id', NEW.bill_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION ledger.derive_budget_template_line_user_id()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    parent_exists BOOLEAN;
BEGIN
    SELECT true, t.user_id INTO parent_exists, NEW.user_id
      FROM ledger.budget_templates t
     WHERE t.id = NEW.template_id;

    IF parent_exists IS NULL THEN
        RAISE EXCEPTION 'template_id % not found; cannot derive user_id', NEW.template_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    RETURN NEW;
END;
$$;
