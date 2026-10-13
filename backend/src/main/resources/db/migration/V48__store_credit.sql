-- =============================================================================
--  V48 -- GFS Store Credit
--
--  Money a customer holds with us, in a currency: issued when a settlement is paid
--  as store credit, spent at checkout on an order in the same currency, and given
--  back if that order is abandoned or refunded.
--
--  Not loyalty points, and deliberately apart from them. Points are worth a rupee
--  each and only on INR orders, and what they add up to moves a customer up the
--  tier ladder; store credit is a sum of money in its own currency, spends on any
--  order in that currency, and has nothing to do with tiers. It cannot be paid out
--  as cash.
--
--  Append-only, like points_ledger: the balance is the sum of a customer's rows in
--  a currency, never a column that is overwritten, so any balance can be explained
--  row by row.
-- =============================================================================

CREATE TABLE store_credit_ledger (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id    BIGINT      NOT NULL REFERENCES account (id),
    currency      CHAR(3)     NOT NULL,
    -- Signed: positive when credit is added, negative when it is spent.
    amount_minor  BIGINT      NOT NULL,
    -- ISSUED: a settlement paid as store credit. SPENT: used on an order.
    -- RETURNED: given back from an order abandoned or refunded.
    entry_type    TEXT        NOT NULL,
    order_id      BIGINT      NOT NULL REFERENCES orders (id),
    description   TEXT        NOT NULL,
    -- Who issued it; null when the system did.
    created_by    BIGINT      REFERENCES account (id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT store_credit_type_ck CHECK (entry_type IN ('ISSUED', 'SPENT', 'RETURNED')),
    CONSTRAINT store_credit_sign_ck CHECK (
        (entry_type = 'SPENT' AND amount_minor < 0) OR (entry_type <> 'SPENT' AND amount_minor > 0))
);

-- Once each per order, whatever a retry does: an order is issued credit once, spends
-- once, and gets back what it spent once.
CREATE UNIQUE INDEX store_credit_order_type_uk ON store_credit_ledger (order_id, entry_type);
CREATE INDEX store_credit_account_ix ON store_credit_ledger (account_id, currency, created_at DESC);

-- What an order paid with store credit, from its signed quote. Zero for every order
-- that used none.
ALTER TABLE orders ADD COLUMN store_credit_minor BIGINT NOT NULL DEFAULT 0;
ALTER TABLE orders ADD CONSTRAINT orders_store_credit_ck CHECK (store_credit_minor >= 0);
