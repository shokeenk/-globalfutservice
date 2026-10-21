-- =============================================================================
--  V58: email verification, how marketing consent was given, and sign-up settings
--  (2026-10-21)
--
--  email_verification_token
--      One "verify your email" link, sent at sign-up or by Resend. Only the SHA-256 of
--      the token in the link is kept. Usable once (used_at), for 24 hours (expires_at);
--      sending a new one voids the ones still open (revoked_at). Verifying sets the
--      existing account.email_verified_at.
--
--  Existing accounts are not touched: email_verified_at stays as it is. Accounts made
--  with Google or Discord are marked verified by the application when they are made,
--  and the ones already made are marked here, since the provider vouched for the
--  address then as now.
--
--  account.marketing_consent_source
--      How the current marketing choice was made: SIGN_UP, ACCOUNT_SETTINGS or
--      UNSUBSCRIBE_LINK. NULL for accounts whose choice predates this column. The
--      when is the existing marketing_opt_in_at / marketing_opt_out_at.
--
--  marketing_consent_event
--      Every change of that choice, with how and when, so "where did this consent come
--      from" has an answer after it changes again. Written by the application only.
--
--  signup_settings
--      One row. prefill_marketing_consent: whether the sign-up form's marketing box
--      starts ticked. FALSE by default: pre-ticked consent is not valid consent under
--      GDPR, and the client decides.
-- =============================================================================

CREATE TABLE email_verification_token (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id  BIGINT      NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    token_hash  TEXT        NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    revoked_at  TIMESTAMPTZ
);
CREATE INDEX email_verification_token_account_ix ON email_verification_token (account_id);

UPDATE account SET email_verified_at = created_at
 WHERE email_verified_at IS NULL AND password_hash IS NULL AND oauth_subject IS NOT NULL;

ALTER TABLE account
    ADD COLUMN marketing_consent_source TEXT,
    ADD CONSTRAINT account_marketing_consent_source_ck CHECK (marketing_consent_source IS NULL
        OR marketing_consent_source IN ('SIGN_UP', 'ACCOUNT_SETTINGS', 'UNSUBSCRIBE_LINK'));

CREATE TABLE marketing_consent_event (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id  BIGINT      NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    opted_in    BOOLEAN     NOT NULL,
    source      TEXT        NOT NULL,
    campaign_id BIGINT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT marketing_consent_event_source_ck
        CHECK (source IN ('SIGN_UP', 'ACCOUNT_SETTINGS', 'UNSUBSCRIBE_LINK'))
);
CREATE INDEX marketing_consent_event_account_ix ON marketing_consent_event (account_id, created_at);

CREATE TABLE signup_settings (
    id                         SMALLINT    PRIMARY KEY DEFAULT 1,
    prefill_marketing_consent  BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_by                 BIGINT      REFERENCES account (id),
    updated_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT signup_settings_one_row_ck CHECK (id = 1)
);
INSERT INTO signup_settings (id) VALUES (1);
