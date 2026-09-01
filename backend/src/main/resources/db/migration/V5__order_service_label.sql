-- =============================================================================
--  What an order was sold as, recorded on the order.
--
--  The label was being derived twice from the same inputs and the two answers had
--  diverged. PricingEngine built the customer's invoice line from the rate card's
--  label ("Division 1 to Elite"); OrderService.describe() rebuilt it from the raw
--  variant ("DIV_1_TO_ELITE") for the admin queue, the gateway line item and the
--  operator notification. Same order, two names, and the ugly one was the one the
--  operator and the payment processor saw.
--
--  Deriving it twice was the bug. This column ends that: the pricing engine
--  computes the line once, the order stores what it computed, and every screen
--  reads the stored value. It also makes the name historically correct for free --
--  an order keeps the name it was sold under even after the rate card is renamed,
--  which a live lookup could never guarantee.
-- =============================================================================

ALTER TABLE orders ADD COLUMN service_label TEXT;

-- ---- backfill: what the customer actually saw --------------------------------
--
-- The frozen price_breakdown holds the exact BASE line the customer was shown at
-- checkout, so it is the authoritative record of what an order was sold as. Every
-- existing order gets its own line back, whatever the rate card says today.

UPDATE orders o
SET service_label = (
    SELECT l->>'label'
    FROM jsonb_array_elements(o.price_breakdown->'lines') AS l
    WHERE l->>'code' = 'BASE'
    LIMIT 1)
WHERE service_label IS NULL;

-- ---- and a narrow correction for orders that were never paid -----------------
--
-- An order that never reached PAID has no settled record to protect: nobody was
-- charged, no invoice was issued, and no contractual notice was sent. Those rows
-- are almost always abandoned checkouts and test data, and leaving them showing a
-- name the catalogue no longer uses just leaves rubbish in the operator's queue
-- forever. So where a live rate card still matches the sku and variant, unpaid
-- orders take the current label.
--
-- Deliberately scoped to unpaid orders. Doing this to a paid one would rewrite
-- what a customer was told they bought, which is exactly what the frozen
-- breakdown exists to prevent.

UPDATE orders o
SET service_label = CASE o.sku
        WHEN 'BOOST_CHAMPS' THEN 'Champs Boosting — ' || rc.label
        WHEN 'BOOST_RIVALS' THEN 'Rivals Boosting — ' || rc.label
        WHEN 'COACHING'     THEN 'FUT Classes — '     || rc.label
        WHEN 'CARDS'        THEN 'Player Cards — '    || rc.label
        ELSE o.service_label
    END
FROM rate_card rc
WHERE rc.season = o.season
  AND rc.sku = o.sku
  AND rc.variant IS NOT DISTINCT FROM o.variant
  AND rc.currency = o.currency
  AND rc.valid_to IS NULL
  AND rc.label IS NOT NULL
  AND o.status IN ('DRAFT', 'AWAITING_PAYMENT', 'ABANDONED')
  AND o.sku <> 'TRADING_SERVICE';
