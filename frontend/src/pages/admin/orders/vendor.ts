import { dateTime } from '../../../lib/format'
import type { VendorActionName, VendorOrderDetail, VendorSection } from '../../../lib/types'

/**
 * FUT Transfer on the admin's order page: what each action is called, where it posts, and
 * the question asked before it.
 *
 * <p>The server decides which actions apply ({@code available}) and checks again when one
 * is clicked; this only words them. Staff may see the partner's own codes here -- this is
 * the admin console, never a customer's page.
 */

export const VENDOR_ACTIONS: VendorActionName[] = [
  'SEND_SIGN_IN', 'RESUME', 'UPDATE_SETTINGS', 'LINK', 'RETRY', 'STOP', 'MARK_FINISHED', 'RESOLVE',
]

export const VENDOR_ACTION_LABEL: Record<VendorActionName, string> = {
  SEND_SIGN_IN: 'Send corrected sign-in',
  RESUME: 'Resume at partner',
  STOP: 'Stop at partner',
  MARK_FINISHED: 'Mark finished at partner',
  RETRY: 'Allow sending again',
  LINK: 'Link partner order',
  RESOLVE: 'Resolve',
  UPDATE_SETTINGS: 'Update settings at partner',
}

export const VENDOR_ACTION_PATH: Record<VendorActionName, string> = {
  SEND_SIGN_IN: 'send-sign-in',
  RESUME: 'resume',
  STOP: 'stop',
  MARK_FINISHED: 'mark-finished',
  RETRY: 'retry',
  LINK: 'link',
  RESOLVE: 'resolve',
  UPDATE_SETTINGS: 'update-settings',
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

/** "200 of 500 K": delivered of ordered, in thousands. Nothing reported yet counts as none delivered. */
export function deliveredOfOrdered(deliveredK: number | null | undefined, orderedK: number | null | undefined): string {
  if (orderedK == null) return '—'
  return `${(deliveredK ?? 0).toLocaleString('en-IN')} of ${orderedK.toLocaleString('en-IN')} K`
}

const IN_FLIGHT = new Set(['SUBMITTING', 'SUBMITTED', 'IN_DELIVERY', 'AWAITING_CUSTOMER', 'NEEDS_REVIEW', 'STOPPED'])

/** The partner's latest numbers and when they came: what a review is about, as it is now. */
export function latestReport(v: VendorOrderDetail): string {
  return `Latest report: ${deliveredOfOrdered(v.deliveredK, v.vendorAmountOrderedK ?? v.amountOrderedK)} delivered`
    + `${v.lastPolledAt ? `, ${dateTime(v.lastPolledAt)}` : ''}`
}

/** Who stopped it and when, for a stopped order. */
export function stoppedLine(v: VendorOrderDetail): string {
  const by = v.stoppedBy ? ` by ${v.stoppedBy}` : ''
  const at = v.stoppedAt ? `, ${dateTime(v.stoppedAt)}` : ''
  return `Stopped at FUT Transfer${by}${at}. It stays stopped until someone presses Resume at partner.`
}

/** States that need someone: the red box shows for these, and goes once the order recovers. */
export const ATTENTION_STATES = new Set(['NEEDS_REVIEW', 'PARTIALLY_DELIVERED', 'FAILED', 'STOPPED'])

/**
 * What is holding the order up, in one line, or null when nothing is: the Tracking summary's
 * last row. Taken from the fields the rest of the section shows in full.
 */
export function blockage(section: VendorSection): string | null {
  const v = section.vendorOrder
  if (!v) {
    const auto = automaticNote(section)
    return auto ? `${auto.title}: ${auto.text}` : null
  }
  switch (v.state) {
    case 'NEEDS_REVIEW':
      return `Needs review${v.lastErrorCode ? ` (${v.lastErrorCode})` : ''}: ${v.reviewReason ?? 'no reason recorded'}`
        + `${/[.!?]$/.test(v.reviewReason ?? '') ? '' : '.'} ${latestReport(v)}.`
    case 'STOPPED':
      return `${stoppedLine(v)} ${latestReport(v)}.`
    case 'AWAITING_CUSTOMER':
      return `Waiting for the customer${v.customerAction ? `: ${v.customerAction}` : ''}`
    case 'PARTIALLY_DELIVERED':
      return `Partly delivered${v.reviewReason ? `: ${v.reviewReason}` : ''}`
    case 'FAILED':
      return `Not created at FUT Transfer${v.lastErrorCode ? ` (${v.lastErrorCode})` : ''}`
        + `${v.reviewReason ? `: ${v.reviewReason}` : ''}`
  }
  if (v.aborted) return 'FUT Transfer reports this order was aborted.'
  if (section.paused && IN_FLIGHT.has(v.state)) return 'Calls to FUT Transfer are paused: it refused our API credentials.'
  return null
}

/**
 * How the price ceiling an order was sent with came about, in one sentence for "How it was sent";
 * null when it was worked out from FUT Transfer's price, which the cells beside it show.
 */
export function ceilingNote(v: VendorOrderDetail): string | null {
  if (v.orderMode !== 'PUBLIC_POOL' || v.buyNowThresholdSent == null) return null
  const ceiling = euroAmount(v.buyNowThresholdSent)
  if (v.thresholdSource === 'FIXED_OVERRIDE') return `Fixed override from Render: ${ceiling}.`
  if (v.thresholdSource === 'MARKET') return null
  const sizeOfOrder = v.buyNowThresholdSent === v.amountOrderedK
  return `Sent before automatic pricing: ceiling was ${sizeOfOrder
    ? `the order size (${ceiling} for ${v.amountOrderedK.toLocaleString('en-IN')}K)`
    : `${ceiling}`}. This is a maximum per 100K, not the price charged.`
}

/** A figure in euros, as FUT Transfer prices and charges. */
export function euroAmount(value: number): string {
  return `€${value.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 4 })}`
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
  STOPPED: 'Stopped',
}

export type VendorTone = 'ok' | 'warn' | 'attention' | 'neutral' | 'info'

export function vendorStateTone(state: string): VendorTone {
  switch (state) {
    case 'DELIVERED': return 'ok'
    case 'NEEDS_REVIEW': case 'PARTIALLY_DELIVERED': case 'STOPPED': return 'warn'
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
        + `Ordered ${ordered}; delivered ${delivered} as last reported.\n`
        + 'FUT Transfer starts delivering the rest again, from where it stopped. Only do this once whatever '
        + 'stopped it has been fixed — for example the customer has signed out of their console, or cleared '
        + 'their unassigned items.'
    case 'STOP':
      return `Stop ${ref} at FUT Transfer?\n\n`
        + `Ordered ${ordered}; delivered ${delivered} as last reported.\n`
        + 'FUT Transfer stops sending coins. What has been delivered stays delivered. The order stays stopped here '
        + 'until someone presses Resume at partner; Mark finished closes it instead.'
    case 'MARK_FINISHED':
      return `Mark ${ref} finished at FUT Transfer?\n\n`
        + `Ordered ${ordered}; delivered ${delivered} as last reported.\n`
        + 'FUT Transfer closes the order and delivers nothing more. This cannot be undone.'
    case 'RETRY':
      return `Have you checked the FUT Transfer dashboard and found no order for ${ref}?\n\n`
        + 'We look it up as well. If the partner does not have it, the order can be approved again — '
        + 'which sends it as a new order. If it does exist there, use Link instead.'
    case 'LINK':
      return `Link ${ref} to ${input.vendorOrderId ? `partner order ${input.vendorOrderId}` : 'the partner order under this reference'}?\n\n`
        + `It is checked with the partner first, for ${ordered}, and then watched again.`
    case 'UPDATE_SETTINGS':
      return `Update ${ref}'s settings at FUT Transfer?

`
        + `Sends minTransferAmount ${section.nextSend?.minTransferAmount ?? 0} (0 means no minimum per transfer) to the `
        + `order the partner already has (${v?.vendorOrderId ?? '—'}). Nothing else about the order changes, `
        + 'and sending it again is safe.'
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
