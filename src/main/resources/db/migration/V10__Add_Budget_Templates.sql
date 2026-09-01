-- =====================================================================
-- V10__Add_Budget_Templates.sql
-- Managed by Flyway. DO NOT edit directly — create a new migration instead.
--
-- REQ-5.3 "Quick Start Templates" (F.1 / F.2): reusable sets of category
-- allocations used to seed new budgets.
--
-- Two documented deviations from the written requirement, both forced by
-- the existing schema:
--
--   1. F.1 declares user_id FOREIGN KEY -> security.users(id). No such
--      schema exists — V1 creates only `ledger`, and no table in this
--      service references a users table (ledger.budgets.user_id is a bare
--      UUID stamped from the request identity). The column is therefore a
--      plain UUID, consistent with every other tenant-scoped table here.
--
--   2. F.2 types default_limit NUMERIC(11,2). Kept as specified: 9 integer
--      digits plus 2 decimals is exactly the REQ-5.1 range ceiling of
--      999,999,999.99, so the column type and the business rule agree
--      rather than merely coexisting as they do on budget_lines
--      (DECIMAL(15,2) guarded only by an application check).
-- =====================================================================

CREATE TABLE ledger.budget_templates (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- NULL user_id == a global system template. This is the ownership
    -- signal the RLS policies below key on; is_system is its denormalized
    -- twin, kept in sync by chk_budget_templates_system_ownership.
    user_id      UUID,
    name         VARCHAR(100) NOT NULL,
    description  VARCHAR(255),
    is_system    BOOLEAN NOT NULL DEFAULT false,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- A system template has no owner and a custom template always has one.
    -- Without this the two columns can disagree, and a row with
    -- is_system = true AND user_id = <someone> would be visible to every
    -- user while claiming private ownership.
    CONSTRAINT chk_budget_templates_system_ownership
        CHECK ((is_system AND user_id IS NULL) OR (NOT is_system AND user_id IS NOT NULL))
);

-- REQ-5.3 B "Template Name Uniqueness": system names globally unique,
-- custom names unique per user. Both case-insensitive.
CREATE UNIQUE INDEX uq_system_template_name
    ON ledger.budget_templates (LOWER(name)) WHERE user_id IS NULL;

CREATE UNIQUE INDEX uq_custom_template_name
    ON ledger.budget_templates (user_id, LOWER(name)) WHERE user_id IS NOT NULL;

CREATE INDEX idx_budget_templates_user ON ledger.budget_templates(user_id);


CREATE TABLE ledger.budget_template_lines (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    template_id   UUID NOT NULL REFERENCES ledger.budget_templates(id) ON DELETE CASCADE,
    -- Denormalized from the parent by trg_budget_template_lines_set_user_id
    -- so RLS is a single-column check, matching ledger.budget_lines.
    -- Nullable here because a system template's lines have no owner.
    user_id       UUID,
    category_name VARCHAR(50) NOT NULL,
    default_limit NUMERIC(11,2) NOT NULL
        CHECK (default_limit >= 0.00 AND default_limit <= 999999999.99),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- REQ-5.3 F.2 uq_template_category — case-insensitive, so a template can
-- never seed a budget that violates REQ-5.2 "Category Uniqueness".
CREATE UNIQUE INDEX uq_template_category
    ON ledger.budget_template_lines (template_id, LOWER(category_name));

CREATE INDEX idx_budget_template_lines_template ON ledger.budget_template_lines(template_id);


-- =====================================================================
-- Ownership derivation — mirrors ledger.derive_budget_line_user_id (V3),
-- except a NULL owner is legitimate here rather than an error, because a
-- system template's lines belong to no one.
-- =====================================================================
CREATE OR REPLACE FUNCTION ledger.derive_budget_template_line_user_id()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    parent_exists BOOLEAN;
BEGIN
    SELECT true, t.user_id INTO parent_exists, NEW.user_id
      FROM ledger.budget_templates t
     WHERE t.id = NEW.template_id;

    IF parent_exists IS NULL THEN
        RAISE EXCEPTION 'template_id % not found; cannot derive user_id', NEW.template_id;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_budget_template_lines_set_user_id
BEFORE INSERT ON ledger.budget_template_lines
FOR EACH ROW EXECUTE FUNCTION ledger.derive_budget_template_line_user_id();


-- =====================================================================
-- SECTION: Row-Level Security
--
-- These tables are the first in the schema with TWO ownership models, so
-- the standard V3 predicate does not work:
--
--     USING (user_id = current_setting('app.current_user_id', true)::uuid)
--
-- A system template has user_id IS NULL. `NULL = <uuid>` evaluates to
-- NULL, which RLS treats as false — so copying the V3 policy verbatim
-- would hide every system template from every user and leave the
-- "Quick Start" catalog permanently empty, with no error to explain it.
--
-- SELECT is therefore widened to "mine or unowned", while INSERT, UPDATE
-- and DELETE stay strictly "mine". That asymmetry is the point: every
-- user can read the system catalog, and no user can write to it.
-- =====================================================================

ALTER TABLE ledger.budget_templates ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.budget_templates FORCE ROW LEVEL SECURITY;

CREATE POLICY budget_templates_read ON ledger.budget_templates
    FOR SELECT
    USING (user_id IS NULL
           OR user_id = current_setting('app.current_user_id', true)::uuid);

-- WITH CHECK rejects user_id IS NULL, so no request-scoped session can
-- manufacture a system template.
CREATE POLICY budget_templates_insert ON ledger.budget_templates
    FOR INSERT
    WITH CHECK (user_id = current_setting('app.current_user_id', true)::uuid);

CREATE POLICY budget_templates_update ON ledger.budget_templates
    FOR UPDATE
    USING (user_id = current_setting('app.current_user_id', true)::uuid)
    WITH CHECK (user_id = current_setting('app.current_user_id', true)::uuid);

CREATE POLICY budget_templates_delete ON ledger.budget_templates
    FOR DELETE
    USING (user_id = current_setting('app.current_user_id', true)::uuid);


ALTER TABLE ledger.budget_template_lines ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.budget_template_lines FORCE ROW LEVEL SECURITY;

CREATE POLICY budget_template_lines_read ON ledger.budget_template_lines
    FOR SELECT
    USING (user_id IS NULL
           OR user_id = current_setting('app.current_user_id', true)::uuid);

CREATE POLICY budget_template_lines_insert ON ledger.budget_template_lines
    FOR INSERT
    WITH CHECK (user_id = current_setting('app.current_user_id', true)::uuid);

CREATE POLICY budget_template_lines_update ON ledger.budget_template_lines
    FOR UPDATE
    USING (user_id = current_setting('app.current_user_id', true)::uuid)
    WITH CHECK (user_id = current_setting('app.current_user_id', true)::uuid);

CREATE POLICY budget_template_lines_delete ON ledger.budget_template_lines
    FOR DELETE
    USING (user_id = current_setting('app.current_user_id', true)::uuid);


-- =====================================================================
-- Seed: the predefined global catalog named in REQ-5.3 A.
--
-- Inserted by the migration (which runs as the schema owner, outside any
-- request context) because the INSERT policy above deliberately makes it
-- impossible to create a system template through the application.
--
-- These figures previously lived hardcoded in the Angular create-budget
-- dialog; the catalog is their system of record now.
-- =====================================================================
INSERT INTO ledger.budget_templates (id, user_id, name, description, is_system) VALUES
    ('11111111-1111-4111-8111-111111111111', NULL, 'Basic Living',
     'Essential monthly expenses for a typical household.', true),
    ('22222222-2222-4222-8222-222222222222', NULL, 'Aggressive Savings',
     'Trimmed discretionary spending with a large monthly savings transfer.', true);

INSERT INTO ledger.budget_template_lines (template_id, user_id, category_name, default_limit) VALUES
    ('11111111-1111-4111-8111-111111111111', NULL, 'Rent/Mortgage',   1500.00),
    ('11111111-1111-4111-8111-111111111111', NULL, 'Groceries',        500.00),
    ('11111111-1111-4111-8111-111111111111', NULL, 'Utilities',        200.00),
    ('11111111-1111-4111-8111-111111111111', NULL, 'Transportation',   150.00),
    ('11111111-1111-4111-8111-111111111111', NULL, 'Insurance',        200.00),
    ('11111111-1111-4111-8111-111111111111', NULL, 'Dining Out',       100.00),
    ('11111111-1111-4111-8111-111111111111', NULL, 'Miscellaneous',    100.00),

    ('22222222-2222-4222-8222-222222222222', NULL, 'Rent/Mortgage',   1200.00),
    ('22222222-2222-4222-8222-222222222222', NULL, 'Savings Transfer',1000.00),
    ('22222222-2222-4222-8222-222222222222', NULL, 'Groceries',        350.00),
    ('22222222-2222-4222-8222-222222222222', NULL, 'Utilities',        150.00),
    ('22222222-2222-4222-8222-222222222222', NULL, 'Transportation',   100.00),
    ('22222222-2222-4222-8222-222222222222', NULL, 'Dining Out',        50.00),
    ('22222222-2222-4222-8222-222222222222', NULL, 'Miscellaneous',     50.00);
