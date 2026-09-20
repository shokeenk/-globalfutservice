-- =============================================================================
--  Which Discord account has claimed which order.
--
--  Most customers sign in with Google, so at the moment a ticket is opened the
--  application does not know their Discord id and cannot grant them anything.
--  They join the server and run /verify with their order reference, and the bot
--  learns the id for the first time at that point. This table is what it writes
--  down.
--
--  ---------------------------------------------------------------------------
--  ONE ORDER, ONE DISCORD ACCOUNT, AND THAT IS THE WHOLE POINT.
--
--  An order reference travels: it is in an email, on a receipt, in a screenshot
--  a customer posts publicly while asking for help. Anybody who has seen one
--  could type it into /verify. The unique constraint on order_id is what stops
--  the second person to try from taking over a ticket the first person is
--  already using -- the grant is refused and the attempt is recorded, rather
--  than the channel quietly acquiring a stranger.
--
--  Re-verifying to a different account is deliberately not possible from
--  Discord. A customer who genuinely changed accounts needs an operator to
--  delete the row, which is a conversation with support rather than a command
--  anybody can run.
--  ---------------------------------------------------------------------------
--
--  Customers who signed in with Discord never appear here. Their id is already
--  on the account from OAuth, the grant happens when the ticket is created, and
--  they are never asked to verify anything.
-- =============================================================================

CREATE TABLE discord_order_verification (
    id                bigserial   PRIMARY KEY,

    order_id          bigint      NOT NULL REFERENCES orders (id) ON DELETE CASCADE,

    -- The numeric snowflake, not a username. Usernames change; this does not.
    discord_user_id   text        NOT NULL,

    -- What they were called at the time, for an operator reading the row later.
    -- Never matched against, because it is not stable.
    discord_username  text,

    -- The channel access was granted on, so a later revoke does not have to
    -- guess which one it was.
    channel_id        text,

    verified_at       timestamptz NOT NULL DEFAULT now(),

    -- One order cannot be claimed twice.
    CONSTRAINT discord_order_verification_order_unique UNIQUE (order_id)
);

-- The lookup /verify performs on every attempt.
CREATE INDEX idx_discord_verification_user
    ON discord_order_verification (discord_user_id);

COMMENT ON TABLE discord_order_verification IS
    'Which Discord account claimed which order. One row per order, enforced.';

-- =============================================================================
--  Failed attempts, kept so that guessing is visible and can be slowed down.
--
--  The rate limiter in front of /verify is in memory and resets when the
--  service restarts, which is fine for smoothing bursts and useless as a record.
--  This table is the record: somebody working through candidate order references
--  leaves a row per try, and an operator can see it.
--
--  Successful attempts are logged here too. A row that says a real order was
--  claimed at a particular minute is the thing you want when a customer says
--  somebody else got into their ticket.
-- =============================================================================

CREATE TABLE discord_verification_attempt (
    id               bigserial   PRIMARY KEY,
    discord_user_id  text        NOT NULL,

    -- As typed. Not a foreign key: the whole point is that most of these do not
    -- correspond to an order.
    order_ref        text,

    outcome          text        NOT NULL,
    attempted_at     timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT discord_verification_attempt_outcome_ck CHECK (outcome IN
        ('GRANTED', 'NOT_FOUND', 'ALREADY_CLAIMED', 'NO_TICKET', 'RATE_LIMITED', 'ERROR'))
);

CREATE INDEX idx_discord_attempt_user_time
    ON discord_verification_attempt (discord_user_id, attempted_at DESC);

COMMENT ON TABLE discord_verification_attempt IS
    'Every /verify attempt, successful or not. The durable half of abuse control.';
