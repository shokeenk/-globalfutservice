import { useCallback } from 'react'
import { useCatalog } from '../state/CatalogContext'

/**
 * Formats minor units in whatever currency the catalogue is currently serving.
 *
 * <p>Exists because the page copy used to hardcode `₹`. That was correct when INR was
 * the only currency and became a lie the moment the picker appeared — a visitor
 * switching to euros would still have been told the reward rate in rupees.
 *
 * <p>Prices that come from the server already arrive formatted (`unitPriceFormatted`)
 * and should be rendered as-is; this is only for numbers the storefront composes
 * itself out of policy values, where there is no server-rendered string to use.
 *
 * <p>Grouping follows the reader's locale rather than the currency's home country, so
 * an Indian customer paying in dollars still reads digits grouped the Indian way.
 */
export function useMoney() {
  const { catalog, currency } = useCatalog()
  const code = catalog?.currency ?? currency ?? 'INR'

  // Only the currency belongs in the dependency list. `decimals` is an argument of the
  // returned function, decided per call site, and putting it here would rebuild the
  // formatter on renders where nothing changed.
  return useCallback(
    (minorUnits: number, decimals = false) =>
      new Intl.NumberFormat(undefined, {
        style: 'currency',
        currency: code,
        // Whole amounts by default: "₹2,000" reads better inside a sentence than
        // "₹2,000.00". Call sites showing an exact charge pass decimals.
        minimumFractionDigits: decimals ? 2 : 0,
        maximumFractionDigits: decimals ? 2 : 0,
      }).format(minorUnits / 100),
    [code],
  )
}

const SYMBOL: Record<string, string> = { INR: '₹', USD: '$', EUR: '€', GBP: '£' }

/**
 * An amount in minor units, written the way the server writes it: symbol, Indian grouping
 * for rupees (₹3,42,850.00) and Western for the rest ($1,250.00), two decimals.
 *
 * <p>For amounts the page composes itself next to amounts the server formatted -- an
 * hourly price times the hours chosen -- so the two read identically.
 */
export function formatMinor(minor: number, currency: string): string {
  const negative = minor < 0
  const abs = Math.abs(minor)
  const whole = Math.floor(abs / 100).toString()
  const fraction = (abs % 100).toString().padStart(2, '0')
  let grouped: string
  if (currency === 'INR' && whole.length > 3) {
    const last3 = whole.slice(-3)
    const rest = whole.slice(0, -3).replace(/\B(?=(\d{2})+(?!\d))/g, ',')
    grouped = `${rest},${last3}`
  } else {
    grouped = whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  }
  return `${negative ? '-' : ''}${SYMBOL[currency] ?? `${currency} `}${grouped}.${fraction}`
}
