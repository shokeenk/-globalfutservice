-- =============================================================================
--  Coin orders move in 10K steps instead of 500K.
--
--  Prices, minimum and maximum are all unchanged. The only thing that changes is
--  the granularity a customer may choose between them: step_quantity 0.50 -> 0.01,
--  which is 500,000 coins -> 10,000 coins.
--
--  Why it was worth a migration rather than a slider attribute: the step is what
--  the order was *accepted against*. A quantity the storefront would not let you
--  pick is a quantity the pricing engine was never asked to price, and the value
--  lives on the rate card precisely so the two cannot disagree. Changing it in the
--  bundle would have left the server still willing to quote 0.5 increments only.
--
--  0.01 is exactly representable. step_quantity is NUMERIC(10,2), so 10K is the
--  finest granularity the column can hold -- 1K would silently round and produce a
--  slider whose values the server rejects.
--
--  Closed-and-reinserted rather than UPDATEd, following the rule V6 set for the
--  cap and V4 set for prices: the bounds are part of the offer, so replacing the
--  row keeps the record of what was on sale when.
--
--  All four currencies are done together. INR is the only one in GFS_CURRENCIES
--  today, but leaving USD, EUR and GBP on 500K steps would mean the granularity
--  silently reverts for those customers the day international is switched on --
--  the same trap V6 called out when it capped the USD rows nobody could buy yet.
--
--  NOT CHANGED, AND DELIBERATELY: min_quantity stays at 0.50 (500K). The brief
--  that asked for this gives "110K, 120K, 130K" as examples, and every one of
--  those is below the current floor -- so they remain unreachable after this
--  migration. Lowering the minimum is a business decision, not a presentation
--  one: at the INR rate a 110K order is roughly Rs.77, which is a question about
--  whether an order that small is worth a trader's time and a payment-gateway
--  transaction, and that is not a developer's call to make in a migration.
-- =============================================================================

UPDATE rate_card SET valid_to = now()
WHERE season = 'FC26' AND sku = 'TRADING_SERVICE' AND valid_to IS NULL;

INSERT INTO rate_card
    (season, sku, platform, variant, currency, price_unit, unit_price_minor,
     min_quantity, max_quantity, step_quantity, label, sort_order)
VALUES
    ('FC26', 'TRADING_SERVICE', 'PC',          NULL, 'INR', 'PER_MILLION', 70000,
     0.50, 5.00, 0.01, 'PC', 1),
    ('FC26', 'TRADING_SERVICE', 'PLAYSTATION', NULL, 'INR', 'PER_MILLION', 60000,
     0.50, 5.00, 0.01, 'PlayStation', 2),
    ('FC26', 'TRADING_SERVICE', 'XBOX',        NULL, 'INR', 'PER_MILLION', 60000,
     0.50, 5.00, 0.01, 'Xbox', 3),

    ('FC26', 'TRADING_SERVICE', 'PC',          NULL, 'USD', 'PER_MILLION', 899,
     0.50, 5.00, 0.01, 'PC', 1),
    ('FC26', 'TRADING_SERVICE', 'PLAYSTATION', NULL, 'USD', 'PER_MILLION', 799,
     0.50, 5.00, 0.01, 'PlayStation', 2),
    ('FC26', 'TRADING_SERVICE', 'XBOX',        NULL, 'USD', 'PER_MILLION', 799,
     0.50, 5.00, 0.01, 'Xbox', 3),

    ('FC26', 'TRADING_SERVICE', 'PC',          NULL, 'EUR', 'PER_MILLION', 749,
     0.50, 5.00, 0.01, 'PC', 1),
    ('FC26', 'TRADING_SERVICE', 'PLAYSTATION', NULL, 'EUR', 'PER_MILLION', 649,
     0.50, 5.00, 0.01, 'PlayStation', 2),
    ('FC26', 'TRADING_SERVICE', 'XBOX',        NULL, 'EUR', 'PER_MILLION', 649,
     0.50, 5.00, 0.01, 'Xbox', 3),

    ('FC26', 'TRADING_SERVICE', 'PC',          NULL, 'GBP', 'PER_MILLION', 699,
     0.50, 5.00, 0.01, 'PC', 1),
    ('FC26', 'TRADING_SERVICE', 'PLAYSTATION', NULL, 'GBP', 'PER_MILLION', 599,
     0.50, 5.00, 0.01, 'PlayStation', 2),
    ('FC26', 'TRADING_SERVICE', 'XBOX',        NULL, 'GBP', 'PER_MILLION', 599,
     0.50, 5.00, 0.01, 'Xbox', 3);
