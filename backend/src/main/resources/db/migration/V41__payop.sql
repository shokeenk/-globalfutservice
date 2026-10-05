-- =============================================================================
--  Payop: the international gateway behind the checkout's International tab.
--
--  Four things, all new; nothing existing changes except two nullable columns on
--  orders.
--
--  payop_fee_method  What each Payop method costs, from the client's Payop pricing
--                    sheet: a fixed part in EUR plus a percentage. Imported, then
--                    edited by an admin; never hard-coded, never in git.
--  payop_fee_audit   Every change to that table, who made it and what it was before.
--  fx_rate           EUR rates, for one purpose only: turning the fixed EUR part of
--                    a fee into the order's currency. The ECB's daily rates, with
--                    rates an admin enters as the fallback. Prices themselves are
--                    still authored per currency and never converted.
--  payop_invoice     One row per attempt to pay an order through Payop, holding the
--                    fee exactly as the customer was shown it. A later fee change
--                    never alters an old attempt.
--
--  Payments themselves land in the existing payment table (provider 'PAYOP'), and
--  IPN deliveries in webhook_event, so the existing idempotency applies to both.
-- =============================================================================

CREATE TABLE payop_fee_method (
    method_id     BIGINT        PRIMARY KEY,           -- Payop's payment method identifier
    name          TEXT          NOT NULL,
    method_type   TEXT          NOT NULL,              -- ewallet, bank_transfer, cash, crypto...
    region        TEXT,                                -- the sheet's region heading
    fixed_eur     NUMERIC(10,2) NOT NULL CHECK (fixed_eur >= 0),
    percent       NUMERIC(6,3)  NOT NULL CHECK (percent >= 0 AND percent < 100),
    -- ISO 3166-1 alpha-2 codes, comma-separated, or '*' for every country.
    countries     TEXT          NOT NULL,
    -- The method's processing currencies, as the sheet lists them. Informational:
    -- Payop converts from the order's currency itself.
    currencies    TEXT          NOT NULL,
    active        BOOLEAN       NOT NULL DEFAULT TRUE,
    version       INT           NOT NULL DEFAULT 1 CHECK (version >= 1),
    updated_by    BIGINT        REFERENCES account (id),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE payop_fee_audit (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    method_id     BIGINT        NOT NULL,
    version       INT           NOT NULL,
    action        TEXT          NOT NULL CHECK (action IN ('IMPORTED', 'UPDATED')),
    before_json   JSONB,
    after_json    JSONB         NOT NULL,
    actor_id      BIGINT        REFERENCES account (id),
    at            TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX payop_fee_audit_method_ix ON payop_fee_audit (method_id, at DESC);

CREATE TABLE fx_rate (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    base          CHAR(3)       NOT NULL DEFAULT 'EUR' CHECK (base = 'EUR'),
    quote         CHAR(3)       NOT NULL,
    rate          NUMERIC(18,8) NOT NULL CHECK (rate > 0),
    source        TEXT          NOT NULL CHECK (source IN ('ECB', 'ADMIN')),
    rate_date     DATE          NOT NULL,              -- the ECB's date, or the admin's
    fetched_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    entered_by    BIGINT        REFERENCES account (id)
);
-- One ECB rate per currency per day; admin rates are a history, newest wins.
CREATE UNIQUE INDEX fx_rate_ecb_day_uk ON fx_rate (quote, rate_date) WHERE source = 'ECB';
CREATE INDEX fx_rate_lookup_ix ON fx_rate (quote, source, rate_date DESC, id DESC);

CREATE TABLE payop_invoice (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id        BIGINT        NOT NULL REFERENCES orders (id),
    -- Ours. Sent to Payop as metadata and returned in the IPN, so a reply always
    -- names the attempt it belongs to, even after the order is retried.
    attempt_id      UUID          NOT NULL UNIQUE,
    -- Payop's, from the create call's identifier header. Null until Payop answers.
    invoice_id      TEXT          UNIQUE,
    status          TEXT          NOT NULL CHECK (status IN
                        ('CREATING', 'OPEN', 'PAID', 'FAILED', 'EXPIRED', 'REVIEW', 'DUPLICATE')),
    -- The fee exactly as the customer was shown it.
    method_id       BIGINT        NOT NULL,
    method_name     TEXT          NOT NULL,
    method_version  INT           NOT NULL,
    fixed_eur       NUMERIC(10,2) NOT NULL,
    percent         NUMERIC(6,3)  NOT NULL,
    fx_rate         NUMERIC(18,8) NOT NULL,            -- EUR -> currency; 1 for EUR
    fx_source       TEXT          NOT NULL,
    fx_date         DATE          NOT NULL,
    currency        CHAR(3)       NOT NULL,
    net_minor       BIGINT        NOT NULL CHECK (net_minor > 0),
    fee_minor       BIGINT        NOT NULL CHECK (fee_minor >= 0),
    total_minor     BIGINT        NOT NULL CHECK (total_minor = net_minor + fee_minor),
    -- The exact order.amount string that was signed and sent ("12.34").
    amount_sent     TEXT          NOT NULL,
    country         CHAR(2),
    txid            TEXT,
    review_reason   TEXT,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ   NOT NULL,
    paid_at         TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);
-- One active attempt per order, enforced by the database, not by hoping two requests
-- never race. An attempt past its 24 hours is moved to EXPIRED before a new one starts.
CREATE UNIQUE INDEX payop_invoice_one_active_uk ON payop_invoice (order_id)
    WHERE status IN ('CREATING', 'OPEN');
CREATE INDEX payop_invoice_order_ix ON payop_invoice (order_id, created_at DESC);
CREATE INDEX payop_invoice_review_ix ON payop_invoice (status) WHERE status IN ('REVIEW', 'DUPLICATE');

-- The order keeps the quote it was accepted at (price_breakdown) untouched. When Payop
-- pays it, these say which attempt did and what that payment's fee was; total_minor
-- becomes the amount actually charged.
ALTER TABLE orders ADD COLUMN payop_invoice_id BIGINT REFERENCES payop_invoice (id);
ALTER TABLE orders ADD COLUMN payment_fee JSONB;
