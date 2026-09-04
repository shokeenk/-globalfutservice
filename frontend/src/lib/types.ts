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

export interface Order {
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
}

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
  publicRef: string
  status: string
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
}

export interface MyCoaching {
  creditBalance: number
  creditsExpireAt: string | null
  upcoming: CoachingSession[]
  policy: CoachingPolicy
}

export interface ApiErrorBody {
  error: string
  message: string
  details?: Record<string, string[]>
  traceId?: string
}
