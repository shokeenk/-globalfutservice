/**
 * What every status is called in the console, and what colour it is.
 *
 * <p>One table, read by one badge component, so a status looks the same on every page
 * that shows it. The labels are the real states' names, not the reference's sample words:
 * "Awaiting sign-in" rather than "Awaiting customer", because waiting for payment is also
 * waiting on the customer and the two need different things from an operator.
 */
export type Tone = 'red' | 'blue' | 'amber' | 'green' | 'grey' | 'violet'

export interface StatusStyle {
  label: string
  tone: Tone
}

export const ORDER_STATUS: Record<string, StatusStyle> = {
  DRAFT: { label: 'Starting', tone: 'grey' },
  AWAITING_PAYMENT: { label: 'Awaiting payment', tone: 'red' },
  ABANDONED: { label: 'Abandoned', tone: 'grey' },
  PAID: { label: 'Paid', tone: 'blue' },
  CREDENTIALS_PENDING: { label: 'Awaiting sign-in', tone: 'violet' },
  READY_FOR_DELIVERY: { label: 'Ready to work', tone: 'amber' },
  IN_PROGRESS: { label: 'In progress', tone: 'blue' },
  ON_HOLD: { label: 'On hold', tone: 'grey' },
  DELIVERED: { label: 'Delivered', tone: 'green' },
  COMPLETED: { label: 'Completed', tone: 'green' },
  DISPUTED: { label: 'Disputed', tone: 'red' },
  REFUNDED: { label: 'Refunded', tone: 'violet' },
  CREDITED: { label: 'Store credit', tone: 'violet' },
}

/**
 * Where an order's payment has got to, from its latest manual payment claim.
 *
 * <p>One set of words for a payment on every page. The references disagree -- the
 * dashboard's says Verified and Pending, the payments page's Success and Failed -- so the
 * dashboard's are used, and "Failed" is "Rejected": it means the money could not be found,
 * not that a payment failed. No claim at all is "Unpaid", the dashboard's "New".
 */
export const PAYMENT_STATE: Record<string, StatusStyle> = {
  NONE: { label: 'Unpaid', tone: 'blue' },
  SUBMITTED: { label: 'Pending', tone: 'amber' },
  VERIFIED: { label: 'Verified', tone: 'green' },
  REJECTED: { label: 'Rejected', tone: 'red' },
}

/**
 * A payment on the Payments page, in the same words: the server's four statuses, which
 * add Refunded -- verified, and the order later refunded -- to the claim's own three.
 */
export const PAY_STATUS: Record<string, StatusStyle> = {
  SUCCESS: PAYMENT_STATE.VERIFIED!,
  PENDING: PAYMENT_STATE.SUBMITTED!,
  FAILED: PAYMENT_STATE.REJECTED!,
  REFUNDED: { label: 'Refunded', tone: 'violet' },
}

/** How a customer paid, named as they would name it. */
export const METHOD_LABEL: Record<string, string> = {
  UPI: 'UPI',
  PAYPAL: 'PayPal',
  CRYPTO: 'USDT (TRON)',
}

/** "SOMETHING_NEW" -> "Something new", for a value the tables above do not know yet. */
function humanise(value: string): string {
  const words = value.toLowerCase().replace(/_/g, ' ')
  return words.charAt(0).toUpperCase() + words.slice(1)
}

export function orderStatus(status: string): StatusStyle {
  return ORDER_STATUS[status] ?? { label: humanise(status), tone: 'grey' }
}

export function paymentState(state: string | null | undefined): StatusStyle {
  return PAYMENT_STATE[state ?? 'NONE'] ?? { label: humanise(state ?? ''), tone: 'grey' }
}

/** Every order status, in lifecycle order, for the Status dropdown. */
export const ORDER_STATUS_ORDER = [
  'AWAITING_PAYMENT', 'PAID', 'CREDENTIALS_PENDING', 'READY_FOR_DELIVERY', 'IN_PROGRESS',
  'ON_HOLD', 'DELIVERED', 'DISPUTED', 'COMPLETED', 'REFUNDED', 'CREDITED', 'ABANDONED', 'DRAFT',
] as const

/**
 * The Orders page's status tabs: named groups of real statuses.
 *
 * <p>"Ready to Work" includes PAID, the moment between payment and the order being routed;
 * "Completed" includes DELIVERED, which is finished work still inside its guarantee
 * window. The badge on each row still says which of the two it is.
 */
export const STATUS_TABS: Array<{ key: string; label: string; statuses: string[] }> = [
  { key: 'all', label: 'All', statuses: [] },
  { key: 'awaiting-payment', label: 'Awaiting Payment', statuses: ['AWAITING_PAYMENT'] },
  { key: 'ready', label: 'Ready to Work', statuses: ['PAID', 'READY_FOR_DELIVERY'] },
  { key: 'in-progress', label: 'In Progress', statuses: ['IN_PROGRESS'] },
  { key: 'awaiting-customer', label: 'Awaiting Customer', statuses: ['CREDENTIALS_PENDING'] },
  { key: 'on-hold', label: 'On Hold', statuses: ['ON_HOLD'] },
  { key: 'completed', label: 'Completed', statuses: ['DELIVERED', 'COMPLETED'] },
  { key: 'disputed', label: 'Disputed', statuses: ['DISPUTED'] },
]

/**
 * The service tabs, and what each covers. Boosting is Champs and Rivals together; the
 * Service dropdown can still pick either one.
 */
export const SERVICE_TABS: Array<{ key: string; label: string; skus: string[] }> = [
  { key: '', label: 'All Orders', skus: [] },
  { key: 'COINS', label: 'Coins', skus: ['TRADING_SERVICE'] },
  { key: 'BOOSTING', label: 'Boosting', skus: ['BOOST_CHAMPS', 'BOOST_RIVALS'] },
  { key: 'COACHING', label: 'Coaching', skus: ['COACHING'] },
]

/** The Service dropdown, one step finer than the tabs. */
export const SERVICE_OPTIONS: Array<{ value: string; label: string }> = [
  { value: '', label: 'All Services' },
  { value: 'COINS', label: 'Coins' },
  { value: 'BOOSTING', label: 'Boosting (all)' },
  { value: 'CHAMPS', label: 'Champs Boosting' },
  { value: 'RIVALS', label: 'Rivals Boosting' },
  { value: 'COACHING', label: 'Coaching' },
]

/** The small tag in the Service column: which of the four products an order is. */
export const SERVICE_TAG: Record<string, StatusStyle> = {
  TRADING_SERVICE: { label: 'Coins', tone: 'amber' },
  BOOST_CHAMPS: { label: 'Champs', tone: 'red' },
  BOOST_RIVALS: { label: 'Rivals', tone: 'violet' },
  COACHING: { label: 'Coaching', tone: 'blue' },
}

export const PLATFORM_LABEL: Record<string, string> = {
  PC: 'PC',
  PLAYSTATION: 'PlayStation',
  XBOX: 'Xbox',
}
