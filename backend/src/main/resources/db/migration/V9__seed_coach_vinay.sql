-- =============================================================================
--  The first coach.
--
--  Coaching has been sellable since V4 and there has never been a coach row, so
--  every customer who bought a session reached an empty calendar. This is that
--  gap closed.
--
--  Seeded here rather than through the API because there is no admin UI for
--  coaches yet — AdminCoachingController exposes the endpoints, but nothing in
--  the console calls them. A migration also guarantees the row exists on the
--  deploy rather than depending on someone remembering to POST it afterwards,
--  which is the same reason V2 and V4 seed the rate cards.
--
--  WHAT IS DELIBERATELY EMPTY: headline, bio and credentials. Those render on
--  the coach card as claims about a real person's rank and experience, and they
--  are not mine to write. The card degrades to name, languages and timezone
--  until somebody who knows fills them in.
-- =============================================================================

WITH new_coach AS (
    INSERT INTO coach (
        public_id,
        display_name,
        timezone,
        languages,
        active,
        sort_order
    )
    VALUES (
        'vinay',
        'Vinay',
        -- The frame the windows below are declared in. Everything is stored as a
        -- local wall clock in this zone, so "18:00 on a Tuesday" keeps meaning
        -- 18:00 across a DST change anywhere the customer happens to be.
        'Asia/Kolkata',
        'English, Hindi',
        TRUE,
        1
    )
    -- Idempotent, so a Flyway repair or a re-run against a database that already
    -- has him cannot fail the deploy or duplicate the row. If he already exists,
    -- nothing is returned and no windows are added either.
    ON CONFLICT (public_id) DO NOTHING
    RETURNING id
)
INSERT INTO coach_availability (coach_id, day_of_week, start_time, end_time)
SELECT new_coach.id, w.day_of_week, w.start_time, w.end_time
FROM new_coach
CROSS JOIN (VALUES
    -- day_of_week is ISO-8601: Monday is 1, Sunday is 7, matching java.time.DayOfWeek.
    -- Windows are local to the coach's zone and must not wrap past midnight — a
    -- late-night window is declared as two rows, per the CHECK in V3.
    --
    -- Weekday evenings. FUT is played after work and after school, so the useful
    -- hours are the ones a 9-to-5 calendar would call unsociable.
    (1::smallint, '18:00'::time, '23:00'::time),   -- Monday
    (2::smallint, '18:00'::time, '23:00'::time),   -- Tuesday
    (3::smallint, '18:00'::time, '23:00'::time),   -- Wednesday
    (4::smallint, '18:00'::time, '23:00'::time),   -- Thursday
    (5::smallint, '18:00'::time, '23:00'::time),   -- Friday

    -- Weekends open earlier, and split around the middle of the day rather than
    -- running straight through, so a full Saturday is not spent on call.
    (6::smallint, '11:00'::time, '14:00'::time),   -- Saturday, morning
    (6::smallint, '17:00'::time, '23:00'::time),   -- Saturday, evening
    (7::smallint, '11:00'::time, '14:00'::time),   -- Sunday, morning
    (7::smallint, '17:00'::time, '23:00'::time)    -- Sunday, evening
) AS w(day_of_week, start_time, end_time);
