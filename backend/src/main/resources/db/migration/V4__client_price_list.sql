-- =============================================================================
--  The client's actual price list, replacing the launch estimates.
--
--  Prices are TEMPORAL. Nothing here UPDATEs a price: the live row is closed with
--  valid_to = now() and a new one inserted. That keeps the history of what
--  anything cost reconstructable, so an order placed against the old list stays
--  explainable, and a mistaken change here is undone by making another one rather
--  than by restoring a backup.
--
--  Coin trading is untouched: PC Rs.700/M and console Rs.600/M were already right,
--  as is the 5% EA market tax (GFS_MARKET_TAX_BPS=500), so there is nothing to
--  change and no reason to churn the history.
-- =============================================================================

-- ------------------------------------------------------- champs boosting ------
--
--  Two corrections and a naming change.
--
--  12 wins was priced at Rs.2,300 against the client's Rs.2,100, and 13 wins at
--  Rs.2,700 against Rs.2,600 -- the launch seed interpolated the middle of the
--  ladder instead of using the real numbers. The other five were already correct.
--
--  Every label now carries the in-game rank the tier actually reaches. "12 wins"
--  is what the customer buys; "Elite IV" is what they are buying it FOR, and the
--  rank is the thing they recognise on the rewards screen.

UPDATE rate_card SET valid_to = now()
WHERE season = 'FC26' AND sku = 'BOOST_CHAMPS' AND currency = 'INR' AND valid_to IS NULL;

INSERT INTO rate_card
    (season, sku, platform, variant, currency, price_unit, unit_price_minor, label, sort_order)
VALUES
    ('FC26', 'BOOST_CHAMPS', NULL, 'WINS_9',  'INR', 'FLAT', 120000, '9 wins · Champion II', 1),
    ('FC26', 'BOOST_CHAMPS', NULL, 'WINS_10', 'INR', 'FLAT', 155000, '10 wins · Champion I', 2),
    ('FC26', 'BOOST_CHAMPS', NULL, 'WINS_11', 'INR', 'FLAT', 190000, '11 wins · Elite V',    3),
    ('FC26', 'BOOST_CHAMPS', NULL, 'WINS_12', 'INR', 'FLAT', 210000, '12 wins · Elite IV',   4),
    ('FC26', 'BOOST_CHAMPS', NULL, 'WINS_13', 'INR', 'FLAT', 260000, '13 wins · Elite III',  5),
    ('FC26', 'BOOST_CHAMPS', NULL, 'WINS_14', 'INR', 'FLAT', 310000, '14 wins · Elite II',   6),
    ('FC26', 'BOOST_CHAMPS', NULL, 'WINS_15', 'INR', 'FLAT', 355000, '15 wins · Elite I',    7);

-- Note what is NOT re-inserted: WINS_EXTRA_8. The client lists the extra-8-wins
-- add-on under Rivals, not Champs, so it moves below. Closing it here and opening
-- it there is the whole of that move -- see the flag in the handover notes, since
-- an extra-wins push is more commonly a Champs concept and this is worth
-- confirming with him before launch.

-- ------------------------------------------------------- rivals boosting ------
--
--  The launch seed had this ladder genuinely wrong, not merely imprecise. It took
--  the client's five prices and spread them across seven divisions, so Rs.750 --
--  his price for the 5-to-4 climb -- ended up on 8-to-7, and every tier above it
--  was quoted at roughly double what he charges. Division 5 to 4 was live at
--  Rs.1,500 against his Rs.750.
--
--  Divisions 8-7, 7-6 and 6-5 are retired rather than repriced. The client does
--  not sell them, and leaving them live at the old estimates would produce an
--  incoherent list where an easier climb costs more than a harder one: 6-to-5 at
--  Rs.1,200 sitting above 5-to-4 at Rs.750.

UPDATE rate_card SET valid_to = now()
WHERE season = 'FC26' AND sku = 'BOOST_RIVALS' AND currency = 'INR' AND valid_to IS NULL;

INSERT INTO rate_card
    (season, sku, platform, variant, currency, price_unit, unit_price_minor, label, sort_order)
VALUES
    ('FC26', 'BOOST_RIVALS', NULL, 'DIV_5_TO_4',     'INR', 'FLAT',  75000, 'Division 5 to 4',  1),
    ('FC26', 'BOOST_RIVALS', NULL, 'DIV_4_TO_3',     'INR', 'FLAT', 110000, 'Division 4 to 3',  2),
    ('FC26', 'BOOST_RIVALS', NULL, 'DIV_3_TO_2',     'INR', 'FLAT', 140000, 'Division 3 to 2',  3),
    ('FC26', 'BOOST_RIVALS', NULL, 'DIV_2_TO_1',     'INR', 'FLAT', 180000, 'Division 2 to 1',  4),
    ('FC26', 'BOOST_RIVALS', NULL, 'DIV_1_TO_ELITE', 'INR', 'FLAT', 290000, 'Division 1 to Elite', 5),
    ('FC26', 'BOOST_RIVALS', NULL, 'WINS_EXTRA_8',   'INR', 'FLAT', 170000, '+8 extra wins',    6);

-- --------------------------------------------------------------- coaching -----
--
--  The six-session month stays at Rs.4,050 -- already correct, so it is left
--  alone rather than rewritten for the sake of it.
--
--  The single session drops from Rs.900 to Rs.750, and the change is worth
--  explaining. Rs.900 was a derived guess made when the client had priced only the
--  block. His own framing prices the month at Rs.4,500 less 10%, which puts his
--  list rate at Rs.4,500 / 6 = Rs.750 a session. Charging that for a single one
--  makes the block exactly the 10% saving he advertises, instead of an arbitrary
--  25% that happens to fall out of a number nobody chose.

UPDATE rate_card SET valid_to = now()
WHERE season = 'FC26' AND sku = 'COACHING' AND currency = 'INR'
  AND variant = 'SINGLE_SESSION' AND valid_to IS NULL;

INSERT INTO rate_card
    (season, sku, platform, variant, currency, price_unit, unit_price_minor, label, sort_order)
VALUES
    ('FC26', 'COACHING', NULL, 'SINGLE_SESSION', 'INR', 'FLAT', 75000,
     'Single session · 40 min', 0);
