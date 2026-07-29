-- =====================================================================
-- V8__Add_Budget_Status.sql
-- Managed by Flyway. DO NOT edit directly — create a new migration instead.
--
-- REQ-5.1 "Data Impacts": budgets carry a lifecycle status so that closed
-- (historical) periods become immutable. Existing rows default to ACTIVE,
-- matching REQ-5.1 "State Initialization".
-- =====================================================================

ALTER TABLE ledger.budgets
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
    CONSTRAINT chk_budgets_status CHECK (status IN ('ACTIVE', 'CLOSED'));

-- Supports the month-end batch closure (status = 'ACTIVE' AND effective_month < cutoff)
-- and "latest active budget" lookups.
CREATE INDEX idx_budgets_status_month ON ledger.budgets(status, effective_month);

-- =====================================================================
-- REQ-5.1 "Automated Period Closure" — RLS policy for the system batch job.
--
-- The tenant-isolation policy (V3: budgets_isolation) only exposes rows whose
-- user_id matches app.current_user_id, which is set per-request from the
-- authenticated identity. The month-end scheduler is a system actor with no
-- user context, and its UPDATE must span every tenant's budgets.
--
-- Postgres RLS policies are permissive by default (OR-ed together), and an
-- UPDATE first scans for candidate rows under the FOR SELECT policies before
-- the FOR UPDATE policy is applied — so the batch job needs BOTH: a SELECT
-- policy to see other tenants' rows during the scan phase, and an UPDATE
-- policy to modify them. Each is gated on the app.system_job flag, which the
-- repository raises with SET LOCAL inside the batch transaction; Postgres
-- automatically discards it when the transaction ends, so the elevation can
-- never leak to pooled request connections.
-- =====================================================================

-- Allow selecting for batch processing
CREATE POLICY budgets_system_batch_select ON ledger.budgets
    FOR SELECT
    USING (current_setting('app.system_job', true) = 'true');

-- Allow updating for batch processing
CREATE POLICY budgets_system_batch_update ON ledger.budgets
    FOR UPDATE
    USING (current_setting('app.system_job', true) = 'true')
    WITH CHECK (current_setting('app.system_job', true) = 'true');
