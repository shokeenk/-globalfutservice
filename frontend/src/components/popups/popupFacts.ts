import { bpsToPercent } from '../../lib/format'
import { formatMinor } from '../../lib/money'
import { useOptionalCatalog } from '../../state/CatalogContext'

/**
 * The live numbers a popup may show, from the catalogue policy -- never from its words or
 * its image: the point value, and the tier ladder with each tier's discount. With tier
 * discounts switched off, the tiers are named and no percentage is shown.
 */
export interface PopupFacts {
  pointValue: string | null
  tiers: Array<{ name: string; percent: string | null }> | null
}

export function usePopupFacts(): PopupFacts {
  const policy = useOptionalCatalog()?.policy
  if (!policy) return { pointValue: null, tiers: null }
  return {
    // "₹1", not "₹1.00": a whole amount reads as one.
    pointValue: formatMinor(policy.pointValueMinor, policy.loyaltyCurrency).replace(/\.00$/, ''),
    tiers: policy.loyaltyTiers.map((t) => ({
      name: t.displayName,
      percent: policy.tierDiscountEnabled && t.discountBps > 0 ? bpsToPercent(t.discountBps) : null,
    })),
  }
}
