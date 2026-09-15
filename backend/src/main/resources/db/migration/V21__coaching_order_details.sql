-- =============================================================================
--  Coaching details, saved with the order.
--
--  The coaching booking flow asks for a platform, a current rank and what the
--  customer wants to work on. None of it had anywhere to live, so the coach found
--  out on Discord if the customer happened to repeat it. These columns make it part
--  of the order an operator opens and the ticket a coach reads.
--
--  The in-game ID is not here. orders.ea_platform_handle already holds it for every
--  service, and a second column for the same fact is a second place for it to be
--  wrong.
--
--  All nullable. Only coaching orders carry them and every order placed before this
--  migration has none. OrderService requires the platform on a new coaching order;
--  the database does not, so existing rows stay valid.
--
--  Line comments only, never a block comment: Postgres nests block comments, and a
--  stray slash-star in prose is what stopped V19 from parsing at all.
-- =============================================================================

ALTER TABLE orders
    ADD COLUMN coaching_platform TEXT NULL,
    ADD COLUMN coaching_rank     TEXT NULL,
    ADD COLUMN coaching_focus    TEXT NULL;

-- The same three names the platform enum uses everywhere else, stored as text
-- like orders.platform.
ALTER TABLE orders
    ADD CONSTRAINT orders_coaching_platform_ck
        CHECK (coaching_platform IS NULL OR coaching_platform IN ('PLAYSTATION', 'XBOX', 'PC'));

-- Ceilings matching the request validation, so a value the API refuses cannot
-- reach the table by another path either.
ALTER TABLE orders
    ADD CONSTRAINT orders_coaching_rank_len_ck
        CHECK (coaching_rank IS NULL OR length(coaching_rank) <= 40),
    ADD CONSTRAINT orders_coaching_focus_len_ck
        CHECK (coaching_focus IS NULL OR length(coaching_focus) <= 500);
