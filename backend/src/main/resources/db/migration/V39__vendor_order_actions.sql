-- =============================================================================
--  V39 -- what admins do to an order at FUT Transfer, and who did it
--
--  vendor_order.resubmitted_at
--      When an admin last sent the customer's corrected sign-in, or resumed the
--      order. Two jobs: while it is recent, a second click is refused, so two
--      admins cannot send the same correction at once; and for a while after, a
--      status report identical to the one from before the correction is read as
--      stale rather than as the new details being refused.
--
--  vendor_order_action
--      One row per admin action on an order at FUT Transfer -- sending a
--      corrected sign-in, resuming, stopping, marking finished, retrying,
--      linking, resolving -- with who did it and what came of it. The calls
--      themselves are in vendor_call; this is the decision behind them.
--
--      Never stored: a sign-in, a backup code or anything the vendor sent back.
--      detail is written by us; code is a bare identifier (TIMEOUT, HTTP_429).
-- =============================================================================

ALTER TABLE vendor_order ADD COLUMN resubmitted_at TIMESTAMPTZ;

CREATE TABLE vendor_order_action (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id     BIGINT      NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    action       TEXT        NOT NULL,
    actor_id     BIGINT      REFERENCES account (id),
    actor_label  TEXT,
    -- DONE: the vendor confirmed it. REFUSED: the vendor answered no, or we did not
    -- send it. UNCERTAIN: no answer we could read; it may have happened.
    outcome      TEXT        NOT NULL,
    code         TEXT,
    detail       TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT vendor_order_action_action_ck CHECK (action IN (
        'SEND_SIGN_IN', 'RESUME', 'STOP', 'MARK_FINISHED', 'RETRY', 'LINK', 'RESOLVE')),
    CONSTRAINT vendor_order_action_outcome_ck CHECK (outcome IN ('DONE', 'REFUSED', 'UNCERTAIN'))
);

CREATE INDEX vendor_order_action_order_ix ON vendor_order_action (order_id, created_at);
