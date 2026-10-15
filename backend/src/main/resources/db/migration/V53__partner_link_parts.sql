-- =============================================================================
--  V53: the parts of FUT Transfer's tracking link, kept apart
--
--  FUT Transfer's short links (transferstatus.top/<shortCode>) answered 404 on
--  2026-10-15, and FUT Transfer said to give customers
--  https://futtransfer.top/orderStatus?code=<shortCode> (with &shop=<shop>) until they are
--  fixed. To build that, the code and the shop are kept as /generateOrderStatusLink
--  returned them, beside the link itself (still stored verbatim, unchanged).
--
--  vendor_order.tracking_short_code      shortCode, as returned
--  vendor_order.tracking_shop            shop, as returned; empty is stored as null
--  vendor_order.tracking_special_params  specialParams, as returned; empty is stored as null
--
--  Backfill: links already stored carry the code as their path's last part.
-- =============================================================================

ALTER TABLE vendor_order
    ADD COLUMN tracking_short_code     TEXT,
    ADD COLUMN tracking_shop           TEXT,
    ADD COLUMN tracking_special_params TEXT,
    ADD CONSTRAINT vendor_order_tracking_code_ck
        CHECK (tracking_short_code IS NULL OR tracking_short_code ~ '^[A-Za-z0-9]{4,32}$');

UPDATE vendor_order
   SET tracking_short_code = substring(split_part(tracking_url, '?', 1) from '[^/]+$')
 WHERE tracking_url IS NOT NULL
   AND substring(split_part(tracking_url, '?', 1) from '[^/]+$') ~ '^[A-Za-z0-9]{4,32}$';
