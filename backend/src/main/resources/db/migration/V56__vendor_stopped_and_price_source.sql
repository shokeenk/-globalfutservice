-- A stopped order stays stopped, and how its price ceiling came about is recorded (2026-10-15).
--
--   state STOPPED       An admin pressed "Stop at partner" and FUT Transfer confirmed it. The poll
--                       still reads it and records what was delivered, but never moves it back to
--                       delivering: only "Resume at partner" does, or the partner finishing it in full.
--   stopped_at          when it was stopped
--   stopped_by          who stopped it: the admin's label, for staff only
--   threshold_source    how buyNowThreshold was set when it was sent: MARKET (FUT Transfer's price
--                       + the margin) or FIXED_OVERRIDE (GFS_FUTTRANSFER_BUY_NOW_THRESHOLD). Null for
--                       orders sent before automatic pricing (#124), whose ceiling was the order size.

ALTER TABLE vendor_order DROP CONSTRAINT vendor_order_state_ck;
ALTER TABLE vendor_order ADD CONSTRAINT vendor_order_state_ck CHECK (state IN (
    'SUBMITTING', 'SUBMITTED', 'IN_DELIVERY', 'AWAITING_CUSTOMER', 'DELIVERED',
    'PARTIALLY_DELIVERED', 'FAILED', 'NEEDS_REVIEW', 'RESOLVED', 'STOPPED'));

ALTER TABLE vendor_order
    ADD COLUMN stopped_at       TIMESTAMPTZ,
    ADD COLUMN stopped_by       TEXT,
    ADD COLUMN threshold_source TEXT,
    ADD CONSTRAINT vendor_order_threshold_source_ck CHECK (threshold_source IS NULL
        OR threshold_source IN ('MARKET', 'FIXED_OVERRIDE'));

-- Orders an admin stopped at FUT Transfer before this state existed (GFS-26-ZTE3A759): a later
-- report moved them back to delivering. The last confirmed Stop or Resume on each says which
-- they are now; stopped ones become STOPPED, with who and when, so Resume applies to them.
UPDATE vendor_order v
   SET state = 'STOPPED', stopped_at = last.created_at, stopped_by = last.actor_label,
       last_error_code = 'STOPPED_BY_ADMIN', review_reason = NULL, customer_action = NULL, updated_at = now()
  FROM (SELECT DISTINCT ON (order_id) order_id, action, actor_label, created_at
          FROM vendor_order_action
         WHERE action IN ('STOP', 'RESUME') AND outcome = 'DONE'
         ORDER BY order_id, created_at DESC, id DESC) last
 WHERE last.order_id = v.order_id
   AND last.action = 'STOP'
   AND v.vendor_order_id IS NOT NULL
   AND v.state IN ('SUBMITTED', 'IN_DELIVERY', 'AWAITING_CUSTOMER', 'NEEDS_REVIEW');

-- Orders sent since #124 with a margin were priced from the market; with a threshold and no
-- margin, from the override. Anything sent before keeps null.
UPDATE vendor_order SET threshold_source = 'MARKET'
 WHERE threshold_margin_pct IS NOT NULL AND buy_now_threshold_sent IS NOT NULL;
UPDATE vendor_order SET threshold_source = 'FIXED_OVERRIDE'
 WHERE threshold_margin_pct IS NULL AND supplier_rate_sent IS NOT NULL AND buy_now_threshold_sent IS NOT NULL;
