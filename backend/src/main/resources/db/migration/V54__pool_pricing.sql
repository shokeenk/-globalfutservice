-- =============================================================================
--  V54: what a public-pool buyNowThreshold was worked out from
--
--  FUT Transfer (2026-10-15): buyNowThreshold is a price ceiling in EUR per 100K, worked
--  out from /buyConditionAPI's supplier prices just before sending. Each send records the
--  supplier rate it was based on, the margin added and the transfer fee reported, beside
--  the threshold sent (buy_now_threshold_sent, V40 -- positive, as V40 requires), so the
--  admin page can show the estimated cost next to what the customer paid.
--
--  vendor_order.supplier_rate_sent     EUR per 100K: the clearing supplier price, as read
--  vendor_order.threshold_margin_pct   the margin added on top of it, in percent
--  vendor_order.transfer_fee_at_send   /buyConditionAPI's transferFee, as reported
-- =============================================================================

ALTER TABLE vendor_order
    ADD COLUMN supplier_rate_sent   NUMERIC(14, 4),
    ADD COLUMN threshold_margin_pct NUMERIC(6, 2),
    ADD COLUMN transfer_fee_at_send NUMERIC(14, 4),
    ADD CONSTRAINT vendor_order_supplier_rate_ck CHECK (supplier_rate_sent IS NULL OR supplier_rate_sent > 0),
    ADD CONSTRAINT vendor_order_margin_ck CHECK (threshold_margin_pct IS NULL OR threshold_margin_pct >= 0),
    ADD CONSTRAINT vendor_order_transfer_fee_ck CHECK (transfer_fee_at_send IS NULL OR transfer_fee_at_send >= 0);
