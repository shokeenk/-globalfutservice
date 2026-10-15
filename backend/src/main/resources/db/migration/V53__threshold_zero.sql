-- =============================================================================
--  V53: a buyNowThreshold of 0 can be recorded
--
--  The client's instruction (2026-10-15): public-pool orders send buyNowThreshold 0, and
--  never the order's amount. V40 only allowed a recorded threshold above zero; 0 is a real
--  value sent, so the record allows it. Negative values stay refused.
-- =============================================================================

ALTER TABLE vendor_order DROP CONSTRAINT vendor_order_threshold_ck;
ALTER TABLE vendor_order ADD CONSTRAINT vendor_order_threshold_ck
    CHECK (buy_now_threshold_sent IS NULL OR buy_now_threshold_sent >= 0);
