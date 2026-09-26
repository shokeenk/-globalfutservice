-- ---------------------------------------------------------------------------
-- The fields the campaign builder's redesigned first step asks for.
--
-- Every column is either nullable or defaulted, so the drafts that already exist
-- in production -- left behind while campaign creation was returning 500 --
-- migrate as General campaigns with their promo code shown and tracking on, which
-- is exactly how they would have rendered before this migration.
-- ---------------------------------------------------------------------------

ALTER TABLE email_campaign
    -- What the campaign is about. A content label, NOT an audience: a coins sale
    -- can go to everyone who opted in, not only to past coin buyers. Who receives
    -- the campaign is still `audience`, and every audience is still consent-only.
    ADD COLUMN campaign_type text NOT NULL DEFAULT 'GENERAL',

    -- "15% OFF", "Flat ₹500 OFF". Shown beside the promo code.
    ADD COLUMN offer_text text,

    -- The last day the offer can be used, in the business's calendar (Asia/Kolkata).
    -- A date, not a timestamp: an offer ends on a day, and a timestamp would invite
    -- the question of which midnight.
    ADD COLUMN offer_valid_until date,

    -- Lets a code be kept on the campaign while being left out of the email.
    ADD COLUMN show_promo_code boolean NOT NULL DEFAULT true,

    -- When false: no tracking pixel, and the button links straight to the site
    -- rather than through the click counter.
    ADD COLUMN tracking_enabled boolean NOT NULL DEFAULT true,

    -- The two optional lines around the headline: a short kicker above it
    -- ("TEAM OF THE YEAR") and a line beneath it ("BUILD YOUR DREAM SQUAD").
    ADD COLUMN hero_kicker text,
    ADD COLUMN hero_subline text,

    ADD CONSTRAINT email_campaign_type_ck CHECK
        (campaign_type IN ('COINS', 'BOOSTING', 'COACHING', 'GENERAL'));
