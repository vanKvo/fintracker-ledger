-- =====================================================================
-- V21__Add_Category_Code_And_Is_Active.sql
-- Managed by Flyway. DO NOT edit directly — create a new migration instead.
--
-- DP-LEDGER-CATEGORIES-01: SYSTEM categories get an immutable `code`, stable across environments
-- (UUIDs differ per environment); the Data Pipeline maps to codes, never UUIDs.
-- DP-LEDGER-CATEGORIES-02: `is_active` lets a category be deactivated instead of deleted, so
-- category_id values on existing transactions stay valid.
--
-- Renames keep each row's UUID so existing transaction.category_id links carry over:
--   others -> uncategorized (the fallback for unmapped transactions)
--   dining -> "Food & Drink" (stored name food_&_drink, code food-and-drink)
-- =====================================================================

ALTER TABLE ledger.categories
    ADD COLUMN code      VARCHAR(100) NULL,
    ADD COLUMN is_active BOOLEAN      NOT NULL DEFAULT TRUE;

UPDATE ledger.categories SET category_name = 'uncategorized'
 WHERE level = 'SYSTEM' AND category_name = 'others';
UPDATE ledger.categories SET category_name = 'food_&_drink'
 WHERE level = 'SYSTEM' AND category_name = 'dining';

-- Code = spec 03's label normalization: lowercase, & -> and, drop other punctuation, _/space -> -.
UPDATE ledger.categories
   SET code = trim(both '-' from regexp_replace(
                  regexp_replace(replace(lower(category_name), '&', 'and'), '[^a-z0-9_ ]', '', 'g'),
                  '[_ ]+', '-', 'g'))
 WHERE level = 'SYSTEM';

ALTER TABLE ledger.categories
    ADD CONSTRAINT chk_categories_code_by_level
        CHECK ((level = 'SYSTEM' AND code IS NOT NULL) OR (level = 'USER' AND code IS NULL));

CREATE UNIQUE INDEX uq_categories_system_code
    ON ledger.categories (code) WHERE level = 'SYSTEM';

-- Immutable code: the Data Pipeline's mapping tables reference it in every environment.
CREATE FUNCTION ledger.reject_category_code_change() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.code IS DISTINCT FROM OLD.code THEN
        RAISE EXCEPTION 'ledger.categories.code is immutable (category %)', OLD.category_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_categories_code_immutable
    BEFORE UPDATE ON ledger.categories
    FOR EACH ROW EXECUTE FUNCTION ledger.reject_category_code_change();

-- Free-text category columns follow the renames (budgets still match on text).
UPDATE ledger.transactions SET category = 'Uncategorized' WHERE lower(category) = 'others';
UPDATE ledger.transactions SET category = 'Food & Drink'  WHERE lower(category) = 'dining';
UPDATE ledger.budget_lines SET category = 'Uncategorized' WHERE lower(category) = 'others';
UPDATE ledger.budget_lines SET category = 'Food & Drink'  WHERE lower(category) = 'dining';
UPDATE ledger.budget_template_lines SET category_name = 'Uncategorized' WHERE lower(category_name) = 'others';
UPDATE ledger.budget_template_lines SET category_name = 'Food & Drink'  WHERE lower(category_name) = 'dining';
