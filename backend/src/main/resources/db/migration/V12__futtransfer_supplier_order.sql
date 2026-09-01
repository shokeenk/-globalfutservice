-- Supplier fulfilment: the FUT Transfer side of a coin order.
--
-- Kept on `orders` rather than in a table of its own. There is exactly one supplier
-- order per GFS order and it shares that row's whole lifecycle, so a join table would
-- add a join to every status read and buy nothing back.
--
-- `supplier_order_id` is nullable on purpose: coaching orders never have one, and a
-- coin order does not have one until its credentials have been dispatched. Absence is
-- the signal that dispatch has not happened yet.

ALTER TABLE orders
    -- Their id for the order. We send our own public_ref as externalOrderID and could
    -- query by that alone, but storing theirs makes a support conversation possible
    -- without a round trip, and it is the only handle that works in their own UI.
    ADD COLUMN supplier_order_id VARCHAR(64),

    -- The last raw triple we saw, kept verbatim rather than only as our mapped status.
    -- Their vocabulary is richer than ours: `accountCheck` and `economy_state` name the
    -- specific thing a customer must fix (unassigned items, a full transfer list, a used
    -- backup code), and collapsing that to ON_HOLD at write time destroys the only
    -- information that makes the hold actionable.
    ADD COLUMN supplier_status VARCHAR(48),
    ADD COLUMN supplier_account_check VARCHAR(48),
    ADD COLUMN supplier_economy_state VARCHAR(64),

    -- Progress, in coins. `partly_delivered` carries these and they are what turns the
    -- tracking page from a spinner into a number the customer can watch move.
    ADD COLUMN supplier_amount_ordered BIGINT,
    ADD COLUMN supplier_amount_delivered BIGINT,

    -- When we last successfully read their status. Distinct from updated_at, which any
    -- write touches: a poller that has been failing for an hour is invisible unless the
    -- successful reads are timestamped separately.
    ADD COLUMN supplier_polled_at TIMESTAMPTZ,

    -- Consecutive dispatch failures. Bounded retry lives on the row rather than in a
    -- queue because there is no queue in this system, and an unbounded retry against a
    -- supplier that is rejecting our credentials is how an account gets locked.
    ADD COLUMN supplier_dispatch_attempts INT NOT NULL DEFAULT 0;

-- The poller reads "orders that have a supplier order and are not finished". Without
-- this it is a sequential scan of every order ever placed, run every minute forever.
CREATE INDEX idx_orders_supplier_open
    ON orders (supplier_polled_at)
    WHERE supplier_order_id IS NOT NULL;

COMMENT ON COLUMN orders.supplier_order_id IS
    'FUT Transfer order id. NULL until credentials are dispatched; always NULL for coaching.';
COMMENT ON COLUMN orders.supplier_economy_state IS
    'Raw economyState from the supplier. Kept verbatim: it names what the customer must fix.';
