-- =============================================================================
--  V45 -- deleting a coupon
--
--  A coupon no order has used is deleted outright. One that orders have used is
--  kept and marked deleted: those orders, their redemptions and their frozen price
--  breakdowns stay exactly as they were, and the coupon can never be applied again.
--
--  Its code is then free for a new coupon. So the code is unique among coupons that
--  are not deleted, rather than across every row: past orders stay linked to the
--  old coupon by its id, and a new one with the same code is a different coupon.
-- =============================================================================

ALTER TABLE coupon
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD COLUMN deleted_by BIGINT REFERENCES account (id);

-- V8's `code TEXT NOT NULL UNIQUE`, named by Postgres.
ALTER TABLE coupon DROP CONSTRAINT coupon_code_key;
CREATE UNIQUE INDEX coupon_code_live_uk ON coupon (code) WHERE deleted_at IS NULL;

-- Who deleted which coupon, and when: for one deleted outright too, whose row is gone.
CREATE TABLE coupon_deletion (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- No reference: a coupon deleted outright has no row left to point at.
    coupon_id       BIGINT      NOT NULL,
    code            TEXT        NOT NULL,
    discount_bps    INT         NOT NULL,
    redeemed_count  INT         NOT NULL,
    -- REMOVED: never used, deleted outright. HIDDEN: used, kept and marked deleted.
    outcome         TEXT        NOT NULL,
    deleted_by      BIGINT      REFERENCES account (id),
    deleted_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT coupon_deletion_outcome_ck CHECK (outcome IN ('REMOVED', 'HIDDEN'))
);

CREATE INDEX coupon_deletion_at_ix ON coupon_deletion (deleted_at DESC);
