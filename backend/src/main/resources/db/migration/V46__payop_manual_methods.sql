-- =============================================================================
--  V46 -- a Payop method priced by hand
--
--  The pricing sheet is not the only source of fees any more: an admin can add or
--  correct one method by hand -- the card method the sheet does not list, say.
--  Such a method is marked MANUAL, and importing the sheet again leaves it alone
--  rather than switching it off for being missing from the sheet. If a later sheet
--  does list it, the sheet takes it over.
--
--  The audit trail is the import's: every change versioned, with before and after.
--  Adding a method by hand is recorded as ADDED.
-- =============================================================================

ALTER TABLE payop_fee_method
    ADD COLUMN source TEXT NOT NULL DEFAULT 'SHEET';
ALTER TABLE payop_fee_method
    ADD CONSTRAINT payop_fee_method_source_ck CHECK (source IN ('SHEET', 'MANUAL'));

-- V41's inline CHECK on action, named by Postgres.
ALTER TABLE payop_fee_audit DROP CONSTRAINT payop_fee_audit_action_check;
ALTER TABLE payop_fee_audit
    ADD CONSTRAINT payop_fee_audit_action_ck CHECK (action IN ('IMPORTED', 'UPDATED', 'ADDED'));
