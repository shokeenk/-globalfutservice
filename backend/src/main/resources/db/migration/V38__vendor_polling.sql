-- =============================================================================
--  V38 -- what the poller needs to know about each vendor order
--
--  customer_action   what we asked the customer to do, in our own vocabulary
--                    (RESUBMIT_SIGN_IN, FREE_TRANSFER_SLOTS...), while an order
--                    waits for them. The storefront words it; the vendor's codes
--                    never reach the customer.
--  missing_polls     consecutive status reads that did not mention the order.
--                    Past a threshold, the order goes to an admin: silence is
--                    never read as progress.
--  last_polled_at    when the vendor last reported on it.
--  last_progress_at  when anything last moved: its status, or coins delivered.
--                    Nothing moving for too long sends it to an admin.
--
--  vendor_control gains the poll schedule, so the interval, its jitter and its
--  back-off hold across instances rather than each instance keeping its own.
-- =============================================================================

ALTER TABLE vendor_order
    ADD COLUMN customer_action  TEXT,
    ADD COLUMN missing_polls    INT NOT NULL DEFAULT 0,
    ADD COLUMN last_polled_at   TIMESTAMPTZ,
    ADD COLUMN last_progress_at TIMESTAMPTZ;

-- Orders already with the vendor start their stall clock now, not at submission:
-- they were never watched for progress before this.
UPDATE vendor_order SET last_progress_at = now()
 WHERE state IN ('SUBMITTED', 'IN_DELIVERY', 'AWAITING_CUSTOMER');

CREATE INDEX vendor_order_polling_ix ON vendor_order (state, last_polled_at);

ALTER TABLE vendor_control
    ADD COLUMN next_poll_at  TIMESTAMPTZ,
    ADD COLUMN backoff_level INT NOT NULL DEFAULT 0;
