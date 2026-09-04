-- =============================================================================
--  Session credits remember how long the session they buy is.
--
--  There are two coaching products and they are different lengths. A single
--  session is an hour at Rs.1,000. The six-session block is Rs.4,050 -- Rs.675 a
--  session -- and it is cheaper because each session is shorter. The duration
--  difference IS the price difference.
--
--  Until now the application could not express that. `gfs.coaching.session-length`
--  was one global value and `CoachingService` computed every booking as
--  `startsAt.plus(policy.sessionLength())`, so a block customer and a single-session
--  customer picking the same slot got sessions of identical length. Two real
--  consequences, neither of them cosmetic:
--
--    * the block delivered an hour for the price of forty minutes, giving away
--      twenty minutes a session against what was sold;
--    * every block booking held an hour of the coach's calendar for a forty-minute
--      session, over-allocating the day by half an hour per booking.
--
--  V13 "fixed" this by relabelling the block to one hour so the copy matched the
--  code. That was the wrong direction -- V11 had the business right and V13 made
--  the software's limitation into the offer. This migration removes the limitation
--  so the label can be true again.
--
--  ---------------------------------------------------------------------------
--  Why the length lives on the credit rather than on the session or in config.
--
--  The credit is the only thing that knows which product paid for it. A session is
--  created at booking time, long after the purchase, and configuration knows only
--  what the two lengths are, not which one this customer bought. Stamping the
--  credit at grant time also means a change to either length later cannot
--  retroactively shorten a session somebody has already paid for.
-- =============================================================================

ALTER TABLE session_credit ADD COLUMN session_minutes INT;

-- Positive where set, and only meaningful on GRANTED rows -- a CONSUMED entry
-- describes a session that already carries its own start and end.
ALTER TABLE session_credit ADD CONSTRAINT session_credit_minutes_ck
    CHECK (session_minutes IS NULL OR session_minutes > 0);

-- ---------------------------------------------------------------- backfill ---
--
-- Existing grants are dated from the order that created them. `amount` is the
-- number of sessions the order bought, and any grant of more than one session is
-- a block by definition -- which is the same rule `Coaching.sessionLengthFor`
-- applies in Java, so a pack added later needs no change here.
--
-- Deliberately derived from `amount` rather than joined to `orders.variant`: a
-- manual adjustment or a support-issued grant has no order row, and those should
-- still get a sensible length rather than NULL.
UPDATE session_credit
   SET session_minutes = CASE WHEN amount > 1 THEN 40 ELSE 60 END
 WHERE entry_type = 'GRANTED'
   AND session_minutes IS NULL;
