-- =============================================================================
--  V33 -- what an admin sets about a listing, beyond its price
--
--  Prices stay where they are, in rate_card, changed by closing a row and opening
--  another. These two tables hold the rest of what the Listings page edits.
--
--  listing_setting, one row per listing an admin has touched:
--   * the success rate shown under the price. Until an admin sets one here the
--     figure still comes from GFS_BOOSTING_SUCCESS_RATES, so nothing a customer
--     sees changes on deploy. success_rate_set says "an admin decided", which is
--     how "no rate" chosen on purpose differs from "never set".
--   * hidden_since, when an admin turned the listing off. Closed price rows alone
--     cannot say that: earlier migrations closed whole price lists they replaced,
--     and those old tiers are not listings anybody switched off.
--
--  listing_best_value, one row per service once an admin chooses: which listing
--  carries the Best Value tag, or NULL for none. With no row the last tier
--  carries it, as it always has.
-- =============================================================================

CREATE TABLE listing_setting (
    sku               TEXT        NOT NULL,
    variant           TEXT        NOT NULL,
    success_rate_bps  INT,
    success_rate_set  BOOLEAN     NOT NULL DEFAULT false,
    hidden_since      TIMESTAMPTZ,
    updated_by        BIGINT      REFERENCES account (id),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (sku, variant),
    CONSTRAINT listing_setting_rate_ck CHECK (success_rate_bps IS NULL OR success_rate_bps BETWEEN 1 AND 10000)
);

CREATE TABLE listing_best_value (
    sku         TEXT        PRIMARY KEY,
    variant     TEXT,
    updated_by  BIGINT      REFERENCES account (id),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
