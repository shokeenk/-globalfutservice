-- Coaching: a slot picked at checkout, held until the payment is verified; the admin
-- settings that decide which slots exist; and one-off extra slots.
--
-- Extends the booking tables from V3 rather than adding a second booking system. A hold
-- is a coaching_session row like any other, in a status of its own, so the diary, the
-- event log and -- most importantly -- the overlap constraint all cover it.

-- ---------------------------------------------------------------------------------
-- Two new session statuses.
--
--   PENDING   picked at checkout, payment not yet verified. Takes the slot, spends no
--             credit. Becomes SCHEDULED when the order is paid.
--   RELEASED  a hold that ended without becoming a booking: it expired, the payment was
--             rejected, or an admin let it go. Frees the slot. Terminal.
-- ---------------------------------------------------------------------------------
ALTER TABLE coaching_session DROP CONSTRAINT coaching_session_status_ck;
ALTER TABLE coaching_session ADD CONSTRAINT coaching_session_status_ck CHECK (status IN
    ('PENDING', 'SCHEDULED', 'COMPLETED', 'CANCELLED_BY_CUSTOMER', 'CANCELLED_BY_COACH',
     'NO_SHOW', 'RELEASED'));

-- When a hold lets go of its slot if the payment has not been verified by then.
ALTER TABLE coaching_session ADD COLUMN hold_expires_at TIMESTAMPTZ;
ALTER TABLE coaching_session ADD CONSTRAINT coaching_session_hold_expiry_ck
    CHECK (status <> 'PENDING' OR hold_expires_at IS NOT NULL);

CREATE INDEX coaching_session_hold_expiry_ix ON coaching_session (hold_expires_at)
    WHERE status = 'PENDING';

-- One hold per order: a checkout picks one slot, and a retried request must not take two.
CREATE UNIQUE INDEX coaching_session_order_hold_uk ON coaching_session (order_id)
    WHERE status = 'PENDING';

-- The overlap guard now covers holds as well as bookings. This is what makes a slot
-- picked at checkout genuinely taken: two customers paying for the same hour is exactly
-- the race the constraint exists to settle, and a hold that did not count would let it
-- through.
ALTER TABLE coaching_session DROP CONSTRAINT coaching_session_no_overlap;
ALTER TABLE coaching_session ADD CONSTRAINT coaching_session_no_overlap
    EXCLUDE USING gist (
        coach_id WITH =,
        tstzrange(starts_at, ends_at) WITH &&
    ) WHERE (status IN ('PENDING', 'SCHEDULED'));

-- ---------------------------------------------------------------------------------
-- The settings an admin changes from the Coaching diary. One row.
--
-- These used to be environment variables (GFS_COACHING_MIN_LEAD_TIME,
-- GFS_COACHING_SESSION_LENGTH, GFS_COACHING_BLOCK_SESSION_LENGTH). A setting the business
-- changes should not need a redeploy, so they live here now. The two lengths stay per
-- product because each is what a customer bought: a single session is an hour and a
-- session from the six-pack is forty minutes.
-- ---------------------------------------------------------------------------------
CREATE TABLE coaching_settings (
    id                      SMALLINT    PRIMARY KEY CHECK (id = 1),
    min_notice_minutes      INT         NOT NULL CHECK (min_notice_minutes >= 0),
    buffer_minutes          INT         NOT NULL CHECK (buffer_minutes >= 0),
    hold_minutes            INT         NOT NULL CHECK (hold_minutes > 0),
    single_session_minutes  INT         NOT NULL CHECK (single_session_minutes > 0),
    block_session_minutes   INT         NOT NULL CHECK (block_session_minutes > 0),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by              BIGINT REFERENCES account (id)
);

-- 12 hours' notice, as decided; no buffer; two-hour holds; the lengths the products are
-- sold at today.
INSERT INTO coaching_settings
    (id, min_notice_minutes, buffer_minutes, hold_minutes,
     single_session_minutes, block_session_minutes)
VALUES (1, 720, 0, 120, 60, 40);

-- ---------------------------------------------------------------------------------
-- One-off extra availability: a coach working a Tuesday morning they usually do not.
-- Added to the weekly hours for that window only; the slots inside it are generated the
-- same way as any other.
-- ---------------------------------------------------------------------------------
CREATE TABLE coach_extra_slot (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    coach_id    BIGINT      NOT NULL REFERENCES coach (id) ON DELETE CASCADE,
    starts_at   TIMESTAMPTZ NOT NULL,
    ends_at     TIMESTAMPTZ NOT NULL,
    reason      TEXT,
    created_by  BIGINT REFERENCES account (id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT coach_extra_slot_order_ck CHECK (ends_at > starts_at)
);
CREATE INDEX coach_extra_slot_lookup_ix ON coach_extra_slot (coach_id, starts_at, ends_at);
