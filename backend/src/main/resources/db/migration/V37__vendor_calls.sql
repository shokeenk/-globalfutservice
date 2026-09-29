-- =============================================================================
--  V37 -- a record of every call to FUT Transfer
--
--  One row per HTTP attempt, retries included, so any order's dealings with the
--  vendor can be reconstructed: which endpoint, which of the vendor's two
--  domains, what came back, what we made of it, and how long it took.
--
--  Never stored: request or response bodies. They carry the customer's EA
--  sign-in on the way out and can carry their backup codes on the way back.
--  error_code is a bare identifier (TIMEOUT, InvalidPassword, HTTP_403), never
--  the vendor's free text.
-- =============================================================================

CREATE TABLE vendor_call (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    endpoint         TEXT        NOT NULL,
    -- PRIMARY or BACKUP: which of the vendor's domains answered.
    domain           TEXT        NOT NULL,
    -- Null when no answer came back at all (a timeout, a refused connection).
    http_status      INT,
    -- What we made of it: ACCEPTED, REFUSED, UNRECOGNISED or UNCERTAIN for placing an
    -- order; OK, AUTH, RATE_LIMITED, TRANSIENT, NEEDS_REVIEW or UNKNOWN for anything else.
    result           TEXT        NOT NULL,
    error_code       TEXT,
    -- Our references in the call: one for most, up to twenty for a bulk status read.
    order_refs       TEXT[]      NOT NULL DEFAULT '{}',
    vendor_order_id  TEXT,
    duration_ms      INT         NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT vendor_call_domain_ck CHECK (domain IN ('PRIMARY', 'BACKUP')),
    CONSTRAINT vendor_call_result_ck CHECK (result IN (
        'ACCEPTED', 'REFUSED', 'UNRECOGNISED', 'UNCERTAIN',
        'OK', 'AUTH', 'RATE_LIMITED', 'TRANSIENT', 'NEEDS_REVIEW', 'UNKNOWN'))
);

CREATE INDEX vendor_call_refs_ix ON vendor_call USING GIN (order_refs);
CREATE INDEX vendor_call_created_ix ON vendor_call (created_at DESC);
