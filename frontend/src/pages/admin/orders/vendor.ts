import type { VendorActionName, VendorSection } from '../../../lib/types'

/**
 * FUT Transfer on the admin's order page: what each action is called, where it posts, and
 * the question asked before it.
 *
 * <p>The server decides which actions apply ({@code available}) and checks again when one
 * is clicked; this only words them. Staff may see the partner's own codes here -- this is
 * the admin console, never a customer's page.
 */

export const VENDOR_ACTIONS: VendorActionName[] = [
  'SEND_SIGN_IN', 'RESUME', 'LINK', 'RETRY', 'STOP', 'MARK_FINISHED', 'RESOLVE',
]

export const VENDOR_ACTION_LABEL: Record<VendorActionName, string> = {
  SEND_SIGN_IN: 'Send corrected sign-in',
  RESUME: 'Resume at partner',
  STOP: 'Stop at partner',
  MARK_FINISHED: 'Mark finished at partner',
  RETRY: 'Allow sending again',
  LINK: 'Link partner order',
  RESOLVE: 'Resolve',
}

export const VENDOR_ACTION_PATH: Record<VendorActionName, string> = {
  SEND_SIGN_IN: 'send-sign-in',
  RESUME: 'resume',
  STOP: 'stop',
  MARK_FINISHED: 'mark-finished',
  RETRY: 'retry',
  LINK: 'link',
  RESOLVE: 'resolve',
}

/** History-only entries: sending the order, by Approve or automatically. */
const VENDOR_HISTORY_LABEL: Record<string, string> = {
  APPROVE: 'Approve',
  AUTO_DISPATCH: 'Automatic sending',
}

/** The name a history entry is shown under. */
export function historyLabel(action: string): string {
  return (VENDOR_ACTION_LABEL as Record<string, string>)[action] ?? VENDOR_HISTORY_LABEL[action] ?? action
}

/**
 * Who sent the order to the partner: "Sent automatically", or "Sent by" the admin who clicked
 * Approve. From the latest send that went through; null when none has.
 */
export function whoSent(section: VendorSection): string | null {
  const sent = [...section.actions].reverse().find((e) => e.outcome === 'DONE'
    && (e.action === 'APPROVE' || e.action === 'AUTO_DISPATCH'))
  if (!sent) return null
  return sent.action === 'AUTO_DISPATCH' ? 'Sent automatically' : `Sent by ${sent.actorLabel ?? 'an admin'}`
}

/**
 * Why the automatic queue left this order for Approve, while nothing has been sent since:
 * the reason staff were alerted with. Null when it was sent, or never left.
 */
export function notSentAutomatically(section: VendorSection): string | null {
  return automaticNote(section)?.text ?? null
}

/**
 * The automatic queue's note on this order, while nothing has been sent since: why it left it
 * for Approve -- or, when it is only waiting for the customer's sign-in, that it will be sent
 * by itself once that arrives, which needs nobody on our side.
 */
export function automaticNote(section: VendorSection): { title: string; text: string; waiting: boolean } | null {
  const last = [...section.actions].reverse().find((e) => e.action === 'APPROVE' || e.action === 'AUTO_DISPATCH')
  if (!last || last.action !== 'AUTO_DISPATCH' || last.outcome !== 'REFUSED') return null
  const waiting = last.code === 'NO_SIGN_IN'
  return {
    title: waiting ? 'Waiting for the customer’s sign-in' : 'Not sent automatically',
    text: last.detail ?? last.code ?? '',
    waiting,
  }
}

/** How an order is placed at the partner. */
export const ORDER_MODE_LABEL: Record<string, string> = {
  PUBLIC_POOL: 'Public pool',
  OWN_SENDERS: 'Own senders',
}

/** The mode in words; "Not recorded" for an order from before it was. */
export function orderModeLabel(mode: string | null | undefined): string {
  return mode == null ? 'Not recorded' : ORDER_MODE_LABEL[mode] ?? mode
}

/** A partner figure as it reported it, with no currency assumed. */
export function asReported(value: number | null | undefined): string {
  return value == null ? '—' : value.toLocaleString('en-IN', { maximumFractionDigits: 4 })
}

/** Actions that end something at the partner, shown apart from the rest. */
export const VENDOR_FINAL_ACTIONS = new Set<VendorActionName>(['STOP', 'MARK_FINISHED'])

export const VENDOR_STATE_LABEL: Record<string, string> = {
  SUBMITTING: 'Being sent',
  SUBMITTED: 'With the partner',
  IN_DELIVERY: 'Delivering',
  AWAITING_CUSTOMER: 'Waiting for the customer',
  DELIVERED: 'Delivered',
  PARTIALLY_DELIVERED: 'Partly delivered',
  FAILED: 'Not created',
  NEEDS_REVIEW: 'Needs review',
  RESOLVED: 'Resolved',
}

export type VendorTone = 'ok' | 'warn' | 'attention' | 'neutral' | 'info'

export function vendorStateTone(state: string): VendorTone {
  switch (state) {
    case 'DELIVERED': return 'ok'
    case 'NEEDS_REVIEW': case 'PARTIALLY_DELIVERED': return 'warn'
    case 'AWAITING_CUSTOMER': return 'attention'
    case 'SUBMITTED': case 'IN_DELIVERY': case 'SUBMITTING': return 'info'
    default: return 'neutral'
  }
}

function k(value: number | null | undefined): string {
  return value === null || value === undefined ? 'unknown' : `${value.toLocaleString('en-IN')}K`
}

/**
 * The question asked before an action. Every one says what reaches the partner and what
 * cannot be taken back, and the ones that end something say how much was delivered.
 */
export function vendorQuestion(action: VendorActionName, ref: string, section: VendorSection,
                               input: { vendorOrderId?: string; note?: string } = {}): string {
  const v = section.vendorOrder
  const ordered = k(v?.amountOrderedK)
  const delivered = k(v?.deliveredK)
  switch (action) {
    case 'SEND_SIGN_IN':
      return `Send the customer's new EA sign-in for ${ref} to FUT Transfer?\n\n`
        + `It goes to the order the partner already has (${v?.vendorOrderId ?? '—'}) and restarts it. `
        + 'No new order is created. Once sent, the details are with the partner.'
    case 'RESUME':
      return `Resume ${ref} at FUT Transfer?\n\n`
        + 'Only once whatever stopped it has been fixed — for example the customer has signed out of '
        + 'their console, or cleared their unassigned items.'
    case 'STOP':
      return `Stop ${ref} at FUT Transfer?\n\n`
        + `Ordered ${ordered}; delivered ${delivered} as last reported.\n`
        + 'What has been delivered stays delivered. The partner will not carry on, and this cannot be undone.'
    case 'MARK_FINISHED':
      return `Mark ${ref} finished at FUT Transfer?\n\n`
        + `Ordered ${ordered}; delivered ${delivered} as last reported.\n`
        + 'The partner closes the order and nothing more is delivered. This cannot be undone.'
    case 'RETRY':
      return `Have you checked the FUT Transfer dashboard and found no order for ${ref}?\n\n`
        + 'We look it up as well. If the partner does not have it, the order can be approved again — '
        + 'which sends it as a new order. If it does exist there, use Link instead.'
    case 'LINK':
      return `Link ${ref} to ${input.vendorOrderId ? `partner order ${input.vendorOrderId}` : 'the partner order under this reference'}?\n\n`
        + `It is checked with the partner first, for ${ordered}, and then watched again.`
    case 'RESOLVE':
      return `Close ${ref} at FUT Transfer with this note?\n\n“${input.note ?? ''}”\n\n`
        + 'Nothing is sent to the partner and the order itself does not move — refund or deliver it as usual.'
  }
}

/**
 * Whether Approve would send anything. Once the partner has the order, or is sending it,
 * approving again sends nothing: the section's own actions take over. Null when Approve
 * is the right button.
 */
export function approveReplacedBy(section: VendorSection | null): string | null {
  const state = section?.vendorOrder?.state
  if (!state || state === 'FAILED') return null
  if (state === 'AWAITING_CUSTOMER') {
    return 'The partner already has this order and is waiting for the customer. Use the FUT Transfer '
      + 'section below.'
  }
  return `The partner already has this order (${VENDOR_STATE_LABEL[state] ?? state}). `
    + 'Use the FUT Transfer section below.'
}

export type VendorTimelineItem =
  | { kind: 'call'; at: string; key: string; call: VendorSection['calls'][number] }
  | { kind: 'action'; at: string; key: string; entry: VendorSection['actions'][number] }

/** Calls and admin actions, newest first, as one history. */
export function vendorTimeline(section: VendorSection): VendorTimelineItem[] {
  const items: VendorTimelineItem[] = [
    ...section.calls.map((call, i) => ({ kind: 'call' as const, at: call.at, key: `c${i}`, call })),
    ...section.actions.map((entry, i) => ({ kind: 'action' as const, at: entry.at, key: `a${i}`, entry })),
  ]
  return items.sort((a, b) => (a.at < b.at ? 1 : a.at > b.at ? -1 : a.kind === 'action' ? -1 : 1))
}
