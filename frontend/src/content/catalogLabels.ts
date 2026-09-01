import { useCallback, useMemo } from 'react'
import { useT } from '../i18n'
import { useCatalog } from '../state/CatalogContext'
import type { QuoteLine } from '../lib/types'

/**
 * Localising the strings that arrive from the server.
 *
 * <p>The interface dictionary covers everything the frontend writes. It does not cover
 * what the API sends — the service names, the variant labels and the quote line
 * descriptions are all built in Java, in English. On a French page that left half the
 * checkout in English: "1 · FUT Classes", "Single session · 1 hour", "Payment processing
 * (2.5%)" sitting between "Ta commande" and "Continuer".
 *
 * <p><b>Translated on the client, keyed on the codes the server already sends.</b> Every
 * one of those strings arrives next to a stable identifier — a `sku`, a `variant`, a
 * line `code` — so nothing here has to match on English text. That is the whole reason
 * this is safe: a wording change on the server cannot silently break a translation,
 * because the translations are not keyed on the wording.
 *
 * <p>The alternative was server-side localisation via `Accept-Language`, which means the
 * copy for one screen living in two codebases and three more dictionaries in Java. Not
 * worth it for around twenty strings that change once a season.
 *
 * <p><b>Everything falls back to the server's own text.</b> A SKU or variant added to the
 * price list before it is added here renders in English rather than blank or crashing.
 * English is a worse experience; a missing product name is a broken page.
 *
 * <p>Rank names are deliberately <em>not</em> translated. "Champion II" and "Elite V" are
 * EA's names for those tiers and they appear in English inside the game in every locale —
 * translating them would leave a player unable to match what they bought to what they
 * see on their console.
 */
export function useCatalogLabels() {
  const t = useT()
  const { policy } = useCatalog()

  /** A service's name, e.g. COACHING -> "FUT Classes" / "Cours FUT". */
  const service = useCallback(
    (sku: string | null | undefined, fallback?: string | null): string => {
      if (!sku) return fallback ?? ''
      const table = t.catalog.services as Record<string, string | undefined>
      return table[sku] ?? fallback ?? sku
    },
    [t],
  )

  /** A variant's label, e.g. SINGLE_SESSION -> "Single session · 1 hour". */
  const variant = useCallback(
    (variantCode: string | null | undefined, fallback?: string | null): string => {
      if (!variantCode) return fallback ?? ''
      const table = t.catalog.variants as Record<string, string | undefined>
      return table[variantCode] ?? fallback ?? variantCode
    },
    [t],
  )

  /**
   * What a picker row should say.
   *
   * <p>Trading is chosen by platform and boosting and coaching by variant, so the option
   * carries one or the other. Platform names are brand names and pass through untouched.
   */
  const option = useCallback(
    (o: { variant?: string | null; platform?: string | null; label?: string | null }): string => {
      if (o.variant) return variant(o.variant, o.label)
      return o.label ?? o.platform ?? ''
    },
    [variant],
  )

  /**
   * One line of the price breakdown.
   *
   * <p>Keyed on `code`, with the numbers taken from data the client already holds: the
   * tax and gateway percentages come from the policy, the point count and the coupon
   * code from the quote itself. That is what keeps this a frontend-only change — no new
   * fields on the DTO, and nothing added to the payload the quote signature covers.
   *
   * <p>The discount percentage is dropped from the coupon and referral lines rather than
   * guessed. It is not on the wire, and the amount saved is printed immediately to the
   * right of the label — the percentage was always the redundant half.
   */
  const line = useCallback(
    (
      l: QuoteLine,
      context?: { sku?: string | null; variant?: string | null; platform?: string | null;
                  quantity?: string | null; couponCode?: string | null;
                  referralCode?: string | null; pointsRedeemed?: number },
    ): string => {
      const pct = (bps: number | undefined) =>
        bps === undefined ? '' : `${(bps / 100).toString().replace(/\.0$/, '')}%`

      switch (l.code) {
        case 'BASE': {
          // Rebuilt from the order's own identifiers rather than translated as a
          // sentence, because the server composes it from a service name and a variant
          // that this module can already localise individually.
          if (!context?.sku) return l.label
          const name = service(context.sku, null)
          const detail = context.variant
            ? variant(context.variant, null)
            : [context.quantity ? t.catalog.millions(context.quantity) : null, context.platform]
                .filter(Boolean)
                .join(' · ')
          return detail ? t.catalog.lines.base(name, detail) : name
        }
        case 'MARKET_TAX':
          return t.catalog.lines.marketTax(pct(policy?.marketTaxBps))
        case 'GATEWAY_FEE':
          return t.catalog.lines.gatewayFee(pct(policy?.gatewayFeeBps))
        case 'WALLET_REDEMPTION':
          return t.catalog.lines.walletRedemption(context?.pointsRedeemed ?? 0)
        case 'COUPON_DISCOUNT':
          return t.catalog.lines.coupon(context?.couponCode ?? '')
        case 'REFERRAL_DISCOUNT':
          return t.catalog.lines.referral(context?.referralCode ?? '')
        case 'TIER_DISCOUNT':
          return t.catalog.lines.tierDiscount
        default:
          // An unknown code is a server ahead of this bundle. Its own label is the
          // best available answer and is never wrong, only untranslated.
          return l.label
      }
    },
    [t, policy, service, variant],
  )

  return useMemo(() => ({ service, variant, option, line }), [service, variant, option, line])
}
