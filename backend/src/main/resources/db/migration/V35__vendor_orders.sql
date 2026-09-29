-- =============================================================================
--  V35 -- one vendor order per order, guarded by the database
--
--  Sending a coin order to FUT Transfer spends real coins, and until now the only
--  guard against sending it twice was a counter on the order. A request that timed
--  out after the vendor had accepted left no vendor id behind, and the next Approve
--  sent the order again.
--
--  vendor_order is the record of that submission. There is at most one row per
--  order (vendor_order_order_uq), and the row is written, as SUBMITTING, and
--  committed before the vendor is called. A second Approve -- a double click,
--  another admin, another instance, a redeploy mid-request -- finds the row and
--  stops. The constraint is the guarantee; the code only reads what it decided.
--
--  A row leaves SUBMITTING for:
--    SUBMITTED     the vendor returned its order id, or a lookup by our reference
--                  confirmed the order exists (then vendor_order_id may be null:
--                  the lookup response does not carry the vendor's id)
--    FAILED        the vendor definitely refused it before creating anything; an
--                  admin may approve it again, which reuses this row
--    NEEDS_REVIEW  we cannot prove what happened; an admin decides
--  The other states are for delivery tracking, which later work fills in.
--
--  Never stored: request or response bodies, the customer's sign-in, and the
--  backup codes the vendor's status response can carry.
-- =============================================================================

CREATE TABLE vendor_order (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id                BIGINT      NOT NULL REFERENCES orders (id),
    -- What we sent as externalOrderID: our public reference.
    external_ref            TEXT        NOT NULL,
    vendor_order_id         TEXT,
    state                   TEXT        NOT NULL,
    -- What we asked for, in the vendor's unit: thousands of coins.
    amount_ordered_k        BIGINT      NOT NULL,
    -- What the vendor reports, in the same unit.
    vendor_amount_ordered_k BIGINT,
    amount_delivered_k      BIGINT,
    coins_used              BIGINT,
    -- The vendor's cost figure. Its currency is unconfirmed.
    to_pay                  NUMERIC(14, 4),
    vendor_status           TEXT,
    vendor_account_check    TEXT,
    vendor_economy_state    TEXT,
    vendor_was_aborted      BOOLEAN,
    attempts                INT         NOT NULL DEFAULT 1,
    -- A short code for the last thing that went wrong, e.g. TIMEOUT or InvalidPassword.
    last_error_code         TEXT,
    review_reason           TEXT,
    submitted_at            TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT vendor_order_order_uq UNIQUE (order_id),
    -- One vendor order can only ever belong to one of ours.
    CONSTRAINT vendor_order_vendor_id_uq UNIQUE (vendor_order_id),
    CONSTRAINT vendor_order_state_ck CHECK (state IN (
        'SUBMITTING', 'SUBMITTED', 'IN_DELIVERY', 'AWAITING_CUSTOMER', 'DELIVERED',
        'PARTIALLY_DELIVERED', 'FAILED', 'NEEDS_REVIEW', 'RESOLVED')),
    CONSTRAINT vendor_order_amount_ck CHECK (amount_ordered_k > 0),
    CONSTRAINT vendor_order_attempts_ck CHECK (attempts >= 1)
);

CREATE INDEX vendor_order_state_ix ON vendor_order (state);

-- ---------------------------------------------------------------------------
--  Orders already with the vendor keep being tracked.
-- ---------------------------------------------------------------------------
INSERT INTO vendor_order (order_id, external_ref, vendor_order_id, state, amount_ordered_k,
                          vendor_amount_ordered_k, amount_delivered_k, vendor_status,
                          vendor_account_check, vendor_economy_state, attempts,
                          submitted_at, created_at, updated_at)
SELECT o.id, o.public_ref, o.supplier_order_id,
       CASE WHEN o.status IN ('DELIVERED', 'COMPLETED') THEN 'DELIVERED' ELSE 'SUBMITTED' END,
       GREATEST(1, round(o.quantity * 1000)::BIGINT),
       o.supplier_amount_ordered, o.supplier_amount_delivered, o.supplier_status,
       o.supplier_account_check, o.supplier_economy_state,
       GREATEST(1, o.supplier_dispatch_attempts),
       o.updated_at, o.updated_at, now()
  FROM orders o
 WHERE o.supplier_order_id IS NOT NULL;

-- ---------------------------------------------------------------------------
--  Coin orders that were tried and never got a vendor id.
--
--  A try that timed out may still have created an order at the vendor, and
--  nothing recorded which kind of failure it was. So these are not left
--  approvable: an admin checks the vendor's dashboard first.
-- ---------------------------------------------------------------------------
INSERT INTO vendor_order (order_id, external_ref, state, amount_ordered_k, attempts,
                          last_error_code, review_reason, created_at, updated_at)
SELECT o.id, o.public_ref, 'NEEDS_REVIEW', GREATEST(1, round(o.quantity * 1000)::BIGINT),
       o.supplier_dispatch_attempts, 'EARLIER_ATTEMPT_UNKNOWN',
       'Sent to the vendor before this record existed, and no vendor order id came back. '
           || 'Check the FUT Transfer dashboard for ' || o.public_ref || ' before approving again.',
       o.updated_at, now()
  FROM orders o
 WHERE o.supplier_order_id IS NULL
   AND o.supplier_dispatch_attempts > 0
   AND o.sku = 'TRADING_SERVICE';
