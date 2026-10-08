-- =============================================================================
--  Coin prices by structure: PC on its own, PlayStation and Xbox together.
--
--  Until now a coin price was one rate_card row per platform per currency, and the
--  admin wrote the same number into all three. The client prices two markets: PC,
--  and PlayStation + Xbox as one -- FUT Transfer's own documentation treats its PS
--  market as including Xbox. The customer still picks PlayStation or Xbox; the two
--  read the same prices.
--
--  Each structure has, per season, one live version holding:
--    - the slider: minimum, maximum and step, and the quick-pick amounts, all in
--      whole thousands of coins (FUT Transfer takes amounts in K);
--    - per currency, a base rate per million (from_k = 0) and optional volume
--      brackets: from that amount on, every coin in the order at the bracket's rate.
--
--  A version is never edited. Saving closes the live one and opens the next, the
--  same rule rate_card follows, so any quote can be explained against the version
--  that priced it.
--
--  ---------------------------------------------------------------------------
--  THE RATES ARE COPIED FROM WHAT IS LIVE, NOT WRITTEN HERE.
--
--  They have been changed from the admin since V25, so the numbers in any
--  migration would be stale. PC comes from the PC rows; PlayStation + Xbox from the
--  PlayStation rows, and only when the Xbox rows agree on every currency's price
--  and range. If they differ the migration stops and names both, rather than
--  choosing one: picking a price is the business's decision.
--  ---------------------------------------------------------------------------
--
--  The minimum becomes 50K in both structures: the client's decision, matching FUT
--  Transfer's 50K minimum per transfer (GFS Transfer Method 3.0's minTransferAmount).
--  Maximum and step are carried over (1M and 10K today). The quick picks are
--  today's set -- 50K, 100K, 250K, 500K, 1M -- as far as the range reaches.
--
--  The old coin rows are closed, not deleted: they stay as the record of what was
--  on sale, and when.
-- =============================================================================

CREATE TABLE coin_price_card (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    season        TEXT        NOT NULL,
    market        TEXT        NOT NULL CHECK (market IN ('PC', 'CONSOLE')),
    -- Whole thousands. 10K is the finest step: orders store coins in millions to two
    -- decimal places, so anything finer would round.
    min_k         INT         NOT NULL CHECK (min_k >= 10 AND min_k % 10 = 0),
    max_k         INT         NOT NULL CHECK (max_k >= min_k AND max_k <= 10000),
    step_k        INT         NOT NULL CHECK (step_k >= 10 AND step_k % 10 = 0),
    quick_picks_k INT[]       NOT NULL DEFAULT '{}',
    valid_from    TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_to      TIMESTAMPTZ,
    created_by    BIGINT REFERENCES account (id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT coin_price_card_steps_ck CHECK ((max_k - min_k) % step_k = 0)
);

-- One live version per season and structure.
CREATE UNIQUE INDEX coin_price_card_live_idx ON coin_price_card (season, market) WHERE valid_to IS NULL;

CREATE TABLE coin_price_rate (
    card_id           BIGINT  NOT NULL REFERENCES coin_price_card (id),
    currency          CHAR(3) NOT NULL,
    -- 0 is the base rate. Anything else is a bracket starting at that amount, in K.
    from_k            INT     NOT NULL CHECK (from_k >= 0),
    per_million_minor BIGINT  NOT NULL CHECK (per_million_minor > 0),
    PRIMARY KEY (card_id, currency, from_k)
);

DO $$
DECLARE
    problem TEXT;
BEGIN
    -- PlayStation and Xbox become one price. Different prices or ranges: stop, naming both.
    SELECT string_agg(format('%s %s: PlayStation %s per million (minor units, range %s-%sM step %sM), Xbox %s (range %s-%sM step %sM)',
                             coalesce(p.season, x.season), coalesce(p.currency, x.currency),
                             coalesce(p.unit_price_minor::text, 'none'), p.min_quantity, p.max_quantity, p.step_quantity,
                             coalesce(x.unit_price_minor::text, 'none'), x.min_quantity, x.max_quantity, x.step_quantity),
                      '; ')
      INTO problem
      FROM (SELECT * FROM rate_card
             WHERE sku = 'TRADING_SERVICE' AND platform = 'PLAYSTATION' AND valid_to IS NULL) p
      FULL JOIN (SELECT * FROM rate_card
                  WHERE sku = 'TRADING_SERVICE' AND platform = 'XBOX' AND valid_to IS NULL) x
        ON x.season = p.season AND x.currency = p.currency
     WHERE p.id IS NULL OR x.id IS NULL
        OR p.unit_price_minor <> x.unit_price_minor
        OR p.max_quantity IS DISTINCT FROM x.max_quantity
        OR p.step_quantity IS DISTINCT FROM x.step_quantity;
    IF problem IS NOT NULL THEN
        RAISE EXCEPTION 'PlayStation and Xbox coin prices differ, so they cannot become one shared price. '
            'Nothing was changed. Set them equal first, then deploy again: %', problem;
    END IF;

    -- One range per structure: every currency's rows must agree on the maximum and the step.
    SELECT string_agg(season || ' ' || platform, ', ')
      INTO problem
      FROM (SELECT season, platform
              FROM rate_card
             WHERE sku = 'TRADING_SERVICE' AND valid_to IS NULL
             GROUP BY season, platform
            HAVING count(DISTINCT (max_quantity, step_quantity)) > 1) d;
    IF problem IS NOT NULL THEN
        RAISE EXCEPTION 'Coin price ranges differ between currencies for %, and a structure has one range. '
            'Nothing was changed.', problem;
    END IF;

    -- The 50K minimum must fit the range carried over: at most the maximum, and on its steps.
    SELECT string_agg(format('%s %s: maximum %sK, step %sK', season, platform,
                             (max_quantity * 1000)::int, (step_quantity * 1000)::int), '; ')
      INTO problem
      FROM rate_card
     WHERE sku = 'TRADING_SERVICE' AND valid_to IS NULL AND platform IN ('PC', 'PLAYSTATION')
       AND ((max_quantity * 1000)::int < 50
            OR (step_quantity * 1000)::int % 10 <> 0
            OR ((max_quantity * 1000)::int - 50) % (step_quantity * 1000)::int <> 0
            OR (max_quantity * 1000)::int > 10000);
    IF problem IS NOT NULL THEN
        RAISE EXCEPTION 'A 50K minimum does not fit the current coin range for %. Nothing was changed.', problem;
    END IF;
END $$;

INSERT INTO coin_price_card (season, market, min_k, max_k, step_k, quick_picks_k)
SELECT season, market, 50, max_k, step_k,
       ARRAY(SELECT k FROM unnest(ARRAY[50, 100, 250, 500, 1000]) AS k
              WHERE k BETWEEN 50 AND max_k AND (k - 50) % step_k = 0
              ORDER BY k)
  FROM (SELECT season,
               CASE platform WHEN 'PC' THEN 'PC' ELSE 'CONSOLE' END AS market,
               max((max_quantity * 1000)::int)  AS max_k,
               max((step_quantity * 1000)::int) AS step_k
          FROM rate_card
         WHERE sku = 'TRADING_SERVICE' AND valid_to IS NULL AND platform IN ('PC', 'PLAYSTATION')
         GROUP BY season, CASE platform WHEN 'PC' THEN 'PC' ELSE 'CONSOLE' END) live;

INSERT INTO coin_price_rate (card_id, currency, from_k, per_million_minor)
SELECT c.id, r.currency, 0, r.unit_price_minor
  FROM coin_price_card c
  JOIN rate_card r ON r.season = c.season
                  AND r.sku = 'TRADING_SERVICE'
                  AND r.valid_to IS NULL
                  AND r.platform = CASE c.market WHEN 'PC' THEN 'PC' ELSE 'PLAYSTATION' END;

UPDATE rate_card SET valid_to = now() WHERE sku = 'TRADING_SERVICE' AND valid_to IS NULL;
