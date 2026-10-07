-- =============================================================================
--  V43 -- the coin transfer has started: when, and whether the customer was told
--
--  orders.transfer_started_at
--      When FUT Transfer first accepted this order: its order id came back, a
--      lookup confirmed it after a lost answer, or an admin linked it. Set once, by
--      the vendor ledger, alongside vendor_order.submitted_at, and never cleared.
--      It is what shows the customer's "Track your order" button, so the button
--      means the partner has the order, not that we tried.
--
--  orders.transfer_notice_at
--      When the "Your coin transfer has started" email was claimed for sending. At
--      most once per order: the job that sends it sets this first, and only the one
--      that sets it sends.
--
--  Backfill: orders already at the partner get both, so deploying this sends
--  nobody a "started" email about a transfer that began, or finished, long ago.
-- =============================================================================

ALTER TABLE orders ADD COLUMN transfer_started_at TIMESTAMPTZ;
ALTER TABLE orders ADD COLUMN transfer_notice_at  TIMESTAMPTZ;

UPDATE orders o
   SET transfer_started_at = v.submitted_at,
       transfer_notice_at  = now()
  FROM vendor_order v
 WHERE v.order_id = o.id
   AND v.submitted_at IS NOT NULL;

CREATE INDEX orders_transfer_notice_due_idx ON orders (transfer_started_at)
    WHERE transfer_started_at IS NOT NULL AND transfer_notice_at IS NULL;
