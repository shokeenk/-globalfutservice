/** Wire shapes, mirroring the API's DTOs. */

export type Currency = 'INR' | 'USD' | 'EUR' | 'GBP' | 'AED'

export interface CatalogOption {
  platform: string | null
  variant: string | null
  label: string | null
  unitPriceMinor: number
  unitPriceFormatted: string
  minQuantity: string | null
  maxQuantity: string | null
  stepQuantity: string | null

  /**
   * Measured success rate for this package, in basis points, or null.
   *
   * Null in every deployed environment today, because nothing records what rank an
   * order actually reached — only that it finished. The storefront renders the label
   * when this is a number and renders nothing when it is not, so the day the backend
   * starts measuring, the UI is already there.
   *
   * Never defaulted. `successRateBps ?? 9500` would put an invented advertised claim
   * beside a buy button, which is the one thing this field must not do.
   */
  successRateBps?: number | null
  /**
   * Whether this option carries the Best Value tag: the admin's choice on the Listings page,
   * or without one the last tier. Missing from older servers, which is why the page falls
   * back to the last tier itself.
   */
  bestValue?: boolean
  /**
   * A coin option's price structure: its slider and its rates. PlayStation and Xbox carry
   * the same one -- they share a market and its prices. Missing from older servers, and
   * on every option that is not coins.
   */
  coin?: CatalogCoin | null
}

/** A coin price structure, as the storefront reads it. Amounts in whole thousands of coins. */
export interface CatalogCoin {
  /** The price version these numbers come from; a quote priced from another means they are stale. */
  version?: number | null
  market: 'PC' | 'CONSOLE'
  marketLabel: string
  minK: number
  maxK: number
  stepK: number
  /** The one-tap amounts, in order. */
  quickPicksK: number[]
  /** The base rate (fromK 0), then any brackets: from that amount on, every coin in the order at that rate. */
  rates: CatalogCoinRate[]
}

export interface CatalogCoinRate {
  fromK: number
  perMillionMinor: number
  perMillionFormatted: string
  per100kFormatted: string
}

export interface ServiceGroup {
  sku: string
  displayName: string
  sellable: boolean
  priceUnit: 'PER_MILLION' | 'FLAT'
  marketTaxApplies: boolean
  mayRequireCredentials: boolean
  options: CatalogOption[]
}

export interface Catalog {
  season: string
  currency: string
  availableCurrencies: string[]
  services: ServiceGroup[]
}

/**
 * The policy numbers behind the offer.
 *
 * The site renders its loyalty and guarantee copy from this rather than from
 * hard-coded strings, so the marketing and the pricing engine cannot quietly stop
 * agreeing with each other.
 */
export interface Policy {
  marketTaxBps: number
  gatewayFeeBps: number
  gatewayFeeMode: string
  /** The currency loyalty settles in. Points are only earned and spent in this one. */
  loyaltyCurrency: string
  pointValueMinor: number
  earnSpendUnitMinor: number
  earnPointsPerUnit: number
  maxWalletRedemptionBps: number
  quoteTtlSeconds: number
  guaranteeDays: number
  deliverySlaHours: number
  refundFeeBps: number
  guaranteeCashBps: number
  guaranteeCreditBps: number
  defaultDeliveryMethod: string
  /** Served, never hardcoded — the page cannot promise a rung the engine does not price. */
  loyaltyTiers: LoyaltyTierView[]
  tierDiscountEnabled: boolean
  dailyBonusPoints: number
  /** Coaching session length, served so the storefront never states its own. */
  /** Length of a single purchased session. */
  coachingSessionMinutes: number
  /**
   * Length of one session from a multi-session block.
   *
   * Separate because the two products differ: the block is cheaper per session
   * because each session is shorter, so a page that shows one number for both
   * advertises one of them at a duration it is not sold at.
   */
  coachingBlockSessionMinutes: number
  /** Whether card and online payment through the gateway is available. */
  onlinePaymentsEnabled: boolean
  /** Whether customer emails are on. The storefront only says it emailed when this is true. */
  customerEmailsEnabled: boolean
  /** EA backup codes a coin order's sign-in must include (GFS_BACKUP_CODES_REQUIRED). */
  backupCodesRequired?: number
}

export interface LoyaltyTierView {
  name: string
  displayName: string
  thresholdPoints: number
  discountBps: number
}

export interface QuoteLine {
  code: string
  label: string
  amountMinor: number
  amountFormatted: string
}

export interface SignedQuote {
  quoteId: string
  season: string
  sku: string
  platform: string | null
  variant: string | null
  quantity: string
  currency: string
  lines: QuoteLine[]
  subtotalMinor: number
  totalMinor: number
  totalFormatted: string
  pointsRedeemed: number
  pointsEarned: number
  referralCode: string | null
  /** The coupon that applied, or null. Part of the signed payload. */
  couponCode: string | null
  /** Why a supplied coupon did not apply, or null. Never an error — see QuoteDtos. */
  couponMessage: string | null
  issuedAt: string
  expiresAt: string
  signature: string
  /** The coin price version that priced this quote; null for other services. */
  priceVersion?: number | null
}

export interface Coupon {
  id: number
  code: string
  discountPercent: number
  discountBps: number
  description: string | null
  maxRedemptions: number | null
  redeemedCount: number
  remaining: number | null
  maxPerAccount: number
  minOrderMinor: number
  expiresAt: string | null
  active: boolean
  exhausted: boolean
  createdAt: string
}

export interface PaymentIntent {
  provider: string
  providerOrderId: string
  publicKey: string
  amountMinor: number
  currency: string
  customerEmail: string
  description: string
}

export interface CreateOrderResponse {
  publicRef: string
  status: string
  totalMinor: number
  totalFormatted: string
  currency: string
  payment: PaymentIntent
}

export interface OrderEvent {
  fromStatus: string | null
  toStatus: string
  actorType: string
  actorLabel: string | null
  reason: string | null
  at: string
}

export interface CoachingProgress {
  booked: number
  total: number
  nextStartsAt: string | null
  nextEndsAt: string | null
  nextStatus: 'PENDING' | 'SCHEDULED' | null
  nextTimezone: string | null
}

export interface Order {
  /** FUT Transfer has the order: the transfer has started, and its progress can be followed. */
  transferStarted?: boolean
  publicRef: string
  status: string
  statusLabel: string
  nextAction: string
  serviceLabel: string
  sku: string
  platform: string | null
  variant: string | null
  quantity: string
  deliveryMethod: string
  credentialsRequired: boolean
  credentialsSubmitted: boolean
  currency: string
  totalMinor: number
  totalFormatted: string
  lines: QuoteLine[]
  pointsRedeemed: number
  pointsEarned: number
  referralCode: string | null
  /** Coins delivered so far, from the supplier. Null before fulfilment starts. */
  deliveredCoins: number | null
  /** Coins the supplier was asked for. */
  orderedCoins: number | null
  /** What the customer must do to unstick a held order, or null if nothing. */
  customerAction: CustomerAction | null
  createdAt: string
  deliveredAt: string | null
  guaranteeExpiresAt: string | null
  timeline: OrderEvent[]
  /** Coaching orders: what the customer told the coach at checkout. Null otherwise. */
  eaPlatformHandle: string | null
  coachingPlatform: string | null
  coachingRank: string | null
  coachingFocus: string | null
  /** Boosting on PC: STEAM, EA_APP or EPIC. Null on console and on every other service. */
  pcLauncher: string | null
  /** How this customer reaches their Discord ticket. Decided server-side. */
  discordAccess: DiscordAccess | null
  /**
   * Coaching only: how many of the order's sessions are booked out of how many it bought,
   * and the next one. `nextStatus` is PENDING while the slot picked at checkout is held
   * awaiting payment, SCHEDULED once it is booked. Absent on every other service.
   */
  coaching?: CoachingProgress | null
  /**
   * Where the order stands on payment: UNPAID (payable until `payBy`), SUBMITTED (payment
   * details sent, being checked), EXPIRED, or null once payment is behind it.
   */
  paymentState?: PaymentState | null
  payBy?: string | null
}

export type PaymentState = 'UNPAID' | 'SUBMITTED' | 'EXPIRED'

/**
 * The supplier's stall reasons, as things a person can act on.
 *
 * Mirrors the backend enum by name. Most of these are fixable by the customer in under a
 * minute, which is the whole reason they are surfaced rather than collapsed into "on hold".
 */
export type CustomerAction =
  | 'RESUBMIT_SIGN_IN'
  | 'NEW_BACKUP_CODES'
  | 'SIGN_OUT_CONSOLE'
  | 'CLEAR_UNASSIGNED_ITEMS'
  | 'FREE_TRANSFER_SLOTS'
  | 'ADD_COINS'
  | 'SOLVE_CAPTCHA'
  | 'FIX_PERSONA'
  | 'ACCOUNT_UNUSABLE'
  | 'BANNED'
  | 'SUPPLIER_SIDE'

export interface OrderSummary {
  /** FUT Transfer has the order: the list offers "Track your order". */
  transferStarted?: boolean
  publicRef: string
  status: string
  /** TRADING_SERVICE, BOOST_CHAMPS, BOOST_RIVALS or COACHING. */
  sku: string
  /** The status in the customer's words: Queued, Being delivered, Completed. */
  statusLabel: string
  serviceLabel: string
  platform: string | null
  quantity: string
  deliveryMethod: string
  credentialsHeld: boolean
  customerEmail: string | null
  totalMinor: number
  totalFormatted: string
  currency: string
  createdAt: string
  deliveredAt: string | null
  availableTransitions: string[]
  /** As on the order: "Complete your payment" is offered on the UNPAID ones. */
  paymentState?: PaymentState | null
}

export interface Account {
  publicId: string
  email: string
  displayName: string | null
  role: 'CUSTOMER' | 'OPERATOR' | 'ADMIN'
  pointsBalance: number
  pointsValueMinor: number
  pointsValueFormatted: string
  firstOrder: boolean
  referredByCode: string | null
}

export interface AdminStats {
  awaitingPayment: number
  paid: number
  credentialsPending: number
  readyForDelivery: number
  inProgress: number
  onHold: number
  deliveredAwaitingGuarantee: number
  disputed: number
  credentialsHeld: number
}

/**
 * GET /api/v1/admin/orders/search: one row of the Orders table.
 *
 * <p>`quantity` is a Java BigDecimal and arrives as a JSON number, not a string.
 */
export interface AdminOrderRow {
  publicRef: string
  status: string
  sku: string
  serviceLabel: string
  variant: string | null
  quantity: number | string
  /** The order's platform, or for coaching the player's. */
  platform: string | null
  deliveryMethod: string
  credentialsHeld: boolean
  /** The fulfilment partner has it. */
  withPartner: boolean
  customerName: string | null
  customerEmail: string | null
  /** The latest payment claim's status, or null when none was ever made. */
  paymentState: 'SUBMITTED' | 'VERIFIED' | 'REJECTED' | null
  paymentMethod: ManualPaymentMethod | null
  paymentReference: string | null
  eaHandle: string | null
  totalMinor: number
  totalFormatted: string
  currency: string
  createdAt: string
  deliveredAt: string | null
  availableTransitions: string[]
  /** Null until FUT Transfer has the order: the Tracking column shows a dash. */
  tracking?: AdminOrderTracking | null
}

/** A coin order FUT Transfer has: the customer's own tracking page, and how far it has got. */
export interface AdminOrderTracking {
  /** The address the customer's emails link to. */
  url: string
  orderedK: number | null
  /** Null until FUT Transfer first reports. */
  deliveredK: number | null
}

export interface AdminOrderPage {
  items: AdminOrderRow[]
  total: number
  page: number
  size: number
}

/** GET /api/v1/admin/orders/overview: counts only, never money. */
export interface AdminOrderOverview {
  counts: { sku: string; status: string; count: number }[]
  paymentsToCheck: number
  signInsToWork: number
  disputed: number
  awaitingSignIn: number
  deliveredToday: number
  deliveredYesterdaySoFar: number
  credentialsHeld: number
}

/** GET /api/v1/admin/dashboard: today against yesterday at the same time. No money. */
export interface AdminDashboard {
  newToday: number
  newYesterdaySoFar: number
  /** Paid and not delivered yet. */
  pending: number
  /** Pending at this time yesterday, rebuilt from order history. */
  pendingYesterday: number
  deliveredToday: number
  deliveredYesterdaySoFar: number
  newest: AdminOrderRow[]
  /** The orders whose status changed most recently, and when. */
  recent: { order: AdminOrderRow; changedAt: string }[]
}

/** GET /api/v1/admin/dashboard/revenue: admin only. One entry per currency, rupees first. */
export interface CurrencyRevenue {
  currency: string
  todayMinor: number
  todayFormatted: string
  yesterdayMinor: number
  yesterdayFormatted: string
}

/**
 * GET /api/v1/admin/customers: one customer, an account or a guest.
 *
 * <p>The server leaves out fields that are null, so every optional one here may simply be
 * missing. `spent` is only ever present for an admin.
 */
export interface AdminCustomer {
  /** a-<account id> or g-<first order reference>. */
  key: string
  kind: 'ACCOUNT' | 'GUEST'
  /** Never empty: a guest who gave no name is "Guest". */
  name: string
  email: string
  eaHandle?: string | null
  platform?: string | null
  orders: number
  /** Paid and not refunded, one entry per currency, largest first. Admins only. */
  spent?: { currency: string; minor: number; formatted: string }[] | null
  lastOrderAt?: string | null
  joinedAt: string
  status: 'ACTIVE' | 'DISABLED' | 'LOCKED' | 'GUEST'
  discordConnected: boolean
}

export interface AdminCustomerPage {
  items: AdminCustomer[]
  total: number
  page: number
  size: number
}

export interface AdminCustomerOverview {
  total: number
  newThisMonth: number
  newLastMonthSoFar: number
  withOrders: number
  withOrdersLastMonth: number
}

export interface AdminCustomerDetail {
  customer: AdminCustomer
  recentOrders: { publicRef: string; sku: string; serviceLabel: string; status: string; createdAt: string }[]
}

/**
 * GET /api/v1/admin/payments: one payment a customer reported, and what became of it.
 * Null fields are left out by the server, so optional ones may be missing.
 */
export interface AdminPayment {
  claimId: number
  publicRef: string
  customerName?: string | null
  email: string
  method: ManualPaymentMethod
  reference: string
  /** Which account the customer was told to pay. */
  destination: string
  status: 'SUCCESS' | 'PENDING' | 'FAILED' | 'REFUNDED'
  orderStatus: string
  amountMinor: number
  amountFormatted: string
  currency: string
  submittedAt: string
  reviewedAt?: string | null
  reviewedBy?: string | null
  reviewNote?: string | null
  hasProof: boolean
  /** The record of money sent back. A Refunded payment without one predates the records. */
  refund?: {
    amountMinor: number; amountFormatted: string; method: ManualPaymentMethod; reference: string
    reason: string; at: string; by?: string | null
  } | null
}

export interface AdminPaymentPage {
  items: AdminPayment[]
  total: number
  page: number
  size: number
}

/** One status in one currency. `minor` and `formatted` are sent to admins only. */
export interface PaymentTotal {
  status: AdminPayment['status']
  currency: string
  count: number
  minor?: number | null
  formatted?: string | null
}

export interface AdminPaymentOverview {
  thisMonth: PaymentTotal[]
  lastMonthSoFar: PaymentTotal[]
  allTime: Record<AdminPayment['status'], number>
}

/** GET /api/v1/admin/listings: one boosting tier or coaching package. */
export interface AdminListing {
  sku: string
  variant: string
  /** The server's own label; the page shows the translated title where there is one. */
  label: string
  sortOrder: number
  active: boolean
  /** Minor units per currency code: live prices, or the last ones for a hidden listing. */
  prices: Record<string, { minor: number; formatted: string }>
  successRateBps?: number | null
  /** LISTING when set on this page, CONFIGURATION when it still comes from the server's settings. */
  successRateSource?: 'LISTING' | 'CONFIGURATION' | null
  bestValue: boolean
}

export interface AdminListingCategory {
  sku: 'BOOST_CHAMPS' | 'BOOST_RIVALS' | 'COACHING'
  name: string
  listings: AdminListing[]
  /** Boosting only: DEFAULT (the last tier), CHOSEN, or NONE. */
  bestValueChoice?: 'DEFAULT' | 'CHOSEN' | 'NONE' | null
}

export interface AdminListingsOverview {
  /** The currencies the site sells in, and so the prices a listing can have. */
  currencies: string[]
  categories: AdminListingCategory[]
}

/** A ticket's topic, chosen on the contact form or by staff. Tickets from before have none. */
export type SupportCategory = 'COINS' | 'BOOSTING' | 'COACHING' | 'PAYMENT' | 'ACCOUNT' | 'TECHNICAL' | 'OTHER'

/** OPEN is with staff, ANSWERED is waiting for the customer, CLOSED is resolved. */
export type SupportStatus = 'OPEN' | 'ANSWERED' | 'CLOSED'

/**
 * GET /api/v1/admin/support/tickets: one ticket in the list. Null fields are left out by
 * the server, so optional ones may be missing.
 */
export interface AdminSupportTicket {
  ref: string
  /** The account's name, or the name on the order; missing for a guest who gave none. */
  customerName?: string | null
  email: string
  category?: SupportCategory | null
  subject: string
  orderRef?: string | null
  status: SupportStatus
  messages: number
  lastFrom?: 'CUSTOMER' | 'STAFF' | null
  createdAt: string
  lastActivityAt: string
}

export interface AdminSupportPage {
  items: AdminSupportTicket[]
  total: number
  page: number
  size: number
}

/** GET /api/v1/admin/support/overview: tickets per tab. */
export interface AdminSupportOverview {
  open: number
  waiting: number
  resolved: number
}

export interface AdminSupportMessage {
  id: number
  author: 'CUSTOMER' | 'STAFF'
  /** A NOTE is staff-only and never reaches the customer. */
  kind: 'MESSAGE' | 'NOTE'
  body: string
  /** Which member of staff wrote it; staff messages only. */
  authorLabel?: string | null
  at: string
}

/** GET /api/v1/admin/support/tickets/{ref}: a ticket with its whole thread, notes included. */
export interface AdminSupportDetail {
  ref: string
  status: SupportStatus
  category?: SupportCategory | null
  subject: string
  orderRef?: string | null
  createdAt: string
  resolvedAt?: string | null
  customerName?: string | null
  email: string
  hasAccount: boolean
  /** The customer's own private link to this ticket. */
  customerLink: string
  messages: AdminSupportMessage[]
}

/** GET /api/v1/support/tickets: the signed-in customer's tickets. */
export interface SupportTicketSummary {
  ref: string
  subject: string
  category?: SupportCategory | null
  status: SupportStatus
  lastActivityAt: string
}

/**
 * GET /api/v1/support/tickets/{ref}: a ticket as its customer sees it. Staff appear as
 * SUPPORT, never by name, and notes are never sent.
 */
export interface SupportThread {
  ref: string
  subject: string
  category?: SupportCategory | null
  status: SupportStatus
  orderRef?: string | null
  createdAt: string
  messages: { from: 'CUSTOMER' | 'SUPPORT'; body: string; at: string }[]
}

/** GET /api/v1/admin/saved-views: one person's named filters on a page. */
export interface SavedView {
  id: number
  name: string
  filters: Record<string, string>
  createdAt: string
}

/** GET /api/v1/admin/analytics/revenue. Admin only. */
export interface AdminRevenue {
  revenueLast30dMinor: number
  revenueLast30dFormatted: string
}

export interface WalletEntry {
  type: string
  amount: number
  description: string | null
  at: string
}

export interface Wallet {
  balance: number
  lifetimeEarned: number
  valueMinor: number
  valueFormatted: string
  pointValueMinor: number
  earnSpendUnitMinor: number
  earnPointsPerUnit: number
  maxRedemptionBps: number
  statement: WalletEntry[]
}

/** Loyalty standing. Assembled server-side — the tier is never derived in the browser. */
export interface LoyaltyStatus {
  tier: string
  lifetimePoints: number
  balancePoints: number
  pointsToNextTier: number
  nextTier: string | null
  discountBps: number
  canClaimDaily: boolean
  dailyBonusPoints: number
}

/** One weekday window a coach works, in the coach's own zone. */
export interface AvailabilityWindow {
  /** ISO-8601: Monday is 1, Sunday is 7. */
  dayOfWeek: number
  start: string
  end: string
}

/** A coach as the admin console sees them, schedule included. */
export interface CoachAdmin {
  id: string
  displayName: string
  headline: string | null
  timezone: string
  active: boolean
  sortOrder: number
  availability: AvailabilityWindow[]
}

export interface CoachTimeOff {
  id: number
  startsAt: string
  endsAt: string
  reason: string | null
}

/**
 * One row of the coach's diary.
 *
 * <p>`sessionLabel`, `orderRef` and `paymentStatus` are what make the row actionable —
 * without them a busy week is a list of times with no way to tell a confirmed session
 * from one sitting on an unpaid order.
 */
export interface AdminSession {
  ref: string
  coachName: string
  customerTimezone: string | null
  startsAt: string
  endsAt: string
  status: string
  creditReturned: boolean
  rescheduleCount: number
  customerNote: string | null
  meetingUrl: string | null
  allowedTransitions: string[]
  customerEmail: string | null
  orderRef: string | null
  sessionLabel: string | null
  paymentStatus: string | null
  /** From the order: who to look for in game, on what, at what level, and why. */
  inGameId: string | null
  platform: string | null
  rank: string | null
  improvementFocus: string | null
  /** When a PENDING hold lets go if the payment is still unverified. */
  holdExpiresAt: string | null
}

/** One line of a session's history: what changed, who changed it, and when. */
export interface AdminSessionEvent {
  type: string
  fromStatus: string | null
  toStatus: string | null
  fromTime: string | null
  toTime: string | null
  actor: string | null
  actorEmail: string | null
  detail: string | null
  at: string
}

/** The booking settings an admin sets, in minutes. */
export interface CoachingSettings {
  minNoticeMinutes: number
  bufferMinutes: number
  holdMinutes: number
  singleSessionMinutes: number
  blockSessionMinutes: number
}

/** A one-off window outside the weekly hours. */
export interface CoachExtraSlot {
  id: number
  startsAt: string
  endsAt: string
  reason: string | null
}

export interface Coach {
  id: string
  displayName: string
  headline: string | null
  bio: string | null
  avatarUrl: string | null
  languages: string | null
  credentials: string | null
  timezone: string
}

export interface CoachSlots {
  coachId: string
  coachTimezone: string
  sessionMinutes: number
  /** ISO instants. Rendered in the viewer's zone; never compared as strings. */
  slots: string[]
}

export interface CoachingPolicy {
  sessionMinutes: number
  blockSessionMinutes: number
  changeCutoffHours: number
  maxReschedules: number
  minLeadTimeHours: number
  creditValidityDays: number
  /** How far ahead booking is open. Bounds the calendar's forward paging. */
  maxAdvanceDays: number
}

export interface CoachingSession {
  ref: string
  coachId: string | null
  coachName: string
  startsAt: string
  endsAt: string
  status:
    | 'SCHEDULED'
    | 'COMPLETED'
    | 'CANCELLED_BY_CUSTOMER'
    | 'CANCELLED_BY_COACH'
    | 'NO_SHOW'
  customerTimezone: string | null
  meetingUrl: string | null
  customerNote: string | null
  rescheduleCount: number
  creditReturned: boolean
  /** Server-computed, from the same rule the cancel endpoint applies. */
  cancelRefundsCredit: boolean
  canReschedule: boolean
  /**
   * The order whose credits paid for this session.
   *
   * <p>Lets an order's tracking page list the sessions that belong to that order rather
   * than every session on the account. Null for a manual adjustment, and for anything
   * booked before sessions carried the link.
   */
  orderRef: string | null
}

export interface MyCoaching {
  creditBalance: number
  creditsExpireAt: string | null
  upcoming: CoachingSession[]
  policy: CoachingPolicy
}

/** How a customer may pay when they pay outside the gateway. */
export type ManualPaymentMethod = 'UPI' | 'PAYPAL' | 'CRYPTO'

/**
 * One payment destination, served by the API rather than held in this bundle.
 *
 * The server records which address it sent a customer to, so the address shown here
 * and the address written against the claim have to be the same string. Serving it is
 * what makes that true; a copy in the bundle would be a second source that can drift.
 */
export interface ManualPaymentOption {
  method: ManualPaymentMethod
  /** The payable address itself: a UPI id, a PayPal link, a wallet address. */
  destination: string
  /** Account holder, where there is one to show. Null for PayPal and crypto. */
  accountName: string | null
  /**
   * An optional way to open the payment instead of copying it — PayPal's managed-QR
   * link. Null where the destination is the only form there is.
   */
  link: string | null
  /** What this method's payers call their reference, e.g. "UTR". */
  referenceName: string
}

/**
 * The record of a customer saying they paid. Not a receipt — nothing is confirmed
 * until an operator finds the money.
 */
export interface ManualPaymentClaim {
  id: number
  method: ManualPaymentMethod
  reference: string
  status: 'SUBMITTED' | 'VERIFIED' | 'REJECTED'
  submittedAt: string
}

/** A claim as the operations console sees it, joined to the order it belongs to. */
export interface AdminPaymentClaim {
  id: number
  publicRef: string
  customerEmail: string | null
  sku: string
  totalMinor: number
  totalFormatted: string
  currency: string
  method: ManualPaymentMethod
  /** Which account the customer was told to pay — where to go looking. */
  destination: string
  reference: string
  /** Whether a screenshot is attached. Not the image — the queue only flags it. */
  hasProof: boolean
  status: 'SUBMITTED' | 'VERIFIED' | 'REJECTED'
  submittedAt: string
  reviewedAt: string | null
  reviewNote: string | null
}

/**
 * Coin pricing, as the Coin rates page edits it: two structures -- PC, and PlayStation +
 * Xbox sharing one market -- each with its slider and, per currency, a base price and
 * optional whole-order brackets. Prices travel as plain decimal text per 100,000 coins,
 * with the per-million figure the engine multiplies beside them.
 */
export interface CoinPricingOverview {
  season: string
  /** Every currency the shop sells in: each structure needs a base price in all of them. */
  currencies: string[]
  limits: CoinPricingLimits
  structures: CoinStructure[]
}

/** The rules a structure is checked against, so the page can say them before a save does. */
export interface CoinPricingLimits {
  smallestStepK: number
  maxCapK: number
  /** FUT Transfer's minimum per transfer, as configured: below it, a warning. */
  vendorMinTransferK: number
  defaultQuickPicksK: number[]
  maxQuickPicks: number
  maxBrackets: number
}

export type CoinMarketCode = 'PC' | 'CONSOLE'

/** One version of a structure. */
export interface CoinStructure {
  market: CoinMarketCode
  label: string
  platforms: string[]
  version: number | null
  validFrom: string | null
  /** Null for the live version. */
  validTo: string | null
  /** The admin who saved it; null for the migration that set it up. */
  setBy: string | null
  minK: number
  maxK: number
  stepK: number
  quickPicksK: number[]
  rates: CoinCurrencyRates[]
  warnings: string[]
}

export interface CoinCurrencyRates {
  currency: string
  symbol: string
  /** Null when this currency has no price yet. */
  base: CoinRateView | null
  brackets: CoinRateView[]
}

export interface CoinRateView {
  /** 0 for the base price; otherwise where the bracket starts, in K. */
  fromK: number
  /** As typed: per 100,000 coins, plain decimal text. */
  per100k: string
  per100kFormatted: string
  perMillionMinor: number
  perMillionFormatted: string
  per10k: string
  /** False when a 10K step costs a fraction of a cent: prices stay exact, steps differ by one. */
  stepIsWholeMinorUnit: boolean
}

/** A draft checked and priced by the server, saving nothing. */
export interface CoinPricingPreview {
  errors: string[]
  warnings: string[]
  rows: CoinPreviewRow[]
}

/** What one amount costs, from the same engine run a customer's quote makes. */
export interface CoinPreviewRow {
  amountK: number
  currency: string
  per100k: string
  per100kFormatted: string
  perMillionFormatted: string
  /** The coins, before EA's market tax. */
  coinPriceMinor: number
  coinPriceFormatted: string
  marketTaxLabel: string
  /** EA's tax is already in the price, as the shop is configured: the tax line is zero. */
  marketTaxIncluded: boolean
  marketTaxMinor: number
  marketTaxFormatted: string
  afterTaxMinor: number
  afterTaxFormatted: string
  /** What a guest pays with no discount, the card fee included. */
  guestTotalMinor: number
  guestTotalFormatted: string
}

/** What a campaign did, counted from its send log. */
/**
 * Which Discord panel the tracking page shows.
 *
 * - `DIRECT`  — already in the channel; link straight to it.
 * - `PENDING` — signed in with Discord, ticket not open yet; nothing to do.
 * - `VERIFY`  — join the server and run the command.
 * - `QUOTE`   — the command is not configured; join and quote the reference.
 * - `NONE`    — a service that is not run in Discord at all.
 */
export interface DiscordAccess {
  mode: 'DIRECT' | 'PENDING' | 'VERIFY' | 'QUOTE' | 'NONE'
  channelUrl: string | null
  inviteUrl: string | null
}

export interface CampaignStats {
  total: number
  sent: number
  failed: number
  opened: number
  clicked: number
  /** Counted from accounts that used this campaign's unsubscribe link. */
  unsubscribed: number
}

export interface Campaign {
  publicId: string
  title: string
  subject: string
  heading: string
  body: string
  promoCode: string | null
  ctaText: string | null
  ctaPath: string | null
  hasBanner: boolean
  audience: string
  audienceLabel: string
  status: 'DRAFT' | 'SCHEDULED' | 'SENDING' | 'SENT' | 'CANCELLED' | 'FAILED'
  scheduledAt: string | null
  completedAt: string | null
  updatedAt: string
  stats: CampaignStats
  /*
   * From the campaign builder's first step. Optional because the server leaves a null
   * field out of the response entirely rather than sending it as null.
   */
  type?: 'COINS' | 'BOOSTING' | 'COACHING' | 'GENERAL'
  offerText?: string
  /** YYYY-MM-DD, the offer's last day in India. */
  offerValidUntil?: string
  showButton?: boolean
  showPromoCode?: boolean
  trackingEnabled?: boolean
  heroKicker?: string
  heroSubline?: string
}

/** The fixed segments and buttons a campaign may use, with live audience counts. */
export interface CampaignOptions {
  /** `count` is the opted-in total as a number; `detail` says the same in words. */
  audiences: { value: string; label: string; detail: string; count?: number }[]
  ctas: { value: string; label: string; detail: string }[]
}

export interface ApiErrorBody {
  error: string
  message: string
  details?: Record<string, string[]>
  traceId?: string
}

// ---- order support ---------------------------------------------------------------------

/**
 * An order's support page, from the server. The mode is the order's own, never the
 * address's; the chat fields are the server's allowlist.
 */
export interface SupportContext {
  mode: 'BOOSTING' | 'COINS' | 'COACHING'
  summary: {
    /** A coin order FUT Transfer has: the page offers "Track your order". */
    transferStarted?: boolean
    reference: string
    service: string
    platform?: string | null
    status: string
    coins?: string | null
    session?: string | null
    sessionStartsAt?: string | null
    sessionTimezone?: string | null
    coach?: string | null
  }
  chat: {
    name?: string | null
    email?: string | null
    hash?: string | null
    attributes: Record<string, string>
  }
}

// ---- FUT Transfer, for admins -------------------------------------------------------

/** What an admin can do to an order the partner has. The server decides which apply. */
export type VendorActionName =
  | 'SEND_SIGN_IN' | 'RESUME' | 'STOP' | 'MARK_FINISHED' | 'RETRY' | 'LINK' | 'RESOLVE'

/** Everything we hold about one order at FUT Transfer. Amounts are in K, the partner's unit. */
export interface VendorOrderDetail {
  state: string
  externalRef: string
  vendorOrderId?: string | null
  amountOrderedK: number
  vendorAmountOrderedK?: number | null
  deliveredK?: number | null
  vendorStatus?: string | null
  vendorAccountCheck?: string | null
  vendorEconomyState?: string | null
  aborted?: boolean | null
  coinsUsed?: number | null
  /** The partner's cost figure. Its currency is not confirmed. */
  toPay?: number | null
  attempts: number
  lastErrorCode?: string | null
  reviewReason?: string | null
  customerAction?: string | null
  missingPolls: number
  submittedAt?: string | null
  lastPolledAt?: string | null
  lastProgressAt?: string | null
  resubmittedAt?: string | null
  updatedAt?: string | null
  /** How it was sent: PUBLIC_POOL (/buyCoinsAPI) or OWN_SENDERS (/orderAPI). */
  orderMode?: string | null
  /** The partner's method as sent. Absent for orders from before it was recorded. */
  transferMethod?: string | null
  /** What a public-pool order sent as buyNowThreshold, in the partner's unit (per 100K). Absent: not sent. */
  buyNowThresholdSent?: number | null
  /** maxPrice as sent. Absent: not sent. */
  maxPriceSent?: number | null
  /** The balance the partner reported just before a public-pool send. Its currency is not confirmed. */
  balanceAtSend?: number | null
}

/** One HTTP call to the partner. Never a body. */
export interface VendorCall {
  at: string
  endpoint: string
  domain: 'PRIMARY' | 'BACKUP'
  httpStatus?: number | null
  result: string
  errorCode?: string | null
  vendorOrderId?: string | null
  durationMs: number
}

/**
 * What the order's vendor history records: the admin actions above, plus sending it -- by an
 * admin's Approve, or by the automatic queue (which also records why it left an order).
 */
export type VendorHistoryAction = VendorActionName | 'APPROVE' | 'AUTO_DISPATCH'

/** One admin action on an order at the partner, and who did it. */
export interface VendorActionEntry {
  at: string
  action: VendorHistoryAction
  actorLabel?: string | null
  outcome: 'DONE' | 'REFUSED' | 'UNCERTAIN'
  code?: string | null
  detail?: string | null
}

export interface VendorSection {
  enabled: boolean
  paused: boolean
  vendorOrder?: VendorOrderDetail | null
  available: VendorActionName[]
  calls: VendorCall[]
  actions: VendorActionEntry[]
  /** How a send now would go, from configuration: PUBLIC_POOL or OWN_SENDERS. */
  currentOrderMode?: string | null
  /** What Approve would send for this order right now; null when FUT Transfer is off. */
  nextSend?: VendorNextSend | null
  /** What the customer can follow: when their transfer started, and their tracking page. */
  tracking?: { transferStartedAt: string | null; customerUrl: string | null } | null
}

/** How Approve would place one coin order, from the same decision Approve itself takes. */
export interface VendorNextSend {
  orderMode: string
  endpoint: string
  transferMethod: string
  /** Public pool only; null for own senders or when nothing would be sent. */
  buyNowThreshold: number | null
  buyNowThresholdSource: string | null
  maxPrice: number | null
  topUpEnabled: number
  autoFinishCycle: number
  /** Own senders only. */
  senderGroup: string | null
  /** Why Approve would send nothing at all, or null. */
  refusal: string | null
}

/** The balance FUT Transfer reports, read live. Its currency is not confirmed. */
export interface VendorBalance {
  /** Absent when it could not be read. */
  balance?: number | null
  available: boolean
  readAt?: string | null
  currency: string
}

/** How Approve would send an order: the mode configured now, and the one its last attempt used. */
export interface ReleasePreview {
  orderMode: string
  lastAttemptMode?: string | null
}

/** An order at the partner waiting for an admin's decision. */
export interface VendorReviewItem {
  externalRef: string
  state: 'NEEDS_REVIEW' | 'PARTIALLY_DELIVERED'
  lastErrorCode?: string | null
  reviewReason?: string | null
  amountOrderedK: number
  deliveredK?: number | null
  updatedAt: string
}

export interface VendorControlState {
  paused: boolean
  pausedAt?: string | null
  reason?: string | null
  resumedAt?: string | null
}

/* --------------------------------------------------- completing a payment --- */

export interface OrderLine {
  code: string
  label: string
  amountMinor: number
  amountFormatted: string
}

export interface PaymentBreakdown {
  lines: OrderLine[]
  totalMinor: number
  totalFormatted: string
}

/** A Payop invoice the customer can still pay, and the page to pay it on. */
export interface PayableInvoice {
  invoiceId: string
  methodName: string
  totalMinor: number
  currency: string
  payableUntil: string
  url: string
}

/** The slot a coaching order holds, or held before its hold ran out. */
export interface PaymentCoachingSlot {
  state: 'HELD' | 'EXPIRED' | 'NONE'
  coachId: string | null
  coachName: string | null
  startsAt: string | null
  endsAt: string | null
  timezone: string | null
  holdExpiresAt: string | null
  variant: string | null
}

/** Everything the "Complete your payment" step needs, from GET /orders/{ref}/payment. */
export interface OrderPaymentView {
  publicRef: string
  status: string
  paymentState: PaymentState | null
  payBy: string | null
  currency: string
  amountDueMinor: number
  amountDueFormatted: string
  manual: PaymentBreakdown | null
  payopOffered: boolean
  payableInvoice: PayableInvoice | null
  manualBlockedUntil: string | null
  claimSubmittedAt: string | null
  coaching: PaymentCoachingSlot | null
  signInNeeded: boolean
}

/** Staff: one way the customer tried to pay -- a Payop invoice or a payment claim. */
export interface PaymentAttempt {
  kind: 'PAYOP' | 'MANUAL'
  method: string
  status: string
  note: string | null
  totalMinor: number
  totalFormatted: string
  feeMinor: number | null
  feeFormatted: string | null
  at: string | null
  payableUntil: string | null
  superseded: boolean
}

/** POST /api/v1/admin/payop/orders/{ref}/recheck: each unpaid Payop invoice asked about, and the order after. */
export interface PayopRecheck {
  order: string
  orderStatus: string | null
  invoices: { invoiceId: string; statusBefore: string; outcome: string }[]
}

export interface OrderPaymentStaffView {
  current: PaymentAttempt | null
  currentBreakdown: PaymentBreakdown | null
  attempts: PaymentAttempt[]
}
