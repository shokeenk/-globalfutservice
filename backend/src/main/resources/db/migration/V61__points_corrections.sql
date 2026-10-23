-- =============================================================================
--  V61 -- Points corrections made by an admin
--
--  CORRECTION: a line an admin adds by hand, with a reason the customer sees. Either
--  the reversal of one earlier line -- reverses_id names it, and it can be reversed only
--  once -- or the difference that sets the balance to a chosen number. Neither kind,
--  nor a line that has been reversed, counts as earned or toward the tier.
-- =============================================================================

ALTER TABLE points_ledger DROP CONSTRAINT points_entry_ck;
ALTER TABLE points_ledger ADD CONSTRAINT points_entry_ck CHECK (entry_type IN
    ('EARNED', 'REDEEMED', 'REFUND_REVERSAL', 'CLAWBACK', 'MANUAL_ADJUSTMENT',
     'DAILY_BONUS', 'SETTLEMENT_DEDUCTION', 'CORRECTION'));

ALTER TABLE points_ledger ADD COLUMN reverses_id BIGINT REFERENCES points_ledger (id);

-- Only a correction reverses, it names who made it and why, and a line is reversed once.
ALTER TABLE points_ledger ADD CONSTRAINT points_reverses_ck CHECK (
    reverses_id IS NULL OR entry_type = 'CORRECTION');
ALTER TABLE points_ledger ADD CONSTRAINT points_correction_ck CHECK (
    entry_type <> 'CORRECTION' OR (actor_id IS NOT NULL AND description IS NOT NULL AND amount <> 0));
CREATE UNIQUE INDEX points_ledger_reverses_uk ON points_ledger (reverses_id) WHERE reverses_id IS NOT NULL;
