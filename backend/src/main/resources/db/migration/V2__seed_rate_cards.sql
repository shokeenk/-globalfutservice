-- =============================================================================
--  FC 26 launch rate cards.
--
--  Prices are AUTHORED per currency, never FX-converted at runtime. Converting on
--  the fly produces prices like Rs.58,133.41 and a fresh rounding bug on every
--  request; authoring lets each market get numbers a human chose.
--
--  A currency becomes available on the storefront simply by having live rows here,
--  so switching on a new market is a data change rather than a deploy.
--
--  No admin account is seeded. The first operator is created on boot from
--  GFS_BOOTSTRAP_ADMIN_EMAIL / GFS_BOOTSTRAP_ADMIN_PASSWORD, so no password hash
--  ever lands in version control.
-- =============================================================================

-- ------------------------------------------------- coin trading service (INR) ---
INSERT INTO rate_card
    (season, sku, platform, variant, currency, price_unit, unit_price_minor,
     min_quantity, max_quantity, step_quantity, label, sort_order)
VALUES
    ('FC26', 'TRADING_SERVICE', 'PC',          NULL, 'INR', 'PER_MILLION', 70000,
     0.50, 100.00, 0.50, 'PC', 1),
    ('FC26', 'TRADING_SERVICE', 'PLAYSTATION', NULL, 'INR', 'PER_MILLION', 60000,
     0.50, 100.00, 0.50, 'PlayStation', 2),
    ('FC26', 'TRADING_SERVICE', 'XBOX',        NULL, 'INR', 'PER_MILLION', 60000,
     0.50, 100.00, 0.50, 'Xbox', 3);

-- ------------------------------------------------- coin trading service (USD) ---
-- Authored for the diaspora audience the client's channel already reaches. Enable
-- only once Razorpay international is activated on the merchant account.
INSERT INTO rate_card
    (season, sku, platform, variant, currency, price_unit, unit_price_minor,
     min_quantity, max_quantity, step_quantity, label, sort_order)
VALUES
    ('FC26', 'TRADING_SERVICE', 'PC',          NULL, 'USD', 'PER_MILLION', 899,
     0.50, 100.00, 0.50, 'PC', 1),
    ('FC26', 'TRADING_SERVICE', 'PLAYSTATION', NULL, 'USD', 'PER_MILLION', 799,
     0.50, 100.00, 0.50, 'PlayStation', 2),
    ('FC26', 'TRADING_SERVICE', 'XBOX',        NULL, 'USD', 'PER_MILLION', 799,
     0.50, 100.00, 0.50, 'Xbox', 3);

-- --------------------------------------------------------- champs boosting -----
-- Flat rows keyed by variant, not a formula. The ladder is not linear and the
-- client re-tunes it between promos; a table absorbs that, an equation does not.
INSERT INTO rate_card
    (season, sku, platform, variant, currency, price_unit, unit_price_minor, label, sort_order)
VALUES
    ('FC26', 'BOOST_CHAMPS', NULL, 'WINS_9',       'INR', 'FLAT', 120000, '9 wins',  1),
    ('FC26', 'BOOST_CHAMPS', NULL, 'WINS_10',      'INR', 'FLAT', 155000, '10 wins', 2),
    ('FC26', 'BOOST_CHAMPS', NULL, 'WINS_11',      'INR', 'FLAT', 190000, '11 wins', 3),
    ('FC26', 'BOOST_CHAMPS', NULL, 'WINS_12',      'INR', 'FLAT', 230000, '12 wins', 4),
    ('FC26', 'BOOST_CHAMPS', NULL, 'WINS_13',      'INR', 'FLAT', 270000, '13 wins', 5),
    ('FC26', 'BOOST_CHAMPS', NULL, 'WINS_14',      'INR', 'FLAT', 310000, '14 wins', 6),
    ('FC26', 'BOOST_CHAMPS', NULL, 'WINS_15',      'INR', 'FLAT', 355000, '15 wins', 7),
    ('FC26', 'BOOST_CHAMPS', NULL, 'WINS_EXTRA_8', 'INR', 'FLAT', 170000, '+8 extra wins', 8);

-- --------------------------------------------------------- rivals boosting -----
INSERT INTO rate_card
    (season, sku, platform, variant, currency, price_unit, unit_price_minor, label, sort_order)
VALUES
    ('FC26', 'BOOST_RIVALS', NULL, 'DIV_8_TO_7', 'INR', 'FLAT',  75000, 'Division 8 to 7', 1),
    ('FC26', 'BOOST_RIVALS', NULL, 'DIV_7_TO_6', 'INR', 'FLAT',  95000, 'Division 7 to 6', 2),
    ('FC26', 'BOOST_RIVALS', NULL, 'DIV_6_TO_5', 'INR', 'FLAT', 120000, 'Division 6 to 5', 3),
    ('FC26', 'BOOST_RIVALS', NULL, 'DIV_5_TO_4', 'INR', 'FLAT', 150000, 'Division 5 to 4', 4),
    ('FC26', 'BOOST_RIVALS', NULL, 'DIV_4_TO_3', 'INR', 'FLAT', 185000, 'Division 4 to 3', 5),
    ('FC26', 'BOOST_RIVALS', NULL, 'DIV_3_TO_2', 'INR', 'FLAT', 225000, 'Division 3 to 2', 6),
    ('FC26', 'BOOST_RIVALS', NULL, 'DIV_2_TO_1', 'INR', 'FLAT', 290000, 'Division 2 to 1', 7);

-- ----------------------------------------------------------------- coaching ----
-- Priced and visible in the catalogue, but Sku.COACHING is not sellable yet, so the
-- storefront renders "Coming soon" and the pricing engine refuses to quote it. When
-- the booking system ships, one enum flag turns it on — the price is already here.
INSERT INTO rate_card
    (season, sku, platform, variant, currency, price_unit, unit_price_minor, label, sort_order)
VALUES
    ('FC26', 'COACHING', NULL, 'MONTHLY_6_SESSIONS', 'INR', 'FLAT', 405000,
     '6 sessions x 40 min', 1);
