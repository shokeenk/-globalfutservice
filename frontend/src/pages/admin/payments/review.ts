import { api } from '../../../lib/api'

/** What a verify or reject needs to know about a claim, from either list that shows one. */
export interface ReviewableClaim {
  id: number
  publicRef: string
  amountFormatted: string
  destination: string
  reference: string
}

/**
 * Verify or reject a payment claim, asking first exactly as the payments-to-check panel
 * always has.
 *
 * <p>Shared by that panel and the Payments page so the two can never ask different
 * questions about the same money. Verifying releases the order and cannot be undone from
 * the console, so it confirms, naming the amount and the account it should be in: the
 * question being answered is "is this much money there", not "is this the order".
 *
 * @return false if the person backed out, true once the server has it
 */
export async function reviewClaim(claim: ReviewableClaim, outcome: 'verify' | 'reject'): Promise<boolean> {
  if (outcome === 'verify') {
    const confirmed = window.confirm(
      `Confirm ${claim.amountFormatted} arrived at ${claim.destination} `
      + `with reference ${claim.reference}?\n\n`
      + `This marks order ${claim.publicRef} paid and starts fulfilment.`,
    )
    if (!confirmed) return false
  }
  const note = outcome === 'reject'
    ? window.prompt('Why is this being rejected? (optional, kept on the record)') ?? undefined
    : undefined
  await api.post(`/api/v1/admin/payment-claims/${claim.id}/${outcome}`, { note: note ?? null })
  return true
}
