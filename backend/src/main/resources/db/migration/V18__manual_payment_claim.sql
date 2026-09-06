-- =============================================================================
--  A customer's claim to have paid outside the gateway.
--
--  Payment here is manual: the customer is shown a QR -- a UPI ID, a PayPal link,
--  a TRON address -- pays from their own app, and then tells us the reference the
--  transfer produced. Nothing about that reaches us automatically. There is no
--  webhook, no callback, no signature to check. All we have is a string the
--  customer typed, and somebody has to go and look at a bank statement.
--
--  So this table stores a CLAIM, not a payment. The distinction is the whole point
--  of the table and it is worth being blunt about it: a row here means "the
--  customer says they sent money", and nothing more. It is unverified by
--  construction, it is attacker-controllable, and it must never by itself move an
--  order forward.
--
--  ---------------------------------------------------------------------------
--  Why not the existing `payment` table.
--
--  `payment` is the gateway's record. Its columns -- provider_order_id,
--  provider_payment_id, PaymentStatus.CAPTURED -- all mean "the provider told us
--  this". Writing a customer's typed string into provider_payment_id would put an
--  unverified value in a column every other reader treats as authoritative, and
--  PaymentStatus has no value meaning "somebody claims this happened". Reusing it
--  would save a table and lose the one distinction that matters.
--
--  ---------------------------------------------------------------------------
--  Why the order status does not change.
--
--  The order stays AWAITING_PAYMENT until a human verifies the claim, because
--  until then it genuinely is awaiting payment. There is no PENDING_VERIFICATION
--  in OrderStatus and this migration deliberately does not add one: which states
--  exist, and what the fulfilment queue is allowed to show, is a decision about
--  how the business runs, not something to slip in underneath a payments feature.
--
--  The cost of that choice is real and should be recorded here rather than
--  discovered later: AWAITING_PAYMENT now covers two different situations -- "has
--  not paid" and "says they paid, nobody has checked" -- and only the presence of
--  a SUBMITTED row here tells them apart. Anything that reasons about
--  AWAITING_PAYMENT (chasers, abandonment sweeps, the operator queue) has to
--  consult this table or it will treat a paying customer as an abandoned one.
--  `V18_1` is where a PENDING_VERIFICATION status would go if that is the call.
-- =============================================================================

CREATE TABLE manual_payment_claim (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id        BIGINT      NOT NULL REFERENCES orders (id) ON DELETE CASCADE,

    -- How they paid. Kept separate from `destination` because the destination can
    -- be changed -- a UPI handle moves bank, a wallet is rotated -- and a claim has
    -- to keep meaning what it meant when it was made.
    method          TEXT        NOT NULL,

    -- The exact address the customer was shown: the UPI id, the PayPal link, the
    -- TRON address. Recorded because reconciliation starts with "which account
    -- should this be in", and because the answer changes over time. Without it, a
    -- claim from before a handle changed becomes unauditable.
    destination     TEXT        NOT NULL,

    -- The UTR / transaction id / txid, exactly as the customer typed it, minus
    -- surrounding whitespace. Deliberately not normalised or validated into a
    -- shape: a UTR is 12 digits, a PayPal transaction id is 17 alphanumerics and a
    -- TRON txid is 64 hex, and a customer who pastes a slightly wrong thing is
    -- better served by an operator seeing it than by a regex refusing it.
    reference       TEXT        NOT NULL,

    -- SUBMITTED -> VERIFIED | REJECTED. No other transitions.
    status          TEXT        NOT NULL DEFAULT 'SUBMITTED',

    submitted_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_at     TIMESTAMPTZ,
    reviewed_by     BIGINT,

    -- Why it was rejected, shown to nobody automatically but the thing a customer
    -- will ask about.
    review_note     TEXT,

    CONSTRAINT manual_payment_claim_status_ck
        CHECK (status IN ('SUBMITTED', 'VERIFIED', 'REJECTED')),

    CONSTRAINT manual_payment_claim_method_ck
        CHECK (method IN ('UPI', 'PAYPAL', 'CRYPTO')),

    -- TEXT everywhere, following the rest of the schema, so the bounds that actually
    -- matter are stated as checks rather than left implicit in a column width.
    CONSTRAINT manual_payment_claim_reference_ck
        CHECK (length(btrim(reference)) BETWEEN 4 AND 120),

    CONSTRAINT manual_payment_claim_destination_ck
        CHECK (length(destination) BETWEEN 1 AND 255),

    CONSTRAINT manual_payment_claim_note_ck
        CHECK (review_note IS NULL OR length(review_note) <= 500),

    -- Reviewed rows carry who and when; unreviewed rows carry neither. Keeps a
    -- half-written review from looking like a completed one.
    CONSTRAINT manual_payment_claim_review_ck
        CHECK ((status = 'SUBMITTED' AND reviewed_at IS NULL AND reviewed_by IS NULL)
            OR (status <> 'SUBMITTED' AND reviewed_at IS NOT NULL))
);

-- One live claim per order. A customer who mistypes a UTR must be able to correct
-- it, so this is a partial index over SUBMITTED only: the correction replaces the
-- pending claim, while a REJECTED claim stays on the record and does not block the
-- next attempt. Without the partial predicate a rejected claim would lock the
-- customer out of ever paying.
CREATE UNIQUE INDEX manual_payment_claim_one_pending_ux
    ON manual_payment_claim (order_id)
    WHERE status = 'SUBMITTED';

-- The operator queue: everything waiting on a human, oldest first, because the
-- customer who has been waiting longest is the one to answer first.
CREATE INDEX manual_payment_claim_pending_ix
    ON manual_payment_claim (submitted_at)
    WHERE status = 'SUBMITTED';

-- Reconciliation runs the other way: an operator holds a bank statement line and
-- needs the order it belongs to. Not unique -- two customers can quote the same
-- reference, one of them wrongly, and that collision is exactly what an operator
-- needs to see rather than something the database should refuse.
CREATE INDEX manual_payment_claim_reference_ix
    ON manual_payment_claim (reference);
