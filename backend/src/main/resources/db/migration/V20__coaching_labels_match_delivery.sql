-- =============================================================================
--  Coaching says the same thing in every currency, and says the true thing.
--
--  Found while switching USD, EUR and GBP on. The prices across currencies are
--  deliberately not aligned -- that margin spread is the client's decision and is
--  left alone. The LABELS were a different matter: they described two different
--  products depending on which currency the customer was looking at, and in one
--  case a product that is not what gets delivered.
--
--  What the software actually does, from Coaching.sessionLengthFor:
--
--    one credit   -> session-length        = 60 minutes
--    six credits  -> block-session-length  = 40 minutes
--
--  That is the business rule as stated: the block is cheaper per session because
--  each session is shorter. What was on sale:
--
--    INR  single  "Single session · 1 hour"   correct
--    INR  block   "6 sessions × 1 hour"       WRONG -- delivered as 40 minutes
--    USD/EUR/GBP  single "· 40 min"           WRONG -- delivered as 60 minutes
--    USD/EUR/GBP  block  "× 40 min"           correct
--
--  So every currency had one of the two wrong, and they disagreed with each other.
--
--  ---------------------------------------------------------------------------
--  How each half got that way.
--
--  The non-INR rows came from V7 and were correct when written -- both products
--  were forty minutes then. V10 made the single session an hour and V11/V13 moved
--  the block around, all of them scoped `AND currency = 'INR'`, so the other three
--  currencies kept the old wording and nobody saw it because only INR was enabled.
--
--  The INR block label is the residue of V13. That migration relabelled the block
--  to one hour so the copy matched a limitation in the code -- one global session
--  length. V16 removed the limitation, and its own header says V13 "made the
--  software's limitation into the offer" and that this migration "removes the
--  limitation so the label can be true again". It removed the limitation. Nobody
--  changed the label back, so the site has been selling a forty-minute session as
--  an hour ever since, in the currency it actually trades in.
--
--  ---------------------------------------------------------------------------
--  Prices are carried across unchanged. This migration corrects a description; it
--  is not a repricing, and bundling one into the other is how a price change ships
--  without anyone deciding on it.
--
--  Closed and reinserted rather than UPDATEd, following the rule V4, V6 and V15
--  set: what was on sale, and when, stays on the record.
-- =============================================================================

UPDATE rate_card SET valid_to = now()
WHERE season = 'FC26' AND sku = 'COACHING' AND valid_to IS NULL;

INSERT INTO rate_card
    (season, sku, platform, variant, currency, price_unit, unit_price_minor, label, sort_order)
VALUES
    -- INR: prices from V13, single-session label kept, block label corrected to
    -- the forty minutes it is actually delivered as.
    ('FC26', 'COACHING', NULL, 'SINGLE_SESSION',     'INR', 'FLAT', 100000,
     'Single session · 1 hour',   0),
    ('FC26', 'COACHING', NULL, 'MONTHLY_6_SESSIONS', 'INR', 'FLAT', 405000,
     '6 sessions × 40 minutes',   1),

    -- USD, EUR, GBP: prices from V7, unchanged. Single-session label corrected to
    -- the hour it has been since V10.
    ('FC26', 'COACHING', NULL, 'SINGLE_SESSION',     'USD', 'FLAT',    999,
     'Single session · 1 hour',   0),
    ('FC26', 'COACHING', NULL, 'MONTHLY_6_SESSIONS', 'USD', 'FLAT',   5399,
     '6 sessions × 40 minutes',   1),

    ('FC26', 'COACHING', NULL, 'SINGLE_SESSION',     'EUR', 'FLAT',    799,
     'Single session · 1 hour',   0),
    ('FC26', 'COACHING', NULL, 'MONTHLY_6_SESSIONS', 'EUR', 'FLAT',   4299,
     '6 sessions × 40 minutes',   1),

    ('FC26', 'COACHING', NULL, 'SINGLE_SESSION',     'GBP', 'FLAT',    749,
     'Single session · 1 hour',   0),
    ('FC26', 'COACHING', NULL, 'MONTHLY_6_SESSIONS', 'GBP', 'FLAT',   4049,
     '6 sessions × 40 minutes',   1);
