-- =============================================================================
--  V49 -- Objectives and Tournaments listings
--
--  Champs and Rivals tiers are rate card rows and nothing else: their names live in
--  the storefront's translation files and their artwork ships with it. Objectives and
--  Tournaments are written by the GFS team -- a title, a subtitle, a PNG, the tasks,
--  the games, the time -- so each one is a row here, with its prices still on the rate
--  card under the variant LISTING_<id>, versioned exactly as every other price is.
--
--  A listing is on sale only while it is active and not deleted: its live rate card
--  rows are its prices, kept while it is a draft so it can be priced before it goes on
--  sale, and the catalogue and the quote both refuse a listing that is not active.
--  Deleting is soft and closes its prices: an order can name a listing, and the order
--  keeps its own snapshot (orders.listing_snapshot) either way.
-- =============================================================================

-- Per-hour pricing: price x whole hours, as a coin row is price x millions.
ALTER TABLE rate_card DROP CONSTRAINT rate_card_unit_ck;
ALTER TABLE rate_card ADD CONSTRAINT rate_card_unit_ck
    CHECK (price_unit IN ('PER_MILLION', 'FLAT', 'PER_HOUR'));

CREATE TABLE service_listing (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sku                   TEXT        NOT NULL,
    title                 TEXT        NOT NULL,
    subtitle              TEXT,
    -- Replaces the checkout's generated "what we will do" text when set.
    overview              TEXT,
    -- One requirement per line, as typed.
    requirements          TEXT,
    required_games        INT,
    estimated_time        TEXT,
    scheduling_note       TEXT,
    reward                TEXT,
    customer_instructions TEXT,
    pricing_type          TEXT        NOT NULL DEFAULT 'FIXED',
    -- Per hour only: the most hours one order can book. Not a business limit, a typo stop.
    max_hours             INT         NOT NULL DEFAULT 50,
    show_image            BOOLEAN     NOT NULL DEFAULT TRUE,
    image_id              BIGINT,
    active                BOOLEAN     NOT NULL DEFAULT FALSE,
    display_order         INT         NOT NULL DEFAULT 0,
    -- Set by a listing created by the application itself, so it is created once.
    seed_key              TEXT        UNIQUE,
    created_by            BIGINT REFERENCES account (id),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by            BIGINT REFERENCES account (id),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_by            BIGINT REFERENCES account (id),
    deleted_at            TIMESTAMPTZ,

    CONSTRAINT service_listing_sku_ck CHECK (sku IN ('BOOST_OBJECTIVES', 'BOOST_TOURNAMENTS')),
    CONSTRAINT service_listing_pricing_ck CHECK (pricing_type IN ('FIXED', 'PER_HOUR')),
    CONSTRAINT service_listing_title_ck CHECK (char_length(btrim(title)) BETWEEN 1 AND 80),
    CONSTRAINT service_listing_games_ck CHECK (required_games IS NULL OR required_games BETWEEN 1 AND 1000),
    CONSTRAINT service_listing_hours_ck CHECK (max_hours BETWEEN 1 AND 1000),
    -- A deleted listing is never on sale.
    CONSTRAINT service_listing_deleted_ck CHECK (deleted_at IS NULL OR NOT active)
);
CREATE INDEX service_listing_public_ix ON service_listing (sku, display_order) WHERE active AND deleted_at IS NULL;

-- Card artwork. One row per upload, never changed: a listing points at its current one,
-- an order's snapshot at the one it was bought with, and the URL of an image id can be
-- cached for ever because its bytes never change.
CREATE TABLE service_listing_image (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    listing_id    BIGINT      NOT NULL REFERENCES service_listing (id),
    content_type  TEXT        NOT NULL,
    size_bytes    INT         NOT NULL,
    width         INT         NOT NULL,
    height        INT         NOT NULL,
    bytes         BYTEA       NOT NULL,
    uploaded_by   BIGINT REFERENCES account (id),
    uploaded_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT service_listing_image_type_ck CHECK (content_type = 'image/png'),
    CONSTRAINT service_listing_image_size_ck CHECK (size_bytes BETWEEN 1 AND 2097152)
);

ALTER TABLE service_listing
    ADD CONSTRAINT service_listing_image_fk FOREIGN KEY (image_id) REFERENCES service_listing_image (id);

-- Who changed a listing, when, and what they did.
CREATE TABLE service_listing_change (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    listing_id  BIGINT      NOT NULL REFERENCES service_listing (id),
    action      TEXT        NOT NULL,
    detail      TEXT,
    admin_id    BIGINT REFERENCES account (id),
    at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX service_listing_change_ix ON service_listing_change (listing_id, at DESC);

-- What an Objectives or Tournaments order was sold as, frozen when it was placed: the
-- title, the terms, the hours and the price. Editing or deleting the listing afterwards
-- changes nothing an order shows.
ALTER TABLE orders ADD COLUMN listing_snapshot JSONB;
