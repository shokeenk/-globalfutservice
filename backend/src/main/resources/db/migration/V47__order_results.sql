-- =============================================================================
--  V47 -- what an order achieved, and what it settles to
--
--  A Champions order is sold as a number of wins. When fewer are achieved, the
--  difference is settled with the customer: a refund, or GFS Store Credit. This is
--  the record of that: the target, the result, the amount owed and how it was paid.
--
--  Keyed by order and by `kind`, never by "wins": CHAMPS_WINS is the only kind
--  today, and another service adds its own kind with its own ladder of levels
--  without a new table.
--
--  The amounts are frozen when the result is saved, from the order's own breakdown
--  and the tier prices that applied when it was priced -- rate_card keeps every
--  price it ever had -- so a later price change never moves a settlement.
-- =============================================================================

CREATE TABLE order_result (
    order_id              BIGINT      PRIMARY KEY REFERENCES orders (id),
    kind                  TEXT        NOT NULL,
    currency              CHAR(3)     NOT NULL,

    -- The ordered target and what was achieved, in the kind's own unit (wins).
    target_level          INT         NOT NULL,
    achieved_level        INT         NOT NULL,
    -- Fewer than the lowest tier: the amount is typed by an admin, with a reason.
    below_lowest          BOOLEAN     NOT NULL DEFAULT FALSE,

    -- What the calculation used: the two tiers' list prices when the order was priced,
    -- and what was paid for the service, the payment fee excluded.
    ordered_price_minor   BIGINT      NOT NULL,
    achieved_price_minor  BIGINT,
    service_paid_minor    BIGINT      NOT NULL,

    -- What is owed. Zero when every win was achieved.
    settlement_minor      BIGINT      NOT NULL,

    method                TEXT,
    method_set_by         TEXT,
    method_set_at         TIMESTAMPTZ,

    -- NONE: nothing owed. PENDING: owed. PAID (refund) and ISSUED (store credit):
    -- settled, and locked. CANCELLED: the order was refunded in full instead.
    status                TEXT        NOT NULL,
    -- Until when the customer can choose; the default applies after it.
    choose_by             TIMESTAMPTZ,

    recorded_by           BIGINT      REFERENCES account (id),
    recorded_at           TIMESTAMPTZ NOT NULL,
    updated_at            TIMESTAMPTZ NOT NULL,
    -- Null when settled automatically.
    settled_by            BIGINT      REFERENCES account (id),
    settled_at            TIMESTAMPTZ,
    refund_id             BIGINT      REFERENCES refund (id),
    -- Loyalty points the settlement took back from the order.
    points_removed        BIGINT      NOT NULL DEFAULT 0,
    version               BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT order_result_kind_ck CHECK (kind IN ('CHAMPS_WINS')),
    CONSTRAINT order_result_levels_ck CHECK (achieved_level >= 0 AND achieved_level <= target_level),
    CONSTRAINT order_result_amounts_ck CHECK (
        ordered_price_minor > 0 AND service_paid_minor >= 0
        AND settlement_minor >= 0 AND settlement_minor <= service_paid_minor
        AND (achieved_price_minor IS NULL OR achieved_price_minor >= 0)),
    CONSTRAINT order_result_below_ck CHECK (below_lowest = (achieved_price_minor IS NULL)),
    CONSTRAINT order_result_method_ck CHECK (method IS NULL OR method IN ('REFUND', 'STORE_CREDIT')),
    CONSTRAINT order_result_set_by_ck CHECK (
        (method IS NULL AND method_set_by IS NULL AND method_set_at IS NULL)
        OR (method IS NOT NULL AND method_set_by IN ('CUSTOMER', 'ADMIN', 'AUTOMATIC')
            AND method_set_at IS NOT NULL)),
    CONSTRAINT order_result_status_ck CHECK (status IN ('NONE', 'PENDING', 'PAID', 'ISSUED', 'CANCELLED')),
    CONSTRAINT order_result_settled_ck CHECK (
        (status = 'PAID' AND method = 'REFUND' AND settled_at IS NOT NULL AND refund_id IS NOT NULL)
        OR (status = 'ISSUED' AND method = 'STORE_CREDIT' AND settled_at IS NOT NULL)
        OR (status IN ('NONE', 'PENDING', 'CANCELLED') AND settled_at IS NULL)),
    CONSTRAINT order_result_owed_ck CHECK ((status = 'NONE') = (settlement_minor = 0)),
    CONSTRAINT order_result_points_ck CHECK (points_removed >= 0)
);

-- What the default job looks for: owed, nobody has chosen, and the time is up.
CREATE INDEX order_result_due_ix ON order_result (choose_by)
    WHERE status = 'PENDING' AND method IS NULL;

-- The screenshot an admin attaches when settling: one per result, replaced on a new
-- upload. In its own table for the same reason as manual_payment_proof (V19): the
-- bytes stay out of every query that only wants the result.
CREATE TABLE settlement_proof (
    order_id      BIGINT      PRIMARY KEY REFERENCES order_result (order_id) ON DELETE CASCADE,
    content_type  TEXT        NOT NULL,
    size_bytes    INT         NOT NULL,
    bytes         BYTEA       NOT NULL,
    uploaded_by   BIGINT      NOT NULL REFERENCES account (id),
    uploaded_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT settlement_proof_type_ck CHECK (content_type IN ('image/jpeg', 'image/png')),
    CONSTRAINT settlement_proof_size_ck CHECK (size_bytes BETWEEN 1 AND 5242880)
);

-- -----------------------------------------------------------------------------
--  Order history.
--
--  `kind` and `detail` name a row the customer's page words in their own language
--  ("SETTLEMENT_PAID", "₹475.00") rather than showing the English reason staff read.
--  `staff_only` rows -- an admin's note, a proof upload -- never reach the customer:
--  not their tracking page, not an email.
-- -----------------------------------------------------------------------------
ALTER TABLE order_event ADD COLUMN kind TEXT;
ALTER TABLE order_event ADD COLUMN detail TEXT;
ALTER TABLE order_event ADD COLUMN staff_only BOOLEAN NOT NULL DEFAULT FALSE;

-- -----------------------------------------------------------------------------
--  Refunds: a settlement refund beside the full one.
--
--  One of each per order. The full refund is what it always was; the settlement
--  refund is the part of a Champions order paid back for wins not achieved, and
--  never moves the order to Refunded. Payop joins the three manual rails: a card
--  payment is refunded in Payop, and its reference is the Payop refund ID.
-- -----------------------------------------------------------------------------
ALTER TABLE refund ADD COLUMN kind TEXT NOT NULL DEFAULT 'FULL';
ALTER TABLE refund ADD CONSTRAINT refund_kind_ck CHECK (kind IN ('FULL', 'SETTLEMENT'));
DROP INDEX refund_order_uk;
CREATE UNIQUE INDEX refund_order_kind_uk ON refund (order_id, kind);
ALTER TABLE refund DROP CONSTRAINT refund_method_ck;
ALTER TABLE refund ADD CONSTRAINT refund_method_ck CHECK (method IN ('UPI', 'PAYPAL', 'CRYPTO', 'PAYOP'));

-- -----------------------------------------------------------------------------
--  Loyalty points a settlement takes back from an order whose points had already
--  landed. Its own type so the one-per-order index still leaves room for a real
--  CLAWBACK later.
-- -----------------------------------------------------------------------------
ALTER TABLE points_ledger DROP CONSTRAINT points_entry_ck;
ALTER TABLE points_ledger ADD CONSTRAINT points_entry_ck CHECK (entry_type IN
    ('EARNED', 'REDEEMED', 'REFUND_REVERSAL', 'CLAWBACK', 'MANUAL_ADJUSTMENT',
     'DAILY_BONUS', 'SETTLEMENT_DEDUCTION'));
