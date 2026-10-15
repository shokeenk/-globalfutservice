-- =============================================================================
--  V51: a partner word we do not know never freezes an order
--
--  vendor_order.unknown_words_alerted
--      The undocumented words this order has already alerted admins about, as
--      'field:word' (e.g. 'status:transfersinprogress'). An order under review for a
--      word we did not know is still read; the same word again alerts nobody, and a
--      new one alerts once.
--
--  Backfill: orders already under review for an unknown word were alerted about the
--  word they hold now, so it is recorded as alerted.
-- =============================================================================

ALTER TABLE vendor_order ADD COLUMN unknown_words_alerted TEXT[] NOT NULL DEFAULT '{}';

UPDATE vendor_order
   SET unknown_words_alerted = ARRAY[
           CASE last_error_code
               WHEN 'UNKNOWN_STATUS'        THEN 'status:' || lower(trim(coalesce(vendor_status, '')))
               WHEN 'UNKNOWN_ACCOUNT_CHECK' THEN 'accountCheck:' || lower(trim(coalesce(vendor_account_check, '')))
               WHEN 'UNKNOWN_ECONOMY_STATE' THEN 'economyState:' || lower(trim(coalesce(vendor_economy_state, '')))
           END]
 WHERE state = 'NEEDS_REVIEW'
   AND last_error_code IN ('UNKNOWN_STATUS', 'UNKNOWN_ACCOUNT_CHECK', 'UNKNOWN_ECONOMY_STATE');
