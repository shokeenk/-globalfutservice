-- =============================================================================
--  USD, EUR and GBP price lists.
--
--  ============================ READ THIS FIRST ============================
--  Every non-INR price below is a DRAFT. They were derived from the client's
--  INR list at the reference rates stated under each block and rounded to
--  conventional retail endings. Nobody has approved them, and they are not
--  market-checked against what competitors charge in those countries.
--
--  Review them before taking a payment in any of these currencies. The INR list
--  came from the client; these came from arithmetic.
--  ========================================================================
--
--  Reference rates used (approximate, as authored):
--      1 USD = Rs.78      1 EUR = Rs.95      1 GBP = Rs.105
--
--  These rates are recorded here as documentation of how the numbers were
--  reached. They are NOT used at runtime. Prices stay authored per currency
--  precisely so that nothing is FX-converted on a live request -- runtime
--  conversion produces prices like Rs.58,133.41 and a fresh rounding bug on every
--  request. When the rate moves, someone decides whether to reprice; the
--  application never decides for them.
--
--  Coin trading in USD already exists (V2, recapped to 5M in V6) so it is not
--  repeated. Boosting and coaching had NO USD rows at all -- enabling USD before
--  this migration would have shown a catalogue with coins and nothing else.
-- =============================================================================

-- ============================== USD ==========================================
-- Rs.78 = $1. Coin trading omitted: already authored.

INSERT INTO rate_card
    (season, sku, platform, variant, currency, price_unit, unit_price_minor, label, sort_order)
VALUES
    ('FC26', 'BOOST_CHAMPS', NULL, 'WINS_9',  'USD', 'FLAT', 1499, '9 wins · Champion II', 1),
    ('FC26', 'BOOST_CHAMPS', NULL, 'WINS_10', 'USD', 'FLAT', 1999, '10 wins · Champion I', 2),
    ('FC26', 'BOOST_CHAMPS', NULL, 'WINS_11', 'USD', 'FLAT', 2399, '11 wins · Elite V',    3),
    ('FC26', 'BOOST_CHAMPS', NULL, 'WINS_12', 'USD', 'FLAT', 2699, '12 wins · Elite IV',   4),
    ('FC26', 'BOOST_CHAMPS', NULL, 'WINS_13', 'USD', 'FLAT', 3299, '13 wins · Elite III',  5),
    ('FC26', 'BOOST_CHAMPS', NULL, 'WINS_14', 'USD', 'FLAT', 3999, '14 wins · Elite II',   6),
    ('FC26', 'BOOST_CHAMPS', NULL, 'WINS_15', 'USD', 'FLAT', 4499, '15 wins · Elite I',    7),

    ('FC26', 'BOOST_RIVALS', NULL, 'DIV_5_TO_4',     'USD', 'FLAT',  999, 'Division 5 to 4',     1),
    ('FC26', 'BOOST_RIVALS', NULL, 'DIV_4_TO_3',     'USD', 'FLAT', 1399, 'Division 4 to 3',     2),
    ('FC26', 'BOOST_RIVALS', NULL, 'DIV_3_TO_2',     'USD', 'FLAT', 1799, 'Division 3 to 2',     3),
    ('FC26', 'BOOST_RIVALS', NULL, 'DIV_2_TO_1',     'USD', 'FLAT', 2299, 'Division 2 to 1',     4),
    ('FC26', 'BOOST_RIVALS', NULL, 'DIV_1_TO_ELITE', 'USD', 'FLAT', 3699, 'Division 1 to Elite', 5),
    ('FC26', 'BOOST_RIVALS', NULL, 'WINS_EXTRA_8',   'USD', 'FLAT', 2199, '+8 extra wins',       6),

    -- $9.99 x 6 = $59.94 list; the block at $53.99 is a 9.9% saving, which the
    -- storefront renders as "Save 10%". The badge is computed from these two
    -- numbers rather than written down, so it reports whatever is true here.
    ('FC26', 'COACHING', NULL, 'SINGLE_SESSION',     'USD', 'FLAT',  999, 'Single session · 40 min', 0),
    ('FC26', 'COACHING', NULL, 'MONTHLY_6_SESSIONS', 'USD', 'FLAT', 5399, '6 sessions × 40 min',     1);

-- ============================== EUR ==========================================
-- Rs.95 = EUR 1.

INSERT INTO rate_card
    (season, sku, platform, variant, currency, price_unit, unit_price_minor,
     min_quantity, max_quantity, step_quantity, label, sort_order)
VALUES
    ('FC26', 'TRADING_SERVICE', 'PC',          NULL, 'EUR', 'PER_MILLION', 749,
     0.50, 5.00, 0.50, 'PC', 1),
    ('FC26', 'TRADING_SERVICE', 'PLAYSTATION', NULL, 'EUR', 'PER_MILLION', 649,
     0.50, 5.00, 0.50, 'PlayStation', 2),
    ('FC26', 'TRADING_SERVICE', 'XBOX',        NULL, 'EUR', 'PER_MILLION', 649,
     0.50, 5.00, 0.50, 'Xbox', 3);

INSERT INTO rate_card
    (season, sku, platform, variant, currency, price_unit, unit_price_minor, label, sort_order)
VALUES
    ('FC26', 'BOOST_CHAMPS', NULL, 'WINS_9',  'EUR', 'FLAT', 1299, '9 wins · Champion II', 1),
    ('FC26', 'BOOST_CHAMPS', NULL, 'WINS_10', 'EUR', 'FLAT', 1649, '10 wins · Champion I', 2),
    ('FC26', 'BOOST_CHAMPS', NULL, 'WINS_11', 'EUR', 'FLAT', 1999, '11 wins · Elite V',    3),
    ('FC26', 'BOOST_CHAMPS', NULL, 'WINS_12', 'EUR', 'FLAT', 2199, '12 wins · Elite IV',   4),
    ('FC26', 'BOOST_CHAMPS', NULL, 'WINS_13', 'EUR', 'FLAT', 2699, '13 wins · Elite III',  5),
    ('FC26', 'BOOST_CHAMPS', NULL, 'WINS_14', 'EUR', 'FLAT', 3249, '14 wins · Elite II',   6),
    ('FC26', 'BOOST_CHAMPS', NULL, 'WINS_15', 'EUR', 'FLAT', 3699, '15 wins · Elite I',    7),

    ('FC26', 'BOOST_RIVALS', NULL, 'DIV_5_TO_4',     'EUR', 'FLAT',  799, 'Division 5 to 4',     1),
    ('FC26', 'BOOST_RIVALS', NULL, 'DIV_4_TO_3',     'EUR', 'FLAT', 1149, 'Division 4 to 3',     2),
    ('FC26', 'BOOST_RIVALS', NULL, 'DIV_3_TO_2',     'EUR', 'FLAT', 1449, 'Division 3 to 2',     3),
    ('FC26', 'BOOST_RIVALS', NULL, 'DIV_2_TO_1',     'EUR', 'FLAT', 1899, 'Division 2 to 1',     4),
    ('FC26', 'BOOST_RIVALS', NULL, 'DIV_1_TO_ELITE', 'EUR', 'FLAT', 2999, 'Division 1 to Elite', 5),
    ('FC26', 'BOOST_RIVALS', NULL, 'WINS_EXTRA_8',   'EUR', 'FLAT', 1799, '+8 extra wins',       6),

    -- EUR 7.99 x 6 = EUR 47.94 list; the block at EUR 42.99 saves 10.3% -> "Save 10%".
    ('FC26', 'COACHING', NULL, 'SINGLE_SESSION',     'EUR', 'FLAT',  799, 'Single session · 40 min', 0),
    ('FC26', 'COACHING', NULL, 'MONTHLY_6_SESSIONS', 'EUR', 'FLAT', 4299, '6 sessions × 40 min',     1);

-- ============================== GBP ==========================================
-- Rs.105 = GBP 1.

INSERT INTO rate_card
    (season, sku, platform, variant, currency, price_unit, unit_price_minor,
     min_quantity, max_quantity, step_quantity, label, sort_order)
VALUES
    ('FC26', 'TRADING_SERVICE', 'PC',          NULL, 'GBP', 'PER_MILLION', 699,
     0.50, 5.00, 0.50, 'PC', 1),
    ('FC26', 'TRADING_SERVICE', 'PLAYSTATION', NULL, 'GBP', 'PER_MILLION', 599,
     0.50, 5.00, 0.50, 'PlayStation', 2),
    ('FC26', 'TRADING_SERVICE', 'XBOX',        NULL, 'GBP', 'PER_MILLION', 599,
     0.50, 5.00, 0.50, 'Xbox', 3);

INSERT INTO rate_card
    (season, sku, platform, variant, currency, price_unit, unit_price_minor, label, sort_order)
VALUES
    ('FC26', 'BOOST_CHAMPS', NULL, 'WINS_9',  'GBP', 'FLAT', 1149, '9 wins · Champion II', 1),
    ('FC26', 'BOOST_CHAMPS', NULL, 'WINS_10', 'GBP', 'FLAT', 1499, '10 wins · Champion I', 2),
    ('FC26', 'BOOST_CHAMPS', NULL, 'WINS_11', 'GBP', 'FLAT', 1799, '11 wins · Elite V',    3),
    ('FC26', 'BOOST_CHAMPS', NULL, 'WINS_12', 'GBP', 'FLAT', 1999, '12 wins · Elite IV',   4),
    ('FC26', 'BOOST_CHAMPS', NULL, 'WINS_13', 'GBP', 'FLAT', 2499, '13 wins · Elite III',  5),
    ('FC26', 'BOOST_CHAMPS', NULL, 'WINS_14', 'GBP', 'FLAT', 2949, '14 wins · Elite II',   6),
    ('FC26', 'BOOST_CHAMPS', NULL, 'WINS_15', 'GBP', 'FLAT', 3399, '15 wins · Elite I',    7),

    ('FC26', 'BOOST_RIVALS', NULL, 'DIV_5_TO_4',     'GBP', 'FLAT',  749, 'Division 5 to 4',     1),
    ('FC26', 'BOOST_RIVALS', NULL, 'DIV_4_TO_3',     'GBP', 'FLAT', 1049, 'Division 4 to 3',     2),
    ('FC26', 'BOOST_RIVALS', NULL, 'DIV_3_TO_2',     'GBP', 'FLAT', 1349, 'Division 3 to 2',     3),
    ('FC26', 'BOOST_RIVALS', NULL, 'DIV_2_TO_1',     'GBP', 'FLAT', 1749, 'Division 2 to 1',     4),
    ('FC26', 'BOOST_RIVALS', NULL, 'DIV_1_TO_ELITE', 'GBP', 'FLAT', 2799, 'Division 1 to Elite', 5),
    ('FC26', 'BOOST_RIVALS', NULL, 'WINS_EXTRA_8',   'GBP', 'FLAT', 1649, '+8 extra wins',       6),

    -- GBP 7.49 x 6 = GBP 44.94 list; the block at GBP 40.49 saves 9.9% -> "Save 10%".
    ('FC26', 'COACHING', NULL, 'SINGLE_SESSION',     'GBP', 'FLAT',  749, 'Single session · 40 min', 0),
    ('FC26', 'COACHING', NULL, 'MONTHLY_6_SESSIONS', 'GBP', 'FLAT', 4049, '6 sessions × 40 min',     1);
