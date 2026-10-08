-- =====================================================================
-- R__System_Categories.sql (Flyway repeatable; re-applied whenever this file changes)
--
-- DP-LEDGER-CATEGORIES-01: the SYSTEM category list, upserted by `code`. Only rows whose code
-- (or name) is missing are inserted — existing rows keep their UUID and their (editable) name,
-- so re-running never duplicates or replaces a category.
-- =====================================================================

INSERT INTO ledger.categories (category_name, code, level, user_id)
SELECT seed.category_name, seed.code, 'SYSTEM', NULL
  FROM (VALUES
        ('groceries',      'groceries'),
        ('food_&_drink',   'food-and-drink'),
        ('transportation', 'transportation'),
        ('shopping',       'shopping'),
        ('entertainment',  'entertainment'),
        ('utilities',      'utilities'),
        ('housing',        'housing'),
        ('healthcare',     'healthcare'),
        ('insurance',      'insurance'),
        ('subscriptions',  'subscriptions'),
        ('travel',         'travel'),
        ('education',      'education'),
        ('personal_care',  'personal-care'),
        ('income',         'income'),
        ('transfer',       'transfer'),
        ('fees',           'fees'),
        ('uncategorized',  'uncategorized')
       ) AS seed (category_name, code)
 WHERE NOT EXISTS (
       SELECT 1 FROM ledger.categories c
        WHERE c.level = 'SYSTEM'
          AND (c.code = seed.code OR c.category_name = seed.category_name));
