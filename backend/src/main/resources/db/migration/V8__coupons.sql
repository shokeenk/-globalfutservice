-- =============================================================================
--  Coupon codes.
--
--  A coupon is a direct instruction to charge somebody less, so the rules that
--  bound it are enforced in the database as well as in the domain and the admin
--  form. The ceiling in particular is stated in three places on purpose: a form
--  is one crafted request away from being bypassed, and application code is one
--  psql session away from being bypassed.
-- =============================================================================

CREATE TABLE coupon (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- Stored upper case. Codes are read off a stream overlay and typed by hand,
    -- so "vinu20" and "VINU20" are the same code; a case-sensitive column would
    -- let both exist and the uniqueness index would be decorative.
    code              TEXT        NOT NULL UNIQUE,
    discount_bps      INT         NOT NULL,
    description       TEXT,

    -- NULL means unlimited. A count rather than a boolean because "how many are
    -- left" is the question the admin screen actually asks.
    max_redemptions   INT,
    redeemed_count    INT         NOT NULL DEFAULT 0,

    -- How many times one account may use it. Defaults to once, which is what a
    -- promo code almost always means, and stops a single customer draining a
    -- 500-redemption campaign on their own.
    max_per_account   INT         NOT NULL DEFAULT 1,

    -- Minimum subtotal for the code to apply, in minor units. 0 for none.
    min_order_minor   BIGINT      NOT NULL DEFAULT 0,

    -- NULL means it never expires.
    expires_at        TIMESTAMPTZ,

    -- Switched off without deleting: an expired campaign still has to explain the
    -- orders it discounted, and a deleted coupon row would orphan them.
    active            BOOLEAN     NOT NULL DEFAULT TRUE,

    created_by        BIGINT REFERENCES account (id),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- 20% is the ceiling. Repeated from Coupon.MAX_DISCOUNT_BPS because this is the
    -- layer that holds when everything above it is wrong.
    CONSTRAINT coupon_discount_ck  CHECK (discount_bps BETWEEN 1 AND 2000),
    CONSTRAINT coupon_shape_ck     CHECK (code ~ '^[A-Z0-9_-]{3,32}$'),
    CONSTRAINT coupon_limits_ck    CHECK (max_redemptions IS NULL OR max_redemptions > 0),
    CONSTRAINT coupon_per_acct_ck  CHECK (max_per_account > 0),
    CONSTRAINT coupon_min_order_ck CHECK (min_order_minor >= 0),
    -- The counter can never exceed the cap. This is what makes the atomic
    -- increment in CouponService safe to rely on rather than merely likely to work.
    CONSTRAINT coupon_count_ck     CHECK (
        max_redemptions IS NULL OR redeemed_count <= max_redemptions)
);

CREATE INDEX coupon_active_ix ON coupon (active, expires_at) WHERE active;

-- ---- redemptions ------------------------------------------------------------
--
-- One row per order that used a coupon. This table, not the counter on `coupon`,
-- is the record of what actually happened -- the counter is a denormalised total
-- kept for the redemption limit, and it can be rebuilt from these rows.

CREATE TABLE coupon_redemption (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    coupon_id   BIGINT      NOT NULL REFERENCES coupon (id),
    -- UNIQUE, not merely indexed: an order may carry at most one coupon, ever.
    -- A retried order-creation request that got past the quote replay guard still
    -- cannot double-redeem, because this constraint refuses the second row.
    order_id    BIGINT      NOT NULL UNIQUE REFERENCES orders (id),
    account_id  BIGINT REFERENCES account (id),
    -- What it was worth on this order, frozen. The coupon's percentage can be
    -- edited afterwards; what a given customer actually received cannot.
    discount_bps      INT   NOT NULL,
    discount_minor    BIGINT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Backs the per-account limit check.
CREATE INDEX coupon_redemption_account_ix ON coupon_redemption (coupon_id, account_id)
    WHERE account_id IS NOT NULL;

-- ---- the order's side of it -------------------------------------------------
-- Recorded on the order too, so the queue and the invoice can name the coupon
-- without a join, and so an order keeps its coupon even if the coupon row is
-- later deactivated.
ALTER TABLE orders ADD COLUMN coupon_code TEXT;
