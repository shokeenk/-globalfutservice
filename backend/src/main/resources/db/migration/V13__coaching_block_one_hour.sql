-- =============================================================================
--  Six-session block: back to one hour, because forty minutes was never sellable.
-- =============================================================================
--
--  V11 set this label to "40 min" on the reasoning that the block is priced at
--  Rs.750 per session against the single session's Rs.1,000, so the shorter label
--  reflected what the customer actually received.
--
--  It did not. Session length is not a property of the variant — it is a single
--  policy value, `gfs.coaching.session-length`, and `CoachingService` computes every
--  booking as `startsAt.plus(policy.sessionLength())` with no reference to which
--  product was bought. A block customer and a single-session customer picking the
--  same slot got a session of identical length. The label promised a distinction the
--  booking system has no way to make, which meant one of the two labels was wrong on
--  every order regardless of which number it carried.
--
--  That policy value is now 60m, matching the storefront copy ("One hour, live"),
--  the single session's own label, and the `@DefaultValue("60m")` the Java config
--  had all along — application.yml had been quietly overriding it to 40m, which is
--  why the length panel read "40 min" beside a paragraph promising an hour.
--
--  COMMERCIAL CONSEQUENCE, unchanged by this migration but worth restating where
--  someone will find it: the block stays at Rs.4,050 for six sessions. At an hour
--  each that is Rs.675/hour against the single session's Rs.1,000/hour — roughly a
--  33% saving, not the 10% the business advertises. V10 flagged this when it raised
--  the single session to Rs.1,000 and left the block alone. Nothing in the UI
--  hardcodes "10%" — the badge computes the percentage from these two rows — so the
--  storefront remains truthful either way. Re-pricing the block to Rs.5,400 (540000
--  minor) restores the 10% relationship, and is a decision for the client, not a
--  correction to make silently here.
--
--  Closed and re-inserted, never UPDATEd. A rate card is a temporal record: an order
--  placed yesterday has to keep resolving to the price it was actually sold at, and
--  an UPDATE would rewrite history for every order already referencing this row.
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
     '6 sessions × 1 hour', 1);
