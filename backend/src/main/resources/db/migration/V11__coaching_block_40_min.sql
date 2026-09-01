-- =============================================================================
--  Six-session block: revert session label to 40 minutes.
-- =============================================================================
--
--  The block's per-session duration returns to 40 minutes. V10 had moved it to
--  "1 hour" to match the single session, but the block is priced at Rs.750 per
--  session (Rs.4,500 list, 10% off → Rs.4,050), not at the single session's
--  Rs.1,000 rate. The label should reflect what the customer actually receives.
--
--  The single session remains at 1 hour / Rs.1,000 — only the block changes.
--
--  Price is unchanged (Rs.4,050 = 405000 minor). Only the label moves.
-- =============================================================================

UPDATE rate_card SET valid_to = now()
WHERE season = 'FC26' AND sku = 'COACHING' AND currency = 'INR'
  AND variant = 'MONTHLY_6_SESSIONS' AND valid_to IS NULL;

INSERT INTO rate_card
    (season, sku, platform, variant, currency, price_unit, unit_price_minor, label, sort_order)
VALUES
    ('FC26', 'COACHING', NULL, 'MONTHLY_6_SESSIONS', 'INR', 'FLAT', 405000,
     '6 sessions × 40 min', 1);
