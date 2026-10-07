-- =============================================================================
--  V42 -- sending a paid coin order to FUT Transfer without an admin's Approve
--
--  auto_dispatch
--      One row per coin order queued for automatic sending, written in the same
--      transaction that marks the order PAID -- so the queue entry exists exactly
--      when the payment does -- and worked after it commits by a scheduled job,
--      which sends through Approve's own release.
--
--      QUEUED            waiting, or waiting to be tried again (next_attempt_at)
--      SENT              the partner has it; sent automatically, or already sent
--      LEFT_FOR_APPROVE  not sent automatically, for the reason given; Approve works
--      NEEDS_REVIEW      a send was tried and did not go through; an admin decides
--
--  vendor_order_action.action
--      Two more entries in the order's history: APPROVE, an admin sending it, and
--      AUTO_DISPATCH, the automatic queue sending it or leaving it -- so the order
--      page says "Sent automatically" or "Sent by" whoever clicked.
-- =============================================================================

CREATE TABLE auto_dispatch (
    order_id         BIGINT      PRIMARY KEY REFERENCES orders (id) ON DELETE CASCADE,
    state            TEXT        NOT NULL DEFAULT 'QUEUED',
    attempts         INT         NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    reason_code      TEXT,
    reason           TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT auto_dispatch_state_ck CHECK (state IN ('QUEUED', 'SENT', 'LEFT_FOR_APPROVE', 'NEEDS_REVIEW'))
);

CREATE INDEX auto_dispatch_due_idx ON auto_dispatch (next_attempt_at) WHERE state = 'QUEUED';

ALTER TABLE vendor_order_action DROP CONSTRAINT vendor_order_action_action_ck;
ALTER TABLE vendor_order_action ADD CONSTRAINT vendor_order_action_action_ck CHECK (action IN (
    'SEND_SIGN_IN', 'RESUME', 'STOP', 'MARK_FINISHED', 'RETRY', 'LINK', 'RESOLVE', 'APPROVE', 'AUTO_DISPATCH'));
