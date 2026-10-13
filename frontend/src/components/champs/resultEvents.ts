import type { Dictionary } from '../../i18n/en'
import type { OrderEvent } from '../../lib/types'

/**
 * A history row about a Champions result, in the customer's language, from its kind and
 * its one value. Null for every other row, which keeps the server's own wording.
 *
 * <p>The delivery row keeps its status and adds the wins: "DELIVERED — 14 Wins Achieved".
 */
export function resultEventText(event: OrderEvent, t: Dictionary, choiceDays: number): string | null {
  const e = t.champs.events
  const detail = event.detail ?? ''
  switch (event.kind) {
    case 'RESULT_DELIVERED':
      return `${event.reason ?? event.toStatus} — ${t.champs.timelineDelivered(detail)}`
    case 'RESULT_UPDATED':
      return e.winsUpdated(detail)
    case 'SETTLEMENT_CHOSEN':
      return detail === 'REFUND' ? e.choseRefund : e.choseCredit
    case 'SETTLEMENT_METHOD':
      return detail === 'REFUND' ? e.methodRefund : e.methodCredit
    case 'SETTLEMENT_DEFAULTED':
      return e.defaultedRefund
    case 'SETTLEMENT_PAID':
      return e.refundPaid(detail)
    case 'SETTLEMENT_ISSUED':
      return e.creditIssued(detail)
    case 'SETTLEMENT_ISSUED_AUTO':
      return e.creditIssuedAuto(detail, choiceDays)
    default:
      return null
  }
}
