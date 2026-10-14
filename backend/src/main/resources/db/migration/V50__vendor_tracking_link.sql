-- =============================================================================
--  V50 -- The customer's tracking link from FUT Transfer, and its fuller status
--
--  /generateOrderStatusLink gives each order a page where the customer can watch
--  their coins arrive -- and correct their EA sign-in, which is why the link is a
--  secret: kept here verbatim, shown only to the order's owner, never logged.
--
--  It is asked for once the order's id is committed, apart from placing the order,
--  and a failure never touches the order: the poller asks again with growing gaps
--  until it has the link or the order is finished. A refusal because the order is
--  finished is recorded as UNAVAILABLE, and nothing more is asked.
--
--  The status read also carries simplifiedStatus and two human-readable details,
--  kept for staff. Customers never see the details.
-- =============================================================================

ALTER TABLE vendor_order
    -- The link exactly as the partner returned it, and when it was stored.
    ADD COLUMN tracking_url             TEXT,
    ADD COLUMN tracking_url_at          TIMESTAMPTZ,
    -- Null while it is still to be had; STORED or UNAVAILABLE once settled.
    ADD COLUMN tracking_link_state      TEXT,
    ADD COLUMN tracking_link_attempts   INT         NOT NULL DEFAULT 0,
    ADD COLUMN tracking_link_next_at    TIMESTAMPTZ,
    -- Our own short code for the last failure, e.g. TIMEOUT or HTTP_500. Never the partner's text.
    ADD COLUMN tracking_link_error      TEXT,
    -- When "your coins are on the way" was claimed for sending: at most once per order.
    ADD COLUMN tracking_email_at        TIMESTAMPTZ,
    ADD COLUMN vendor_simplified_status TEXT,
    ADD COLUMN vendor_account_check_long TEXT,
    ADD COLUMN vendor_economy_state_long TEXT,

    ADD CONSTRAINT vendor_order_tracking_state_ck
        CHECK (tracking_link_state IS NULL OR tracking_link_state IN ('STORED', 'UNAVAILABLE')),
    ADD CONSTRAINT vendor_order_tracking_https_ck
        CHECK (tracking_url IS NULL OR tracking_url LIKE 'https://%'),
    ADD CONSTRAINT vendor_order_tracking_stored_ck
        CHECK ((tracking_link_state = 'STORED') = (tracking_url IS NOT NULL));

-- The links still to be had: a small set, asked about on every poll.
CREATE INDEX vendor_order_tracking_due_ix ON vendor_order (tracking_link_next_at)
    WHERE tracking_link_state IS NULL AND vendor_order_id IS NOT NULL;
