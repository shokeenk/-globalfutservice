-- =============================================================================
--  V31 -- saved views in the admin console
--
--  A named set of filters on the Orders page, kept per person. "Coaching this
--  week", "Disputed PlayStation orders": the handful of views somebody opens
--  every morning, one click away instead of five.
--
--  Per account, not shared. One operator's shortcuts are not another's, and a
--  shared list is one somebody can delete from under everyone else.
--
--  The filters are stored as the page sends them -- a flat object of short
--  strings the server checks against a list of known keys before saving. They
--  are the page's own state, read back by the same page, and never used to
--  build a query directly: the search endpoint parses them like any request.
-- =============================================================================

CREATE TABLE admin_saved_view (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id  BIGINT      NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    page        TEXT        NOT NULL,
    name        TEXT        NOT NULL,
    filters     JSONB       NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT admin_saved_view_page_ck CHECK (page IN ('orders')),
    CONSTRAINT admin_saved_view_name_ck CHECK (char_length(name) BETWEEN 1 AND 60),
    CONSTRAINT admin_saved_view_filters_ck CHECK (jsonb_typeof(filters) = 'object')
);

-- One name per page per person, ignoring case, so "Disputed" cannot sit next to
-- "disputed" and leave them guessing which is which.
CREATE UNIQUE INDEX admin_saved_view_name_uk
    ON admin_saved_view (account_id, page, lower(name));
