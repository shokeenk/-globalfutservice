-- =============================================================================
--  Promotional campaigns, and a row per person each one was sent to.
--
--  Two tables rather than one, because they answer different questions. The
--  campaign is what the admin composed; the recipient rows are what actually
--  happened to it. "We sent the Friday sale to 412 people, 3 bounced, 1
--  unsubscribed" is not derivable from a counter on the campaign -- it needs the
--  individual rows, and so does answering a customer who asks why they got it.
--
--  ---------------------------------------------------------------------------
--  WHY THE RECIPIENT LIST IS MATERIALISED BEFORE ANY MAIL GOES OUT.
--
--  The obvious implementation streams the audience query straight into the
--  mailer. That has no memory: a send that dies halfway cannot be resumed
--  without either re-sending to everyone or guessing where it stopped, and the
--  audience query itself returns different rows on the second run because people
--  opt in and out in between.
--
--  So the audience is resolved once, written down, and then worked through. A
--  crashed send resumes on the PENDING rows. A retry cannot double-send, because
--  the row is already SENT. And the list of who was included is preserved after
--  the fact, which is the part that matters when somebody asks.
--  ---------------------------------------------------------------------------
--
--  ---------------------------------------------------------------------------
--  WHY STATUS IS CLAIMED WITH A CONDITIONAL UPDATE.
--
--  The scheduler jobs in this codebase assume a single instance and say so. For
--  a purge sweep that assumption is cheap to get wrong. For a campaign it is
--  not: two instances picking up the same due campaign sends every customer the
--  promotion twice.
--
--  Sending therefore claims the campaign with
--      UPDATE ... SET status='SENDING' WHERE id=? AND status IN ('SCHEDULED',...)
--  and proceeds only if one row was updated. That is atomic in Postgres without
--  any additional locking infrastructure, so it is correct on one node today and
--  still correct on three later.
--  ---------------------------------------------------------------------------
--
--  Open and click tracking are per-recipient columns rather than counters, for
--  the same reason as above, and because a counter cannot be de-duplicated: mail
--  clients pre-fetch images, and without a row to mark idempotently every
--  prefetch would increment the total again.
-- =============================================================================

CREATE TABLE email_campaign (
    id                  bigserial   PRIMARY KEY,

    -- What an admin URL and an unsubscribe attribution refer to. Not the row id:
    -- these appear in links and a sequential id invites walking the list.
    public_id           text        NOT NULL UNIQUE,

    -- Internal label ("Friday Coin Sale"). Never shown to a customer.
    title               text        NOT NULL,

    subject             text        NOT NULL,
    heading             text        NOT NULL,
    body                text        NOT NULL,
    promo_code          text,

    -- A label from a fixed list and a path from a fixed list, not free text.
    -- Free-text CTAs are how a campaign ships with a typo'd URL to 400 people.
    cta_text            text,
    cta_path            text,

    -- The banner, stored the way payment proofs are: bytea plus its type, no
    -- @Lob. See ManualPaymentProofEntity for why that annotation is wrong here.
    banner_content_type text,
    banner_bytes        bytea,

    audience            text        NOT NULL,
    status              text        NOT NULL,
    scheduled_at        timestamptz,
    started_at          timestamptz,
    completed_at        timestamptz,

    created_by          bigint      NOT NULL REFERENCES account (id),
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT email_campaign_status_ck CHECK (status IN
        ('DRAFT', 'SCHEDULED', 'SENDING', 'SENT', 'CANCELLED', 'FAILED')),
    CONSTRAINT email_campaign_audience_ck CHECK (audience IN
        ('ALL_OPTED_IN', 'COINS_BUYERS', 'BOOSTING_BUYERS', 'COACHING_BUYERS')),

    -- A scheduled campaign without a time would never run and would sit in the
    -- Scheduled list looking like it was going to.
    CONSTRAINT email_campaign_scheduled_at_ck CHECK
        (status <> 'SCHEDULED' OR scheduled_at IS NOT NULL)
);

CREATE INDEX idx_email_campaign_status ON email_campaign (status);

-- The scheduler's own query: due campaigns, oldest first.
CREATE INDEX idx_email_campaign_due ON email_campaign (scheduled_at)
    WHERE status = 'SCHEDULED';

CREATE TABLE email_campaign_recipient (
    id           bigserial   PRIMARY KEY,
    campaign_id  bigint      NOT NULL REFERENCES email_campaign (id) ON DELETE CASCADE,
    account_id   bigint      NOT NULL REFERENCES account (id),

    -- Snapshotted, not joined at send time. If the customer later changes their
    -- address, the log must still say where this campaign actually went.
    email        text        NOT NULL,

    status       text        NOT NULL DEFAULT 'PENDING',
    sent_at      timestamptz,
    failed_at    timestamptz,
    error        text,

    -- Per-recipient tracking. Nullable throughout: null means "not observed",
    -- which is different from zero and is the honest reading of open tracking.
    opened_at    timestamptz,
    clicked_at   timestamptz,

    -- Carried by the pixel and the click redirect. Random per recipient so one
    -- customer's tracking URL says nothing about anybody else's.
    token        uuid        NOT NULL DEFAULT gen_random_uuid(),

    CONSTRAINT email_campaign_recipient_status_ck CHECK (status IN
        ('PENDING', 'SENT', 'FAILED', 'SKIPPED')),

    -- The idempotency guarantee: one row per person per campaign, enforced by
    -- the database rather than by the sender remembering.
    CONSTRAINT email_campaign_recipient_unique UNIQUE (campaign_id, account_id)
);

CREATE UNIQUE INDEX idx_campaign_recipient_token ON email_campaign_recipient (token);

-- The send loop's query: what is left to do on this campaign.
CREATE INDEX idx_campaign_recipient_pending
    ON email_campaign_recipient (campaign_id)
    WHERE status = 'PENDING';

-- Which campaign an unsubscribe came from, so a campaign that drives people away
-- can be identified as the one that did it. Nullable: somebody can also opt out
-- from their account page, with no campaign involved.
ALTER TABLE account
    ADD COLUMN marketing_opt_out_campaign_id bigint REFERENCES email_campaign (id);

COMMENT ON TABLE email_campaign_recipient IS
    'Send log. One row per person per campaign, written before sending starts.';
