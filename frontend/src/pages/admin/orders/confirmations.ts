/**
 * The questions the console asks before an action that cannot be taken back.
 *
 * <p>Kept in one place because the same action is offered in two: the Orders table's
 * Next Action button and the order page. Both must ask exactly the same question — an
 * operator who has read it once on the order page should meet the same words in the
 * table, not a shorter version that leaves out what happens to the customer's password.
 */

import { api } from '../../../lib/api'
import type { ReleasePreview } from '../../../lib/types'

/** How Approve would send the order, for its question; null if that cannot be checked. */
export async function releasePreview(publicRef: string): Promise<ReleasePreview | null> {
  try {
    return (await api.get<ReleasePreview>(`/api/v1/admin/orders/${publicRef}/release-preview`)) ?? null
  } catch {
    return null
  }
}

const PLACED_THROUGH: Record<string, string> = {
  PUBLIC_POOL: "the public pool — coins bought from FUT Transfer's sellers",
  OWN_SENDERS: 'own senders — coins sent from our own sender accounts',
}

/**
 * Releasing a coin order sends the customer's EA sign-in to the fulfilment partner.
 *
 * It also says how the order will be placed, and -- for a retry -- when that is not how
 * the last attempt went, so nobody sends to the public pool thinking it is going to our
 * own senders. {@code preview} is null when that could not be checked; the question says so.
 */
export function releaseQuestion(publicRef: string, preview: ReleasePreview | null): string {
  return `Release ${publicRef} to the fulfilment partner?\n\n`
    + `This sends the customer's EA sign-in — email, password and backup codes — to `
    + `FUT Transfer so they can work the order.\n\n`
    + placedThrough(preview)
    + `It cannot be undone. Once sent, the credentials are with a third party.`
}

function placedThrough(preview: ReleasePreview | null): string {
  if (preview == null) return 'How it will be placed could not be checked.\n\n'
  const now = PLACED_THROUGH[preview.orderMode] ?? preview.orderMode
  const last = preview.lastAttemptMode
  const changed = last != null && last !== preview.orderMode
    ? `The last attempt used ${PLACED_THROUGH[last] ?? last}. This one will not.\n`
    : ''
  return `It will be placed through ${now}.\n${changed}\n`
}

/** Starting a boosting order moves it to In progress; the customer sees it as being delivered. */
export function startQuestion(publicRef: string): string {
  return `Start ${publicRef}?\n\nIt moves to In progress, and the customer's order page says so.`
}

/** The sign-in reminder emails the customer. */
export function remindQuestion(publicRef: string, email: string | null): string {
  return `Email ${email ?? 'the customer'} again asking for the EA sign-in on ${publicRef}?\n\n`
    + `It is the same request they were sent when they paid. The reminder is noted on the order.`
}
