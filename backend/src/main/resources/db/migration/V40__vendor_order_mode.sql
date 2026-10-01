-- =============================================================================
--  V40 -- how each order was placed at FUT Transfer
--
--  Coin orders can now be placed two ways: bought from FUT Transfer's public
--  seller pool (/buyCoinsAPI) or sent from our own sender accounts (/orderAPI).
--  Which one is configuration, and configuration changes, so each vendor order
--  records what it was actually sent with. A retry after a refusal uses the
--  configuration of the moment and records that instead.
--
--  vendor_order.order_mode
--      PUBLIC_POOL or OWN_SENDERS. Every row before this migration was sent
--      through /orderAPI, so they are all OWN_SENDERS. No default from here on:
--      the claim always says which, and a path that forgets fails loudly.
--
--  vendor_order.transfer_method
--      The vendor's method name as sent, e.g. targetedSnipe. Null on rows from
--      before this migration: it was configuration then and was not recorded,
--      so it is left unknown rather than guessed.
--
--  vendor_order.buy_now_threshold_sent, max_price_sent
--      What a public-pool order sent for buyNowThreshold and maxPrice, in the
--      vendor's own unit (per 100K coins; the vendor states no currency). Null
--      means the field was not sent.
--
--  vendor_order.balance_at_send
--      The account balance /buyConditionAPI reported just before a public-pool
--      order was sent, for margin tracking. The vendor does not document its
--      currency. Null if it was not read, or the read failed: a failed read never
--      stops a send.
-- =============================================================================

ALTER TABLE vendor_order
    ADD COLUMN order_mode             TEXT,
    ADD COLUMN transfer_method        TEXT,
    ADD COLUMN buy_now_threshold_sent NUMERIC(14, 4),
    ADD COLUMN max_price_sent         NUMERIC(14, 4),
    ADD COLUMN balance_at_send        NUMERIC(18, 4);

UPDATE vendor_order SET order_mode = 'OWN_SENDERS';

ALTER TABLE vendor_order
    ALTER COLUMN order_mode SET NOT NULL,
    ADD CONSTRAINT vendor_order_mode_ck CHECK (order_mode IN ('PUBLIC_POOL', 'OWN_SENDERS')),
    ADD CONSTRAINT vendor_order_threshold_ck CHECK (buy_now_threshold_sent IS NULL OR buy_now_threshold_sent > 0),
    ADD CONSTRAINT vendor_order_max_price_ck CHECK (max_price_sent IS NULL OR max_price_sent > 0);
