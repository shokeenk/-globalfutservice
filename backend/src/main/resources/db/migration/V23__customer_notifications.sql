-- The customer's own notification feed: what happened to their orders, and anything the
-- business broadcast to everybody.
--
-- One row per customer per notification, including broadcasts. A shared row plus a
-- separate table of who has read it would save space and cost a join on every read, an
-- outer join to find the unread count, and a second code path for the one case that
-- behaves differently. Fanning out at send time keeps "has this person seen it" a column
-- on the row it belongs to, which is the question this table exists to answer.
--
-- Line comments only. A block comment nests badly in psql and these files get pasted into
-- a console.
CREATE TABLE customer_notification (
    id          BIGSERIAL PRIMARY KEY,
    account_id  BIGINT      NOT NULL REFERENCES account (id) ON DELETE CASCADE,

    -- What kind of thing happened, for the icon and for filtering later. Kept as text with
    -- a CHECK rather than a Postgres enum: adding a kind should be a migration, not an
    -- ALTER TYPE that cannot run inside a transaction on older servers.
    kind        TEXT        NOT NULL,

    title       TEXT        NOT NULL,
    body        TEXT        NULL,

    -- Where clicking it goes, relative to the site root. Null for a notice with nowhere
    -- to send anybody.
    link        TEXT        NULL,

    -- The order this is about, by public reference, so the feed can be read without a
    -- join and an order deleted in the future does not orphan the text.
    order_ref   TEXT        NULL,

    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    read_at     TIMESTAMPTZ NULL,

    CONSTRAINT customer_notification_kind_ck
        CHECK (kind IN ('ORDER_PLACED', 'PAYMENT_SUBMITTED', 'PAYMENT_CONFIRMED',
                        'ACTION_NEEDED', 'STATUS_CHANGED', 'DELIVERED', 'ANNOUNCEMENT')),
    CONSTRAINT customer_notification_title_len_ck CHECK (length(title) <= 120),
    CONSTRAINT customer_notification_body_len_ck  CHECK (body IS NULL OR length(body) <= 500),
    CONSTRAINT customer_notification_link_len_ck  CHECK (link IS NULL OR length(link) <= 200)
);

-- The feed query: this account's rows, newest first.
CREATE INDEX customer_notification_account_idx
    ON customer_notification (account_id, created_at DESC);

-- The badge query: how many has this account not read. Partial, because read rows are the
-- overwhelming majority over time and none of them belong in this index.
CREATE INDEX customer_notification_unread_idx
    ON customer_notification (account_id)
    WHERE read_at IS NULL;
