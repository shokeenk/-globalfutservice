/**
 * The operating entity, written once.
 *
 * <p>These four facts have to appear identically on the contact page, in the footer,
 * and inside every policy document that names who you are contracting with. A payment
 * gateway's reviewer checks that they match; a customer chasing an order checks that
 * the number they are dialling is the one on the invoice. Typing them out five times
 * is how the address on one page ends up a flat number different from another.
 *
 * <p>Taken from the KYC submission, and formatted exactly as filed. The legal name is
 * the one on the PAN, which is why it is a person rather than a trading name — the
 * business trades as Global FUT Services and is operated by an individual, and the
 * policy pages have to say both.
 */
export const BUSINESS = {
  /** As per PAN. Appears wherever the operator must be named. */
  legalName: 'Vinay Kumar Sharma',
  tradingName: 'Global FUT Services',
  registeredAddress: '2/40 KBHB, Jodhpur, Rajasthan - 342005',
  phone: '+91 7339744705',
  email: 'globalfutservices@gmail.com',

  /**
   * The line-of-business statement.
   *
   * <p>Written to be accurate rather than flattering: a gateway reviewing this
   * compares it against what the site actually sells, and a description that
   * undersells ("gaming services") reads as evasive next to a checkout selling
   * virtual currency. All three revenue lines are named.
   */
  lineOfBusiness:
    'Global FUT Services provides digital gaming services including virtual currency '
    + 'sales, coaching, and competitive boosting for EA Sports FC Ultimate Team players.',
} as const

/** `tel:` needs the number without spaces; the display form keeps them. */
export const PHONE_HREF = `tel:${BUSINESS.phone.replace(/\s+/g, '')}`
export const EMAIL_HREF = `mailto:${BUSINESS.email}`
