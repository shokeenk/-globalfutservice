-- =============================================================================
--  V32 -- a record of every refund
--
--  Payments are manual, so a refund is money somebody sent back by hand: a UPI
--  transfer, a PayPal refund, USDT back to a wallet. Until now marking an order
--  Refunded recorded that it happened and nothing else -- not how much, not how,
--  not the reference a customer would quote. This is that record.
--
--  Full refunds only, for now: the amount is the order's total and there is one
--  refund per order, which the unique index holds even if two admins record the
--  same refund at once. A partial refund needs a rule for loyalty points first.
--
--  The reason is for staff. The customer's order page shows a separate line,
--  written for them, on the order's timeline.
-- =============================================================================

CREATE TABLE refund (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id      BIGINT      NOT NULL REFERENCES orders (id),
    amount_minor  BIGINT      NOT NULL,
    currency      CHAR(3)     NOT NULL,
    -- How the money went back, the same three rails it arrives on.
    method        TEXT        NOT NULL,
    -- The UTR, PayPal refund id or transaction hash of the money sent back.
    reference     TEXT        NOT NULL,
    reason        TEXT        NOT NULL,
    created_by    BIGINT      NOT NULL REFERENCES account (id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT refund_amount_ck CHECK (amount_minor > 0),
    CONSTRAINT refund_method_ck CHECK (method IN ('UPI', 'PAYPAL', 'CRYPTO')),
    CONSTRAINT refund_reference_ck CHECK (char_length(reference) BETWEEN 1 AND 120),
    CONSTRAINT refund_reason_ck CHECK (char_length(reason) BETWEEN 1 AND 500)
);

CREATE UNIQUE INDEX refund_order_uk ON refund (order_id);
CREATE INDEX refund_created_ix ON refund (created_at);
