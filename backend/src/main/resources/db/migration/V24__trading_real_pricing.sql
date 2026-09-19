-- =============================================================================
--  The real coin price, and the range it sells in.
--
--  Everything on the TRADING_SERVICE rate card up to now was placeholder data
--  carried forward from the V2 seed. This is the first row set the owner has
--  actually priced.
--
--  INR: 1,600,000 paise per million, which is Rs.16,000 per million, Rs.1,600 per
--  100K and Rs.160 per 10K. Every step on the slider therefore moves the base
--  price by exactly Rs.160 -- unit_price_minor is an integer and step_quantity is
--  exactly 0.01, so PricingEngine's BigDecimal multiply lands on a whole number of
--  paise at all 100 positions with nothing to round. That is checked in
--  TradingPriceLadderTest rather than asserted here.
--
--  Range: 0.01M to 1.00M, in 0.01M steps. 10,000 coins to 1,000,000, ten thousand
--  at a time.
--
--  That is a floor 50x lower and a ceiling 5x lower than the placeholder carried,
--  and both are deliberate. V15 explicitly left the floor at 500K because
--  lowering it was "a business decision, not a presentation one" and not a
--  developer's to make in a migration; this is that decision arriving. The
--  ceiling comes down because at the real price a 1M order is Rs.16,000, which is
--  already a larger single transaction than the 5M cap represented at the
--  placeholder rate.
--
--  Closed-and-reinserted rather than UPDATEd, following the rule V4 set for
--  prices and V6 and V15 set for bounds: the price and the bounds are both part of
--  the offer, so replacing the row keeps the record of what was on sale when. A
--  quote signed against yesterday's row stays explicable.
--
--  NOT CHANGED, AND DELIBERATELY: the USD, EUR and GBP prices. Only INR is in
--  GFS_CURRENCIES today, and the owner priced in rupees. Converting that into
--  three foreign prices would be inventing three numbers the business has not
--  agreed, which is worse than leaving them visibly stale for a currency nobody
--  can check out in. Their bounds move with INR's, because 10K coins is 10K coins
--  in any currency -- the same reason V6 and V15 moved every currency at once.
-- =============================================================================

UPDATE rate_card SET valid_to = now()
WHERE season = 'FC26' AND sku = 'TRADING_SERVICE' AND valid_to IS NULL;

INSERT INTO rate_card
    (season, sku, platform, variant, currency, price_unit, unit_price_minor,
     min_quantity, max_quantity, step_quantity, label, sort_order)
VALUES
    ('FC26', 'TRADING_SERVICE', 'PC',          NULL, 'INR', 'PER_MILLION', 1600000,
     0.01, 1.00, 0.01, 'PC', 1),
    ('FC26', 'TRADING_SERVICE', 'PLAYSTATION', NULL, 'INR', 'PER_MILLION', 1600000,
     0.01, 1.00, 0.01, 'PlayStation', 2),
    ('FC26', 'TRADING_SERVICE', 'XBOX',        NULL, 'INR', 'PER_MILLION', 1600000,
     0.01, 1.00, 0.01, 'Xbox', 3),

    ('FC26', 'TRADING_SERVICE', 'PC',          NULL, 'USD', 'PER_MILLION', 899,
     0.01, 1.00, 0.01, 'PC', 1),
    ('FC26', 'TRADING_SERVICE', 'PLAYSTATION', NULL, 'USD', 'PER_MILLION', 799,
     0.01, 1.00, 0.01, 'PlayStation', 2),
    ('FC26', 'TRADING_SERVICE', 'XBOX',        NULL, 'USD', 'PER_MILLION', 799,
     0.01, 1.00, 0.01, 'Xbox', 3),

    ('FC26', 'TRADING_SERVICE', 'PC',          NULL, 'EUR', 'PER_MILLION', 749,
     0.01, 1.00, 0.01, 'PC', 1),
    ('FC26', 'TRADING_SERVICE', 'PLAYSTATION', NULL, 'EUR', 'PER_MILLION', 649,
     0.01, 1.00, 0.01, 'PlayStation', 2),
    ('FC26', 'TRADING_SERVICE', 'XBOX',        NULL, 'EUR', 'PER_MILLION', 649,
     0.01, 1.00, 0.01, 'Xbox', 3),

    ('FC26', 'TRADING_SERVICE', 'PC',          NULL, 'GBP', 'PER_MILLION', 699,
     0.01, 1.00, 0.01, 'PC', 1),
    ('FC26', 'TRADING_SERVICE', 'PLAYSTATION', NULL, 'GBP', 'PER_MILLION', 599,
     0.01, 1.00, 0.01, 'PlayStation', 2),
    ('FC26', 'TRADING_SERVICE', 'XBOX',        NULL, 'GBP', 'PER_MILLION', 599,
     0.01, 1.00, 0.01, 'Xbox', 3);
