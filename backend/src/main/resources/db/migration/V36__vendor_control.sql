-- =============================================================================
--  V36 -- one switch that stops every call to FUT Transfer
--
--  A 403 from the vendor means our API credentials were refused: the key may be
--  wrong, revoked or regenerated. Carrying on would send customers' sign-ins at
--  an account that no longer answers to us, so the first 403 pauses every call,
--  and staff are alerted once. An admin resumes after fixing the key.
--
--  A row rather than a flag in memory, so the pause holds across restarts and
--  across instances. There is only ever one row.
-- =============================================================================

CREATE TABLE vendor_control (
    id             SMALLINT    PRIMARY KEY DEFAULT 1,
    paused         BOOLEAN     NOT NULL DEFAULT false,
    paused_at      TIMESTAMPTZ,
    -- A short code, e.g. "HTTP_403 /orderAPI"; never a response body.
    paused_reason  TEXT,
    resumed_at     TIMESTAMPTZ,
    resumed_by     BIGINT      REFERENCES account (id),

    CONSTRAINT vendor_control_single_ck CHECK (id = 1)
);

INSERT INTO vendor_control (id) VALUES (1);
