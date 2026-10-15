-- =============================================================================
--  V52: a customer's corrected sign-in reaches FUT Transfer by itself
--
--  vendor_order.signin_resubmitted_at
--      When the customer last re-entered their sign-in while FUT Transfer was waiting
--      for them (AWAITING_CUSTOMER). Each such submission is sent once.
--  vendor_order.signin_sent_for
--      The submission last sent, by its signin_resubmitted_at: a submission is due
--      while this is older than it.
--  vendor_order.signin_sent_at
--      When the last automatic send was made. At most one every two minutes.
--
--  orders.signin_problem
--      What we asked the customer to fix in their sign-in when the refusal came from a
--      send rather than from a status report: RESUBMIT_SIGN_IN (EA email or password)
--      or NEW_BACKUP_CODES. Shown on their order page with the form; cleared when they
--      enter new details.
-- =============================================================================

ALTER TABLE vendor_order
    ADD COLUMN signin_resubmitted_at TIMESTAMPTZ,
    ADD COLUMN signin_sent_for       TIMESTAMPTZ,
    ADD COLUMN signin_sent_at        TIMESTAMPTZ;

CREATE INDEX vendor_order_signin_resend_ix ON vendor_order (signin_resubmitted_at)
    WHERE state = 'AWAITING_CUSTOMER' AND signin_resubmitted_at IS NOT NULL;

ALTER TABLE orders
    ADD COLUMN signin_problem TEXT,
    ADD CONSTRAINT orders_signin_problem_ck
        CHECK (signin_problem IS NULL OR signin_problem IN ('RESUBMIT_SIGN_IN', 'NEW_BACKUP_CODES'));
