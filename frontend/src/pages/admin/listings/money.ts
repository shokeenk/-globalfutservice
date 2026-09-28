/**
 * Prices and rates as a person types them, and as the server stores them.
 *
 * <p>Every currency the site sells in has two decimal places, so a price is its major
 * amount times a hundred. A typed "1,900" and "1900.00" are the same ₹1,900.00; anything
 * that is not a number is refused rather than guessed at.
 */
export const CURRENCY_SYMBOL: Record<string, string> = { INR: '₹', USD: '$', EUR: '€', GBP: '£' }

/** 190000 -> "1900", 2399 -> "23.99". */
export function toMajor(minor: number): string {
  const whole = Math.floor(minor / 100)
  const cents = minor % 100
  return cents === 0 ? String(whole) : `${whole}.${String(cents).padStart(2, '0')}`
}

/** "1,900" -> 190000, "23.99" -> 2399; null for anything that is not a price. */
export function fromMajor(text: string): number | null {
  const clean = text.replace(/[,\s]/g, '')
  if (!/^\d+(\.\d{1,2})?$/.test(clean)) return null
  const [whole, fraction = ''] = clean.split('.')
  return Number(whole) * 100 + Number(fraction.padEnd(2, '0'))
}

/** 9200 -> "92", 9250 -> "92.5". */
export function toPercent(bps: number): string {
  return String(bps / 100)
}

/** "92" -> 9200, "92.5" -> 9250; null for anything that is not a percentage. */
export function fromPercent(text: string): number | null {
  const clean = text.replace(/[%\s]/g, '')
  if (!/^\d+(\.\d{1,2})?$/.test(clean)) return null
  return Math.round(Number(clean) * 100)
}
