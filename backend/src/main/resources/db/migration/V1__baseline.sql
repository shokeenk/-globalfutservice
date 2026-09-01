-- =============================================================================
--  Global FUT Services — baseline schema
--
--  Conventions used throughout:
--    * Money is BIGINT minor units (paise). Never NUMERIC-as-rupees, never float.
--    * Timestamps are TIMESTAMPTZ. A server that moves region must not reinterpret
--      when an order was delivered.
--    * Every customer-facing identifier is random, never the primary key.
--    * Deletes are avoided in favour of terminal states and audit rows.
-- =============================================================================

-- ---------------------------------------------------------------- accounts ---
CREATE TABLE account (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id           TEXT        NOT NULL UNIQUE,
    email               TEXT        NOT NULL,
    email_normalised    TEXT        NOT NULL UNIQUE,
    phone               TEXT,
    display_name        TEXT,
    password_hash       TEXT,
    role                TEXT        NOT NULL DEFAULT 'CUSTOMER',
    oauth_provider      TEXT,
    oauth_subject       TEXT,
    email_verified_at   TIMESTAMPTZ,
    referred_by_code    TEXT,
    first_completed_at  TIMESTAMPTZ,
    failed_login_count  INT         NOT NULL DEFAULT 0,
    locked_until        TIMESTAMPTZ,
    disabled_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT account_role_ck CHECK (role IN ('CUSTOMER', 'OPERATOR', 'ADMIN')),
    -- Either a password or a federated identity, but an account with neither can
    -- never be signed into and is almost always a bug.
    CONSTRAINT account_credential_ck CHECK (password_hash IS NOT NULL OR oauth_subject IS NOT NULL)
);
CREATE UNIQUE INDEX account_oauth_uk ON account (oauth_provider, oauth_subject)
    WHERE oauth_subject IS NOT NULL;
CREATE INDEX account_referred_by_ix ON account (referred_by_code) WHERE referred_by_code IS NOT NULL;

-- Refresh tokens are stored hashed. A database dump must not hand the reader a set
-- of working sessions.
CREATE TABLE refresh_token (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id    BIGINT      NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    token_hash    TEXT        NOT NULL UNIQUE,
    family_id     TEXT        NOT NULL,
    issued_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ NOT NULL,
    used_at       TIMESTAMPTZ,
    revoked_at    TIMESTAMPTZ,
    user_agent    TEXT,
    ip_hash       TEXT
);
CREATE INDEX refresh_token_account_ix ON refresh_token (account_id);
CREATE INDEX refresh_token_family_ix ON refresh_token (family_id);

-- --------------------------------------------------------------- catalogue ---
-- Temporal: a price change closes the current row and inserts a new one, so the
-- history of what anything cost is always reconstructable.
CREATE TABLE rate_card (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    season         TEXT        NOT NULL,
    sku            TEXT        NOT NULL,
    platform       TEXT,
    variant        TEXT,
    currency       CHAR(3)     NOT NULL,
    price_unit     TEXT        NOT NULL,
    unit_price_minor BIGINT    NOT NULL CHECK (unit_price_minor >= 0),
    min_quantity   NUMERIC(10,2),
    max_quantity   NUMERIC(10,2),
    step_quantity  NUMERIC(10,2),
    label          TEXT,
    sort_order     INT         NOT NULL DEFAULT 0,
    valid_from     TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_to       TIMESTAMPTZ,
    created_by     BIGINT REFERENCES account (id),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT rate_card_unit_ck CHECK (price_unit IN ('PER_MILLION', 'FLAT'))
);
-- At most one live row per sellable combination. This is the constraint that stops
-- two conflicting prices existing at once, which is otherwise very hard to notice.
CREATE UNIQUE INDEX rate_card_live_uk
    ON rate_card (season, sku, COALESCE(platform, '-'), COALESCE(variant, '-'), currency)
    WHERE valid_to IS NULL;
CREATE INDEX rate_card_lookup_ix ON rate_card (sku, currency) WHERE valid_to IS NULL;

-- ------------------------------------------------------------------ orders ---
CREATE TABLE orders (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_ref            TEXT        NOT NULL UNIQUE,
    account_id            BIGINT REFERENCES account (id),
    guest_email           TEXT,
    guest_phone           TEXT,
    season                TEXT        NOT NULL,
    sku                   TEXT        NOT NULL,
    platform              TEXT,
    variant               TEXT,
    quantity              NUMERIC(10,2) NOT NULL,
    delivery_method       TEXT        NOT NULL,
    currency              CHAR(3)     NOT NULL,
    subtotal_minor        BIGINT      NOT NULL,
    total_minor           BIGINT      NOT NULL CHECK (total_minor >= 0),
    price_breakdown       JSONB       NOT NULL,
    points_redeemed       BIGINT      NOT NULL DEFAULT 0 CHECK (points_redeemed >= 0),
    points_earned         BIGINT      NOT NULL DEFAULT 0 CHECK (points_earned >= 0),
    points_settled_at     TIMESTAMPTZ,
    referral_code         TEXT,
    status                TEXT        NOT NULL,
    -- One quote may become at most one order, ever. This single constraint is what
    -- makes a stateless signed quote safe against replay.
    quote_id              TEXT        NOT NULL UNIQUE,
    ea_platform_handle    TEXT,
    customer_note         TEXT,
    operator_note         TEXT,
    delivered_at          TIMESTAMPTZ,
    guarantee_expires_at  TIMESTAMPTZ,
    completed_at          TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    version               BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT orders_delivery_ck CHECK (delivery_method IN ('PLAYER_AUCTION', 'COMFORT_TRADE')),
    -- Guest orders still need a way to be contacted.
    CONSTRAINT orders_contact_ck CHECK (account_id IS NOT NULL OR guest_email IS NOT NULL),
    -- delivered_at and the guarantee window are set together or not at all.
    CONSTRAINT orders_guarantee_ck CHECK (
        (delivered_at IS NULL AND guarantee_expires_at IS NULL)
        OR (delivered_at IS NOT NULL AND guarantee_expires_at IS NOT NULL))
);
CREATE INDEX orders_account_ix ON orders (account_id, created_at DESC);
CREATE INDEX orders_status_ix ON orders (status, created_at);
CREATE INDEX orders_referral_ix ON orders (referral_code) WHERE referral_code IS NOT NULL;
-- Partial index for the two scheduled jobs, so neither ever scans the whole table.
CREATE INDEX orders_guarantee_due_ix ON orders (guarantee_expires_at)
    WHERE status = 'DELIVERED';
CREATE INDEX orders_open_queue_ix ON orders (created_at)
    WHERE status IN ('PAID', 'CREDENTIALS_PENDING', 'READY_FOR_DELIVERY', 'IN_PROGRESS', 'ON_HOLD');

-- Append-only audit trail. Every status change writes one row: this is the admin
-- timeline, the chargeback evidence pack, and the answer to "who did that?".
CREATE TABLE order_event (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id     BIGINT      NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    from_status  TEXT,
    to_status    TEXT        NOT NULL,
    actor_type   TEXT        NOT NULL,
    actor_id     BIGINT,
    actor_label  TEXT,
    reason       TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT order_event_actor_ck CHECK (actor_type IN ('CUSTOMER', 'OPERATOR', 'SYSTEM'))
);
CREATE INDEX order_event_order_ix ON order_event (order_id, created_at);

-- ---------------------------------------------------------------- payments ---
CREATE TABLE payment (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id             BIGINT      NOT NULL REFERENCES orders (id),
    provider             TEXT        NOT NULL DEFAULT 'RAZORPAY',
    provider_order_id    TEXT        NOT NULL,
    provider_payment_id  TEXT,
    method               TEXT,
    amount_minor         BIGINT      NOT NULL,
    currency             CHAR(3)     NOT NULL,
    status               TEXT        NOT NULL,
    failure_reason       TEXT,
    refunded_minor       BIGINT      NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX payment_provider_order_uk ON payment (provider, provider_order_id);
CREATE UNIQUE INDEX payment_provider_payment_uk ON payment (provider, provider_payment_id)
    WHERE provider_payment_id IS NOT NULL;
CREATE INDEX payment_order_ix ON payment (order_id);

-- Gateway webhooks retry. This table is the idempotency key: a delivery seen twice
-- is inserted once and the second attempt short-circuits.
CREATE TABLE webhook_event (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    provider           TEXT        NOT NULL,
    provider_event_id  TEXT        NOT NULL,
    event_type         TEXT,
    payload            JSONB       NOT NULL,
    received_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at       TIMESTAMPTZ,
    process_error      TEXT
);
CREATE UNIQUE INDEX webhook_event_uk ON webhook_event (provider, provider_event_id);

-- ------------------------------------------------------- credential vault ---
-- Holds a customer's EA sign-in for the duration of a comfort-trade order and not
-- one minute longer. Envelope-encrypted: per-row data key, sealed with a master key
-- that lives only in the environment.
CREATE TABLE credential_vault (
    order_id       BIGINT      PRIMARY KEY REFERENCES orders (id) ON DELETE CASCADE,
    ciphertext     BYTEA,
    iv             BYTEA,
    wrapped_dek    BYTEA,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    purge_after    TIMESTAMPTZ NOT NULL,
    purged_at      TIMESTAMPTZ,
    accessed_count INT         NOT NULL DEFAULT 0,
    last_accessed_at TIMESTAMPTZ,
    last_accessed_by BIGINT REFERENCES account (id),
    -- Once purged, no key material may remain behind.
    CONSTRAINT credential_purged_ck CHECK (
        purged_at IS NULL OR (ciphertext IS NULL AND wrapped_dek IS NULL AND iv IS NULL))
);
CREATE INDEX credential_purge_due_ix ON credential_vault (purge_after) WHERE purged_at IS NULL;

-- ----------------------------------------------------------------- loyalty ---
-- Append-only. The balance is the sum of entries, never a mutable column, so a
-- disputed total is always reconstructable.
CREATE TABLE points_ledger (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id  BIGINT      NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    order_id    BIGINT REFERENCES orders (id),
    entry_type  TEXT        NOT NULL,
    amount      BIGINT      NOT NULL,
    description TEXT,
    actor_id    BIGINT REFERENCES account (id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT points_entry_ck CHECK (entry_type IN
        ('EARNED', 'REDEEMED', 'REFUND_REVERSAL', 'CLAWBACK', 'MANUAL_ADJUSTMENT'))
);
CREATE INDEX points_ledger_account_ix ON points_ledger (account_id, created_at DESC);
-- An order may only ever earn once and redeem once, whatever a retry does.
CREATE UNIQUE INDEX points_ledger_order_type_uk ON points_ledger (order_id, entry_type)
    WHERE order_id IS NOT NULL;

-- --------------------------------------------------------------- affiliate ---
CREATE TABLE affiliate (
    id                       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id               BIGINT      NOT NULL REFERENCES account (id),
    code                     TEXT        NOT NULL,
    code_normalised          TEXT        NOT NULL UNIQUE,
    display_name             TEXT,
    channels                 TEXT,
    commission_bps           INT         NOT NULL DEFAULT 1000
                                CHECK (commission_bps BETWEEN 0 AND 5000),
    first_order_discount_bps INT         NOT NULL DEFAULT 1300
                                CHECK (first_order_discount_bps BETWEEN 0 AND 5000),
    status                   TEXT        NOT NULL DEFAULT 'PENDING',
    payout_details           TEXT,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_at              TIMESTAMPTZ,
    CONSTRAINT affiliate_status_ck CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED'))
);
CREATE INDEX affiliate_account_ix ON affiliate (account_id);

CREATE TABLE affiliate_ledger (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    affiliate_id     BIGINT      NOT NULL REFERENCES affiliate (id),
    order_id         BIGINT      NOT NULL REFERENCES orders (id),
    gross_minor      BIGINT      NOT NULL,
    commission_minor BIGINT      NOT NULL,
    currency         CHAR(3)     NOT NULL,
    status           TEXT        NOT NULL DEFAULT 'PENDING',
    paid_at          TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT affiliate_ledger_status_ck CHECK (status IN ('PENDING', 'PAYABLE', 'PAID', 'REVERSED'))
);
-- Commission accrues exactly once per order regardless of retries.
CREATE UNIQUE INDEX affiliate_ledger_order_uk ON affiliate_ledger (order_id);
CREATE INDEX affiliate_ledger_affiliate_ix ON affiliate_ledger (affiliate_id, created_at DESC);

-- ----------------------------------------------------------------- support ---
CREATE TABLE support_ticket (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_ref   TEXT        NOT NULL UNIQUE,
    account_id   BIGINT REFERENCES account (id),
    order_ref    TEXT,
    email        TEXT        NOT NULL,
    subject      TEXT        NOT NULL,
    body         TEXT        NOT NULL,
    status       TEXT        NOT NULL DEFAULT 'OPEN',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at  TIMESTAMPTZ,
    CONSTRAINT support_status_ck CHECK (status IN ('OPEN', 'ANSWERED', 'CLOSED'))
);
CREATE INDEX support_ticket_status_ix ON support_ticket (status, created_at DESC);
