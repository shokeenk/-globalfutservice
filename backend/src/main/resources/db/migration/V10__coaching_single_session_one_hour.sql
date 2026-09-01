-- =============================================================================
--  Single coaching session: Rs.1,000, and one hour rather than forty minutes.
-- =============================================================================
--
--  PRICE
--  -----
--  The live rate was Rs.750, chosen in V4 so that the six-session block worked out
--  at exactly the 10% saving the business advertises: Rs.4,500 list, Rs.4,050 block.
--  This raises the single session to Rs.1,000 at the client's instruction.
--
--  The block is deliberately NOT re-priced here, because that was not asked for.
--  The consequence is worth writing down rather than discovering later: six singles
--  now list at Rs.6,000 against a Rs.4,050 block, so the block's saving moves from
--  10% to roughly 33%. Nothing in the UI hardcodes "10%" — the badge computes the
--  percentage from these two rows — so the storefront stays truthful on its own. It
--  simply advertises a much larger discount than the business may have intended.
--  Re-pricing the block to Rs.5,400 would restore the 10% relationship.
--
--  Closed and re-inserted, never UPDATEd. A rate card is a temporal record: an order
--  placed yesterday has to keep resolving to the price it was actually sold at, and
--  an UPDATE would rewrite history for every order already referencing this row.
--
--  DURATION
--  --------
--  Forty minutes is not stored in this table — it is `gfs.coaching.session-length`,
--  which drives the slot planner, the booking grid and the length written onto every
--  session row. That default moves to 60m in the same change. What lives here is only
--  the human label, and it has to move with it or the price list will describe a
--  session the booking page does not offer.
--
--  Non-INR rows are left alone. Coaching is INR-only sellable, so the USD, EUR and
--  GBP rows are dormant records rather than anything a customer can reach; their
--  labels are corrected in the same pass as their prices, whenever those are set.
--  What the storefront actually renders is the i18n label, not this column.
-- =============================================================================

UPDATE rate_card SET valid_to = now()
WHERE season = 'FC26' AND sku = 'COACHING' AND currency = 'INR'
  AND variant = 'SINGLE_SESSION' AND valid_to IS NULL;

INSERT INTO rate_card
    (season, sku, platform, variant, currency, price_unit, unit_price_minor, label, sort_order)
VALUES
    ('FC26', 'COACHING', NULL, 'SINGLE_SESSION', 'INR', 'FLAT', 100000,
     'Single session · 1 hour', 0);

-- The block keeps its price and gains the corrected duration in its label.
UPDATE rate_card SET valid_to = now()
WHERE season = 'FC26' AND sku = 'COACHING' AND currency = 'INR'
  AND variant = 'MONTHLY_6_SESSIONS' AND valid_to IS NULL;

INSERT INTO rate_card
    (season, sku, platform, variant, currency, price_unit, unit_price_minor, label, sort_order)
VALUES
    ('FC26', 'COACHING', NULL, 'MONTHLY_6_SESSIONS', 'INR', 'FLAT', 405000,
     '6 sessions × 1 hour', 1);
