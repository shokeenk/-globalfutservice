import { useT } from '../i18n'
import { useCatalog } from '../state/CatalogContext'
import { Alert } from './ui'

/**
 * Whether the storefront's current currency participates in the loyalty programme.
 *
 * <p>The pricing engine awards and redeems points only on orders settled in the
 * loyalty currency — see {@code PricingPolicy.earnsLoyalty}. Anything the interface
 * says about points has to be conditioned on the same answer, read from the same
 * served policy, or the copy and the engine drift apart again.
 */
export function useLoyaltyActive(): boolean {
  const { catalog, policy } = useCatalog()
  if (!policy || !catalog) return false

  /*
   * A server that predates this field is treated as "unknown", not as "no".
   *
   * During a rolling deploy the browser can be running this bundle against an older
   * API that does not send `loyaltyCurrency`. Comparing against `undefined` would be
   * false for every currency, so every customer — including the INR ones the
   * programme is actually for — would be told their points do not apply, and the
   * checkout would hide a points field that works perfectly well.
   *
   * Degrading to the previous behaviour is safe because the gate that matters is on
   * the server: the engine refuses to award or redeem outside the loyalty currency
   * whatever the interface believes. This flag only decides what the page *says*.
   */
  if (!policy.loyaltyCurrency) return true

  return catalog.currency === policy.loyaltyCurrency
}

/**
 * Says plainly that points do not apply in the selected currency.
 *
 * <p>Renders nothing when the currency does earn — this is not a permanent banner,
 * it is the explanation for why the points controls elsewhere on the page have gone
 * quiet. Without it, a customer who switches to USD sees their balance vanish from
 * checkout with no reason given, which reads as a bug or as theft.
 */
export function LoyaltyCurrencyNotice({ className = '' }: { className?: string }) {
  const { catalog, policy } = useCatalog()
  const t = useT()
  const active = useLoyaltyActive()

  if (active || !policy || !catalog) return null

  return (
    <div className={className}>
      <Alert tone="neutral" title={t.loyalty.otherCurrencyTitle(policy.loyaltyCurrency)}>
        {t.loyalty.otherCurrencyBody(catalog.currency, policy.loyaltyCurrency)}
      </Alert>
    </div>
  )
}
