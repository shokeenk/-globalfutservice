-- =============================================================================
--  Coaching booking system, and the six-tier loyalty ladder.
--
--  Same conventions as the baseline: money in BIGINT minor units, TIMESTAMPTZ
--  everywhere, customer-facing identifiers random rather than the primary key,
--  terminal states and audit rows instead of deletes.
-- =============================================================================

-- ============================== loyalty: daily check-in ======================
-- The ladder itself needs no schema: a tier is derived from the sum of ledger
-- entries whose type counts toward lifetime, so it is always reconstructable and
-- can never disagree with the statement that explains it.
--
-- The daily bonus does need one column. Carrying the claimed day explicitly is
-- what lets the database enforce one claim per account per day; deriving it from
-- created_at would push the check into application code, where two taps on a
-- flaky connection race the read.

ALTER TABLE points_ledger ADD COLUMN claim_date DATE;

ALTER TABLE points_ledger DROP CONSTRAINT points_entry_ck;
ALTER TABLE points_ledger ADD CONSTRAINT points_entry_ck CHECK (entry_type IN
    ('EARNED', 'REDEEMED', 'REFUND_REVERSAL', 'CLAWBACK', 'MANUAL_ADJUSTMENT',
     'DAILY_BONUS'));

-- Only DAILY_BONUS rows carry a date, and every one of them must.
ALTER TABLE points_ledger ADD CONSTRAINT points_claim_date_ck CHECK (
    (entry_type = 'DAILY_BONUS' AND claim_date IS NOT NULL)
    OR (entry_type <> 'DAILY_BONUS' AND claim_date IS NULL));

-- The actual idempotency guard for the daily bonus.
CREATE UNIQUE INDEX points_ledger_daily_uk ON points_ledger (account_id, claim_date)
    WHERE claim_date IS NOT NULL;

-- ============================== coaching: the coach ==========================
CREATE TABLE coach (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id       TEXT        NOT NULL UNIQUE,
    -- An operator account, so a coach signs in through the same identity system
    -- and their session actions are attributable to a real person.
    account_id      BIGINT REFERENCES account (id),
    display_name    TEXT        NOT NULL,
    headline        TEXT,
    bio             TEXT,
    avatar_url      TEXT,
    -- The frame their availability is declared in. Storing the zone next to the
    -- rules is what makes "Tuesdays 18:00" survive a DST change with its meaning
    -- intact -- see AvailabilityRule.
    timezone        TEXT        NOT NULL DEFAULT 'Asia/Kolkata',
    languages       TEXT,
    -- Highest division / rank reached, shown as social proof on the coach card.
    credentials     TEXT,
    active          BOOLEAN     NOT NULL DEFAULT TRUE,
    sort_order      INT         NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX coach_active_ix ON coach (active, sort_order) WHERE active;

-- Recurring weekly windows, in the coach's local time. Never instants: see the
-- class comment on AvailabilityRule for why storing UTC here is a bug that only
-- shows up when the clocks change.
CREATE TABLE coach_availability (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    coach_id     BIGINT      NOT NULL REFERENCES coach (id) ON DELETE CASCADE,
    day_of_week  SMALLINT    NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    start_time   TIME        NOT NULL,
    end_time     TIME        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Windows do not wrap past midnight; a late-night coach declares two rows.
    CONSTRAINT coach_availability_order_ck CHECK (end_time > start_time)
);
CREATE INDEX coach_availability_coach_ix ON coach_availability (coach_id, day_of_week);

-- One-off absences. Absolute instants, because "I am away from the 3rd to the
-- 7th" is a statement about real time, not about a repeating wall clock.
CREATE TABLE coach_time_off (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    coach_id   BIGINT      NOT NULL REFERENCES coach (id) ON DELETE CASCADE,
    starts_at  TIMESTAMPTZ NOT NULL,
    ends_at    TIMESTAMPTZ NOT NULL,
    reason     TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT coach_time_off_order_ck CHECK (ends_at > starts_at)
);
CREATE INDEX coach_time_off_lookup_ix ON coach_time_off (coach_id, starts_at, ends_at);

-- ============================== coaching: credits ============================
-- An append-only ledger, exactly like points_ledger, for exactly the same reason:
-- "why do I have two sessions left?" is answerable from history instead of being
-- argued about. A balance column would have to be right after every cancellation,
-- expiry and operator correction; a sum is right by construction.
CREATE TABLE session_credit (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id  BIGINT      NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    order_id    BIGINT REFERENCES orders (id),
    session_id  BIGINT,          -- FK added after coaching_session exists
    entry_type  TEXT        NOT NULL,
    amount      INT         NOT NULL,
    expires_at  TIMESTAMPTZ,     -- set on GRANTED rows only
    description TEXT,
    actor_id    BIGINT REFERENCES account (id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT session_credit_type_ck CHECK (entry_type IN
        ('GRANTED', 'CONSUMED', 'RETURNED', 'EXPIRED', 'MANUAL_ADJUSTMENT')),
    -- Signs are not a convention to remember; they are enforced.
    CONSTRAINT session_credit_sign_ck CHECK (
        (entry_type IN ('GRANTED', 'RETURNED') AND amount > 0)
        OR (entry_type IN ('CONSUMED', 'EXPIRED') AND amount < 0)
        OR (entry_type = 'MANUAL_ADJUSTMENT' AND amount <> 0))
);
CREATE INDEX session_credit_account_ix ON session_credit (account_id, created_at DESC);

-- One order grants credits exactly once, whatever a retried webhook does.
CREATE UNIQUE INDEX session_credit_order_grant_uk ON session_credit (order_id)
    WHERE entry_type = 'GRANTED' AND order_id IS NOT NULL;

-- A session consumes at most one credit and returns at most one, ever. This is
-- what makes the session state machine's "settles exactly once" claim true at the
-- storage layer rather than only in the code that calls it.
CREATE UNIQUE INDEX session_credit_session_type_uk ON session_credit (session_id, entry_type)
    WHERE session_id IS NOT NULL;

-- Drives the expiry sweep without scanning the table.
CREATE INDEX session_credit_expiry_ix ON session_credit (expires_at)
    WHERE entry_type = 'GRANTED' AND expires_at IS NOT NULL;

-- ============================== coaching: sessions ===========================
CREATE TABLE coaching_session (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_ref        TEXT        NOT NULL UNIQUE,
    account_id        BIGINT      NOT NULL REFERENCES account (id),
    coach_id          BIGINT      NOT NULL REFERENCES coach (id),
    order_id          BIGINT REFERENCES orders (id),
    starts_at         TIMESTAMPTZ NOT NULL,
    ends_at           TIMESTAMPTZ NOT NULL,
    -- The zone the customer booked in. Kept so reminders and the account page can
    -- show the time they chose it in, not the coach's or the server's.
    customer_timezone TEXT,
    status            TEXT        NOT NULL,
    -- Whether the credit came back. Stored, not inferred from status: for a
    -- customer cancellation the answer depends on how much notice they gave, and
    -- re-deriving it later against a policy that has since changed would silently
    -- rewrite what the customer was told at the time.
    credit_returned   BOOLEAN     NOT NULL DEFAULT FALSE,
    reschedule_count  INT         NOT NULL DEFAULT 0 CHECK (reschedule_count >= 0),
    meeting_url       TEXT,
    customer_note     TEXT,
    coach_note        TEXT,
    settled_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    version           BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT coaching_session_status_ck CHECK (status IN
        ('SCHEDULED', 'COMPLETED', 'CANCELLED_BY_CUSTOMER', 'CANCELLED_BY_COACH', 'NO_SHOW')),
    CONSTRAINT coaching_session_order_ck CHECK (ends_at > starts_at)
);
CREATE INDEX coaching_session_account_ix ON coaching_session (account_id, starts_at DESC);
CREATE INDEX coaching_session_coach_ix ON coaching_session (coach_id, starts_at);
-- The reminder and no-show sweeps.
CREATE INDEX coaching_session_upcoming_ix ON coaching_session (starts_at)
    WHERE status = 'SCHEDULED';

-- The double-booking guard.
--
-- A unique index on (coach_id, starts_at) is NOT sufficient and it is worth
-- saying why, because it looks sufficient. Sessions are 40 minutes long on a
-- 30-minute grid, so 18:00 and 18:30 are different start times that overlap by
-- ten minutes. Only a range exclusion catches that. Two customers hitting
-- "confirm" on adjacent slots in the same second is precisely the case the
-- application-level check cannot win, so the database settles it.
CREATE EXTENSION IF NOT EXISTS btree_gist;
ALTER TABLE coaching_session ADD CONSTRAINT coaching_session_no_overlap
    EXCLUDE USING gist (
        coach_id WITH =,
        tstzrange(starts_at, ends_at) WITH &&
    ) WHERE (status = 'SCHEDULED');

ALTER TABLE session_credit ADD CONSTRAINT session_credit_session_fk
    FOREIGN KEY (session_id) REFERENCES coaching_session (id);

-- Same reasoning as order_event: every state change is a row, so the timeline
-- that explains a disputed session is a query rather than a reconstruction.
CREATE TABLE coaching_session_event (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id  BIGINT      NOT NULL REFERENCES coaching_session (id) ON DELETE CASCADE,
    event_type  TEXT        NOT NULL,
    from_status TEXT,
    to_status   TEXT,
    -- Reschedules keep a session SCHEDULED, so the times they moved between live
    -- here; there is no status transition to hang them off.
    from_time   TIMESTAMPTZ,
    to_time     TIMESTAMPTZ,
    actor       TEXT        NOT NULL,
    actor_id    BIGINT REFERENCES account (id),
    detail      TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT coaching_event_actor_ck CHECK (actor IN ('CUSTOMER', 'COACH', 'OPERATOR', 'SYSTEM'))
);
CREATE INDEX coaching_session_event_ix ON coaching_session_event (session_id, created_at);

-- ============================== coaching: rate cards =========================
-- The six-session pack is already seeded in V2 at Rs.4,050 (Rs.675 a session).
-- The single session is priced at Rs.900: a 33% premium on the pack rate, which
-- makes the pack a 25% saving. Priced deliberately rather than proportionally --
-- a trial session costs the coach the same scheduling overhead as a committed
-- one, and the pack should be the better deal or there is no reason to sell it.
INSERT INTO rate_card
    (season, sku, platform, variant, currency, price_unit, unit_price_minor, label, sort_order)
VALUES
    ('FC26', 'COACHING', NULL, 'SINGLE_SESSION', 'INR', 'FLAT', 90000,
     'Single session x 40 min', 0);

-- Coaching orders are fulfilled by a calendar, not by the operator queue.
ALTER TABLE orders DROP CONSTRAINT orders_delivery_ck;
ALTER TABLE orders ADD CONSTRAINT orders_delivery_ck CHECK (
    delivery_method IN ('PLAYER_AUCTION', 'COMFORT_TRADE', 'SCHEDULED_SESSION'));
