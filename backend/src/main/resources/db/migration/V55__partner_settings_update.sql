-- minTransferAmount at FUT Transfer for orders sent before 2026-10-15.
--
-- Those orders went with minTransferAmount 50 (50K per transfer), which keeps them waiting
-- for 50K transfers. New orders send 0 (off). Each open public-pool order the partner has
-- is marked here, once, and after startup the application sends it /editOrderAPI with
-- {"minTransferAmount": 0} and nothing else -- at most once per order.
--
--   settings_update     PENDING: due; SENDING: being sent (never sent again); then
--                       DONE, IGNORED (the partner answered but did not apply it),
--                       REFUSED, UNCERTAIN or SKIPPED (no longer open when its turn came)
--   settings_update_at  when it last changed
--
-- Orders sent from now on are never marked: they already carry the current setting.

ALTER TABLE vendor_order
    ADD COLUMN settings_update    TEXT,
    ADD COLUMN settings_update_at TIMESTAMPTZ,
    ADD CONSTRAINT vendor_order_settings_update_ck CHECK (settings_update IS NULL OR settings_update IN (
        'PENDING', 'SENDING', 'DONE', 'IGNORED', 'REFUSED', 'UNCERTAIN', 'SKIPPED'));

UPDATE vendor_order
   SET settings_update = 'PENDING', settings_update_at = now()
 WHERE order_mode = 'PUBLIC_POOL'
   AND vendor_order_id IS NOT NULL
   AND state IN ('SUBMITTED', 'IN_DELIVERY', 'AWAITING_CUSTOMER', 'NEEDS_REVIEW')
   AND (vendor_status IS NULL OR lower(vendor_status) NOT IN ('finished', 'cancelled', 'canceled', 'archived'));

CREATE INDEX vendor_order_settings_due_idx ON vendor_order (order_id) WHERE settings_update = 'PENDING';

-- The admin timeline records each update, by the deploy or by an admin's button.
ALTER TABLE vendor_order_action DROP CONSTRAINT vendor_order_action_action_ck;
ALTER TABLE vendor_order_action ADD CONSTRAINT vendor_order_action_action_ck CHECK (action IN (
    'SEND_SIGN_IN', 'RESUME', 'STOP', 'MARK_FINISHED', 'RETRY', 'LINK', 'RESOLVE', 'APPROVE', 'AUTO_DISPATCH',
    'UPDATE_SETTINGS'));
