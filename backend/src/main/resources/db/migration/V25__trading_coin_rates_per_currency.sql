-- =============================================================================
--  The owner's coin price in all four currencies, and an admin able to change it.
--
--  V24 priced coins in rupees and said plainly why it left the other three alone:
--  converting an INR price into three foreign ones "would be inventing three
--  numbers the business has not agreed". Those three numbers have now been
--  agreed. They are not conversions -- there is still no FX feed and this
--  migration does not introduce one -- they are four independently set prices
--  for the same 100,000 coins in four markets.
--
--      INR  Rs.1,600.00 per 100K  ->  Rs.16,000.00 per million  ->  1,600,000 paise
--      USD      $16.70 per 100K  ->       $167.00 per million  ->     16,700 cents
--      EUR      EUR 14.55 per 100K  ->    EUR 145.50 per million  ->     14,550 cents
--      GBP      GBP 12.45 per 100K  ->    GBP 124.50 per million  ->     12,450 pence
--
--  INR is unchanged. Its row is still closed and reopened with the others so that
--  all four carry the same valid_from and a quote can be explained against "the
--  card as it stood on the 19th" rather than against four separate effective
--  dates.
--
--  ---------------------------------------------------------------------------
--  ONE PRICE PER CURRENCY, NOT ONE PER PLATFORM.
--
--  The placeholder rows V7 and V2 carried charged PC more than console -- $8.99
--  against $7.99 per million, and the same shape in EUR and GBP. That spread was
--  never priced by the owner; it came in with the seed data. The brief sets a
--  single price per currency, so all three platforms now take it, which is what
--  V24 had already done for INR. If a platform premium is wanted back it is a
--  business decision and belongs in its own migration, not carried forward by
--  accident because nobody looked at the seed.
--  ---------------------------------------------------------------------------
--
--  ---------------------------------------------------------------------------
--  WHAT A 10,000-COIN STEP COSTS, AND WHERE IT LANDS ON A WHOLE MINOR UNIT.
--
--  step_quantity is 0.01 of a million, so one step is unit_price_minor / 100:
--
--      INR  16,000 paise   Rs.160.00    exact
--      USD     167 cents      $1.67     exact
--      EUR   145.5 cents      EUR 1.455   HALF A CENT
--      GBP   124.5 pence      GBP 1.245   HALF A PENNY
--
--  The engine is exact either way: PricingEngine multiplies in BigDecimal at 24
--  digits and rounds once, at the total. Nothing accumulates, so there is no
--  drift at step 73 to find later. But a half-cent cannot be charged, and on EUR
--  and GBP the rounding that has to happen somewhere lands on the displayed
--  total: consecutive steps differ by 1.45 then 1.46 rather than by 1.455 twice.
--  Over a hundred steps that is correct to the half cent and never more than one
--  minor unit from the exact figure -- it is rounding, not drift.
--
--  If the owner would rather every EUR/GBP step be a clean number, the fix is a
--  price ending in a whole number of cents per 10K (EUR 14.50 or EUR 14.60, GBP 12.40 or
--  GBP 12.50 per 100K), set from the admin screen. That is a pricing decision, so
--  it is left to the owner rather than rounded on their behalf here.
--  ---------------------------------------------------------------------------
--
--  Bounds are untouched at 0.01M to 1.00M in 0.01M steps -- 10,000 to 1,000,000
--  coins, ten thousand at a time. V24 already aligned all four currencies on
--  those, for the reason V6 and V15 gave: 10K coins is 10K coins in any currency.
--
--  Closed-and-reinserted rather than UPDATEd, following V4, V6, V15 and V24: the
--  price is part of the offer, so replacing the row keeps the record of what was
--  on sale when, and a quote signed against yesterday's row stays explicable.
--  This is the same mechanic AdminRateCardController uses, so a price set here
--  and a price set from the admin screen leave identical trails.
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

    ('FC26', 'TRADING_SERVICE', 'PC',          NULL, 'USD', 'PER_MILLION', 16700,
     0.01, 1.00, 0.01, 'PC', 1),
    ('FC26', 'TRADING_SERVICE', 'PLAYSTATION', NULL, 'USD', 'PER_MILLION', 16700,
     0.01, 1.00, 0.01, 'PlayStation', 2),
    ('FC26', 'TRADING_SERVICE', 'XBOX',        NULL, 'USD', 'PER_MILLION', 16700,
     0.01, 1.00, 0.01, 'Xbox', 3),

    ('FC26', 'TRADING_SERVICE', 'PC',          NULL, 'EUR', 'PER_MILLION', 14550,
     0.01, 1.00, 0.01, 'PC', 1),
    ('FC26', 'TRADING_SERVICE', 'PLAYSTATION', NULL, 'EUR', 'PER_MILLION', 14550,
     0.01, 1.00, 0.01, 'PlayStation', 2),
    ('FC26', 'TRADING_SERVICE', 'XBOX',        NULL, 'EUR', 'PER_MILLION', 14550,
     0.01, 1.00, 0.01, 'Xbox', 3),

    ('FC26', 'TRADING_SERVICE', 'PC',          NULL, 'GBP', 'PER_MILLION', 12450,
     0.01, 1.00, 0.01, 'PC', 1),
    ('FC26', 'TRADING_SERVICE', 'PLAYSTATION', NULL, 'GBP', 'PER_MILLION', 12450,
     0.01, 1.00, 0.01, 'PlayStation', 2),
    ('FC26', 'TRADING_SERVICE', 'XBOX',        NULL, 'GBP', 'PER_MILLION', 12450,
     0.01, 1.00, 0.01, 'Xbox', 3);
