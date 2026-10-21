-- =============================================================================
--  V59: popups -- campaigns, their images, daily counters and the change log (2026-10-21)
--
--  popup_campaign
--      One popup. Created as a DRAFT with enabled = FALSE: nothing shows until an admin
--      publishes it AND switches it on, and it shows only inside its schedule.
--      copy     {"en": {"headline","body","referral","trust"}, "es": {...}, "fr": {...}};
--               English required, the others optional and falling back to English.
--      buttons  [{"label": {"en","es","fr"}, "destination": "/path" | "https://globalfutservices.com/..."}],
--               at most two, destinations checked on save.
--      version  bumped on every change, so a browser that has seen version 3 is shown
--               version 4 again (the storefront remembers "seen" per id + version).
--      deleted_at  a deleted campaign is kept for its counters and change log, and never
--               shown or listed again; its images are removed.
--
--  popup_image
--      One uploaded image, never changed: a new upload is a new row, so a URL carrying
--      the id and its hash can be cached for ever. The bytes are a PostgreSQL large
--      object (data_oid), not BYTEA: a large object can be read in chunks, so serving
--      one never holds the whole image in the API's memory. Render's disk is wiped on
--      every deploy, which rules out files. JPEG, PNG or WebP, at most 1.5 MB.
--
--  popup_stat_daily
--      Counters per campaign per day: views, closes and button clicks. Nothing else --
--      no visitor, no IP, no cookie.
--
--  popup_change
--      Who changed a campaign, when, and what they did.
-- =============================================================================

CREATE TABLE popup_campaign (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id        TEXT        NOT NULL UNIQUE,
    name             TEXT        NOT NULL,
    type             TEXT        NOT NULL,
    desktop_image_id BIGINT,
    mobile_image_id  BIGINT,
    copy             JSONB       NOT NULL,
    buttons          JSONB       NOT NULL DEFAULT '[]'::jsonb,
    target_pages     JSONB       NOT NULL DEFAULT '[]'::jsonb,
    target_paths     JSONB       NOT NULL DEFAULT '[]'::jsonb,
    trigger_type     TEXT        NOT NULL DEFAULT 'PAGE_LOAD',
    trigger_value    INT,
    trigger_event    TEXT,
    starts_at        TIMESTAMPTZ,
    ends_at          TIMESTAMPTZ,
    frequency        TEXT        NOT NULL DEFAULT 'SESSION',
    frequency_days   INT,
    priority         INT         NOT NULL DEFAULT 0,
    status           TEXT        NOT NULL DEFAULT 'DRAFT',
    enabled          BOOLEAN     NOT NULL DEFAULT FALSE,
    version          INT         NOT NULL DEFAULT 1,
    created_by       BIGINT REFERENCES account (id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by       BIGINT REFERENCES account (id),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at       TIMESTAMPTZ,

    CONSTRAINT popup_campaign_type_ck CHECK (type IN ('CENTER_MODAL', 'BOTTOM_RIGHT')),
    CONSTRAINT popup_campaign_trigger_ck CHECK (trigger_type IN ('PAGE_LOAD', 'DELAY', 'SCROLL', 'STEP')),
    CONSTRAINT popup_campaign_frequency_ck CHECK (frequency IN ('SESSION', 'DAYS', 'EVERY_VISIT')),
    CONSTRAINT popup_campaign_status_ck CHECK (status IN ('DRAFT', 'PUBLISHED')),
    CONSTRAINT popup_campaign_schedule_ck CHECK (starts_at IS NULL OR ends_at IS NULL OR ends_at > starts_at),
    -- A deleted campaign is never live.
    CONSTRAINT popup_campaign_deleted_ck CHECK (deleted_at IS NULL OR (NOT enabled AND status = 'DRAFT'))
);
CREATE INDEX popup_campaign_live_ix ON popup_campaign (priority DESC)
    WHERE status = 'PUBLISHED' AND enabled AND deleted_at IS NULL;

CREATE TABLE popup_image (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    campaign_id   BIGINT      NOT NULL REFERENCES popup_campaign (id),
    content_type  TEXT        NOT NULL,
    size_bytes    INT         NOT NULL,
    width         INT         NOT NULL,
    height        INT         NOT NULL,
    sha256        TEXT        NOT NULL,
    data_oid      OID         NOT NULL,
    uploaded_by   BIGINT REFERENCES account (id),
    uploaded_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT popup_image_type_ck CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp')),
    CONSTRAINT popup_image_size_ck CHECK (size_bytes BETWEEN 1 AND 1572864)
);
CREATE INDEX popup_image_campaign_ix ON popup_image (campaign_id);

ALTER TABLE popup_campaign
    ADD CONSTRAINT popup_campaign_desktop_image_fk FOREIGN KEY (desktop_image_id) REFERENCES popup_image (id),
    ADD CONSTRAINT popup_campaign_mobile_image_fk FOREIGN KEY (mobile_image_id) REFERENCES popup_image (id);

CREATE TABLE popup_stat_daily (
    campaign_id  BIGINT NOT NULL REFERENCES popup_campaign (id),
    day          DATE   NOT NULL,
    views        INT    NOT NULL DEFAULT 0,
    closes       INT    NOT NULL DEFAULT 0,
    clicks       INT    NOT NULL DEFAULT 0,
    PRIMARY KEY (campaign_id, day)
);

CREATE TABLE popup_change (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    campaign_id  BIGINT      NOT NULL REFERENCES popup_campaign (id),
    action       TEXT        NOT NULL,
    detail       TEXT,
    admin_id     BIGINT REFERENCES account (id),
    at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX popup_change_campaign_ix ON popup_change (campaign_id, at);
