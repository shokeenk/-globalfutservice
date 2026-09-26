/**
 * The campaign builder's first step: its fields, its limits, and what counts as valid.
 *
 * <p>Kept free of React so the rules can be tested on their own, and so the page, the
 * counters and the preview all read the same numbers. The limits mirror the server's
 * (AdminCampaignController.DetailsRequest) exactly; the server is still the authority,
 * and this exists so an admin learns about a problem while typing rather than on save.
 */

export type CampaignType = 'COINS' | 'BOOSTING' | 'COACHING' | 'GENERAL'

export interface StepOneFields {
  title: string
  subject: string
  type: CampaignType
  promoTitle: string
  offerText: string
  promoCode: string
  /** YYYY-MM-DD, as a date input gives it, or empty. */
  offerValidUntil: string
  description: string
  showButton: boolean
  showPromoCode: boolean
  trackingEnabled: boolean
}

export type FieldName = keyof StepOneFields
export type FieldErrors = Partial<Record<FieldName, string>>

export const LIMITS = {
  title: 120,
  subject: 100,
  promoTitle: 200,
  offerText: 40,
  promoCode: 40,
  description: 500,
} as const

/** The order errors are reported and focused in: the order the fields appear on screen. */
export const FIELD_ORDER: FieldName[] = [
  'title', 'subject', 'type', 'promoTitle', 'offerText', 'promoCode', 'offerValidUntil',
  'description',
]

export function emptyStepOne(): StepOneFields {
  return {
    title: '',
    subject: '',
    type: 'COINS',
    promoTitle: '',
    offerText: '',
    promoCode: '',
    offerValidUntil: '',
    description: '',
    // The reference shows all three switched on.
    showButton: true,
    showPromoCode: true,
    trackingEnabled: true,
  }
}

/**
 * How long a value is, counted the way the server counts it.
 *
 * <p>UTF-16 code units, which is what both `String.length` in the browser and
 * `@Size` on the server measure. An emoji is two. Counting "characters" as a person
 * would see them would let the counter read 100 on a subject the server refuses.
 */
export function lengthOf(value: string): number {
  return value.length
}

/**
 * Today's date in India, as YYYY-MM-DD.
 *
 * <p>An offer's last day is a day in the business's calendar. Using the browser's own
 * date would let an admin abroad pick a day that has already ended in India, which the
 * server would then refuse.
 */
export function todayInIndia(now: Date = new Date()): string {
  // en-CA formats as YYYY-MM-DD.
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Kolkata', year: 'numeric', month: '2-digit', day: '2-digit',
  }).format(now)
}

const REQUIRED: [FieldName, string][] = [
  ['title', 'Give the campaign a name.'],
  ['subject', 'An email subject is required.'],
  ['promoTitle', 'A promo title is required.'],
  ['description', 'Describe the offer.'],
]

const TOO_LONG: [keyof typeof LIMITS, string][] = [
  ['title', 'name'],
  ['subject', 'subject'],
  ['promoTitle', 'promo title'],
  ['offerText', 'offer'],
  ['promoCode', 'code'],
  ['description', 'description'],
]

/** Every problem with the step, keyed by field. Empty when the step can be saved. */
export function validateStepOne(f: StepOneFields, today: string = todayInIndia()): FieldErrors {
  const errors: FieldErrors = {}
  for (const [name, message] of REQUIRED) {
    if (!(f[name] as string).trim()) errors[name] = message
  }
  for (const [name, noun] of TOO_LONG) {
    if (!errors[name] && lengthOf(f[name]) > LIMITS[name]) {
      errors[name] = `Keep the ${noun} to ${LIMITS[name]} characters.`
    }
  }
  if (f.offerValidUntil) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(f.offerValidUntil)) {
      errors.offerValidUntil = 'Enter a date.'
    } else if (f.offerValidUntil < today) {
      // String comparison is date comparison for YYYY-MM-DD.
      errors.offerValidUntil = 'That day has already passed. Pick today or a later date.'
    }
  }
  return errors
}

/** The first field with a problem, in screen order, for focusing. */
export function firstInvalid(errors: FieldErrors): FieldName | undefined {
  return FIELD_ORDER.find((name) => errors[name])
}

/** The request body the server's DetailsRequest expects. */
export function toDetailsRequest(f: StepOneFields) {
  return {
    title: f.title.trim(),
    subject: f.subject.trim(),
    type: f.type,
    promoTitle: f.promoTitle.trim(),
    offerText: f.offerText.trim() || null,
    promoCode: f.promoCode.trim() || null,
    offerValidUntil: f.offerValidUntil || null,
    description: f.description,
    showButton: f.showButton,
    showPromoCode: f.showPromoCode,
    trackingEnabled: f.trackingEnabled,
  }
}

// ---- step 2: the lines above and below the headline ---------------------------------

export interface StepTwoFields {
  kicker: string
  subline: string
}

/** Kept to one line of the hero each; the server enforces the same numbers. */
export const CONTENT_LIMITS = { kicker: 40, subline: 60 } as const

export type ContentErrors = Partial<Record<keyof StepTwoFields, string>>

export function validateStepTwo(f: StepTwoFields): ContentErrors {
  const errors: ContentErrors = {}
  if (lengthOf(f.kicker) > CONTENT_LIMITS.kicker) {
    errors.kicker = `Keep the line above the headline to ${CONTENT_LIMITS.kicker} characters.`
  }
  if (lengthOf(f.subline) > CONTENT_LIMITS.subline) {
    errors.subline = `Keep the line below the headline to ${CONTENT_LIMITS.subline} characters.`
  }
  return errors
}

// ---- step 5: whether a send fits what is left of the day ------------------------------

/**
 * How a campaign's audience compares with the provider's remaining allowance.
 *
 * <p>Worth being exact about, because the consequence is permanent: a recipient the
 * provider refuses is marked failed and never retried, so every recipient past the
 * allowance misses the campaign for good.
 */
export function quotaCheck(audience: number, remaining: number) {
  const over = Math.max(0, audience - remaining)
  return { over, fits: over === 0 }
}

/**
 * The request body for the live preview: the same fields, sent as they stand. Nothing is
 * trimmed or checked, because the preview has to render while the admin is mid-word.
 */
export function toPreviewRequest(f: StepOneFields & Partial<StepTwoFields>, campaignId: string | null) {
  return {
    subject: f.subject,
    type: f.type,
    promoTitle: f.promoTitle,
    offerText: f.offerText || null,
    promoCode: f.promoCode || null,
    offerValidUntil: /^\d{4}-\d{2}-\d{2}$/.test(f.offerValidUntil) ? f.offerValidUntil : null,
    description: f.description,
    showButton: f.showButton,
    showPromoCode: f.showPromoCode,
    kicker: f.kicker || null,
    subline: f.subline || null,
    campaignId,
  }
}
