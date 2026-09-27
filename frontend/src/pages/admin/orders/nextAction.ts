import type { AdminOrderRow } from '../../../lib/types'
import type { Tone } from '../ui/status'

/**
 * The button in an order's Next Action column: what a person would do with it now.
 *
 * <p>Every kind runs something that already existed, never a new way of changing an order:
 * <ul>
 *   <li>{@code verify}: opens the order's claim in the payments-to-check panel, where
 *       Verify and Reject live. Called "Check Payment" when the order has moved on
 *       without it — abandoned after 48 hours, say — because verifying then records the
 *       money without changing the order, and the person should read it first.</li>
 *   <li>{@code release}: the order page's "release to the fulfilment partner", with the
 *       same confirmation.</li>
 *   <li>{@code start}: the In progress transition, as on the order page.</li>
 *   <li>{@code remind}: re-sends the sign-in request email.</li>
 *   <li>{@code track}, {@code dispute}, {@code view}: open the order page.</li>
 *   <li>{@code sessions}: open the coaching diary, where coaching is worked.</li>
 * </ul>
 *
 * <p>Marking an order delivered is never offered here. It is irreversible — it emails the
 * customer and starts the guarantee — and belongs on the order page, read in full.
 */
export type NextActionKind =
  | 'verify' | 'release' | 'start' | 'remind' | 'track' | 'dispute' | 'sessions' | 'view'

export interface NextAction {
  kind: NextActionKind
  label: string
  tone: Tone
}

const COACHING_LIVE = new Set(['PAID', 'READY_FOR_DELIVERY', 'IN_PROGRESS'])

export function nextAction(row: AdminOrderRow): NextAction {
  const { status, sku } = row

  if (row.paymentState === 'SUBMITTED') {
    return status === 'AWAITING_PAYMENT'
      ? { kind: 'verify', label: 'Verify Payment', tone: 'red' }
      : { kind: 'verify', label: 'Check Payment', tone: 'amber' }
  }
  if (status === 'CREDENTIALS_PENDING' && !row.credentialsHeld) {
    return { kind: 'remind', label: 'Request Sign-in', tone: 'red' }
  }
  if (status === 'READY_FOR_DELIVERY') {
    // The partner takes coin orders that hold a sign-in and have not been sent yet —
    // the same three things the server checks before releasing one.
    if (sku === 'TRADING_SERVICE' && row.credentialsHeld && !row.withPartner) {
      return { kind: 'release', label: 'Start Order', tone: 'red' }
    }
    if (sku.startsWith('BOOST_') && row.availableTransitions.includes('IN_PROGRESS')) {
      return { kind: 'start', label: 'Start Order', tone: 'red' }
    }
  }
  if (sku === 'COACHING' && COACHING_LIVE.has(status)) {
    return { kind: 'sessions', label: 'Open Diary', tone: 'blue' }
  }
  if (status === 'IN_PROGRESS' && row.withPartner) {
    return { kind: 'track', label: 'Track Delivery', tone: 'blue' }
  }
  if (status === 'DISPUTED') {
    return { kind: 'dispute', label: 'Review Dispute', tone: 'red' }
  }
  return { kind: 'view', label: 'View Details', tone: 'grey' }
}
