-- =============================================================================
--  Cap the coin trading order at 5M.
--
--  Prices are unchanged. What changes is the size of order the business is
--  willing to take in one go, which is a real decision: a 100M order is several
--  days of a trader's time and a far larger sum sitting in one chargeable
--  transaction. Capping it at 5M keeps each order inside a day's work.
--
--  Closed-and-reinserted rather than UPDATEd, following the same rule prices do.
--  The bounds are part of the offer, not merely presentation -- a row saying "up
--  to 100M" is what an order for 80M was accepted against -- so replacing the row
--  keeps the record of what was on sale when, exactly as it does for a price.
--
--  Both currencies are capped. USD is not live yet (only INR is in
--  GFS_CURRENCIES), but leaving the USD rows at 100M would mean the cap silently
--  disappears the day international is switched on, which is precisely the sort of
--  surprise that turns up as a 100M order nobody can fulfil.
-- =============================================================================

UPDATE rate_card SET valid_to = now()
WHERE season = 'FC26' AND sku = 'TRADING_SERVICE' AND valid_to IS NULL;

INSERT INTO rate_card
    (season, sku, platform, variant, currency, price_unit, unit_price_minor,
     min_quantity, max_quantity, step_quantity, label, sort_order)
VALUES
    ('FC26', 'TRADING_SERVICE', 'PC',          NULL, 'INR', 'PER_MILLION', 70000,
     0.50, 5.00, 0.50, 'PC', 1),
    ('FC26', 'TRADING_SERVICE', 'PLAYSTATION', NULL, 'INR', 'PER_MILLION', 60000,
     0.50, 5.00, 0.50, 'PlayStation', 2),
    ('FC26', 'TRADING_SERVICE', 'XBOX',        NULL, 'INR', 'PER_MILLION', 60000,
     0.50, 5.00, 0.50, 'Xbox', 3),

    ('FC26', 'TRADING_SERVICE', 'PC',          NULL, 'USD', 'PER_MILLION', 899,
     0.50, 5.00, 0.50, 'PC', 1),
    ('FC26', 'TRADING_SERVICE', 'PLAYSTATION', NULL, 'USD', 'PER_MILLION', 799,
     0.50, 5.00, 0.50, 'PlayStation', 2),
    ('FC26', 'TRADING_SERVICE', 'XBOX',        NULL, 'USD', 'PER_MILLION', 799,
     0.50, 5.00, 0.50, 'Xbox', 3);
