-- REQ-DP-05: S3 trigger must look up a statement's owner (account_id/user_id) by statement_id.
--
-- ledger.statements has FORCE RLS (V3). Without an active user_id, app.current_user_id is UNSET, 
-- causing the primary isolation policy to evaluate to NULL/FALSE and fail silently (404).
--
-- This PERMISSIVE policy adds an OR condition gated specifically by app.internal_owner_lookup. 
-- Uses a dedicated session flag (not app.current_user_id/X-Internal-User-Id) to prevent other 
-- internal callers from reading arbitrary statement ownership.

CREATE POLICY statements_internal_owner_lookup ON ledger.statements
    FOR SELECT
    USING (current_setting('app.internal_owner_lookup', true) = 'true');
