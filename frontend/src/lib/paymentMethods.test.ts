import { describe, expect, it } from 'vitest'
import { INTERNATIONAL, ONLINE, offersLocalMethods, paymentChoices, paymentMethods } from './paymentMethods'
import type { ManualPaymentOption } from './types'

/** What the server offers an order of each kind: the same methods, only the UPI account differs. */
function offered(upi: string): ManualPaymentOption[] {
  return [
    { method: 'UPI', destination: upi, accountName: 'Global FUT Services', link: null, referenceName: 'UTR' },
    { method: 'PAYPAL', destination: 'pay@gfs.test', accountName: null, link: null, referenceName: 'transaction ID' },
    { method: 'CRYPTO', destination: 'TWALLET', accountName: null, link: null, referenceName: 'TXID' },
  ] as ManualPaymentOption[]
}

describe('one list of ways to pay', () => {
  it('coins, boosting and coaching get the same set, International included', () => {
    const coins = paymentMethods(offered('coins@upi'))
    const boosting = paymentMethods(offered('services@upi'))
    const coaching = paymentMethods(offered('services@upi'))
    expect(coins).toEqual(['UPI', 'PAYPAL', 'CRYPTO', INTERNATIONAL])
    expect(boosting).toEqual(coins)
    expect(coaching).toEqual(coins)
  })

  it('International is listed even when the server offers fewer manual methods', () => {
    expect(paymentMethods([offered('x')[1]!])).toEqual(['PAYPAL', INTERNATIONAL])
  })

  it('a checkout\'s choices: the card gateway first where it is on, then the same list', () => {
    expect(paymentChoices(offered('x'), true)).toEqual([ONLINE, 'UPI', 'PAYPAL', 'CRYPTO', INTERNATIONAL])
    expect(paymentChoices(offered('x'), false)).toEqual(['UPI', 'PAYPAL', 'CRYPTO', INTERNATIONAL])
  })

  it('International holds the local methods for an order in any currency but INR -- the order\'s currency', () => {
    expect(offersLocalMethods('INR')).toBe(false)
    for (const currency of ['USD', 'EUR', 'GBP']) expect(offersLocalMethods(currency)).toBe(true)
    // No order currency known: nothing is promised.
    expect(offersLocalMethods(null)).toBe(false)
    expect(offersLocalMethods(undefined)).toBe(false)
  })
})

/*
 * Read from the source itself, so a checkout that starts listing its own methods again
 * fails here whatever it renders in a test.
 */
const SOURCES: Record<string, string> = import.meta.glob<string>(
  ['/src/**/*.{ts,tsx}', '!/src/**/*.test.{ts,tsx}'],
  { query: '?raw', import: 'default', eager: true },
)

describe('every place that shows the ways to pay builds them from this list', () => {
  it('coaching, boosting and the payment panel use it; coins and "Complete your payment" pay through the panel', () => {
    for (const path of ['/src/pages/CoachingBook.tsx', '/src/pages/BoostingCheckout.tsx']) {
      expect(SOURCES[path], path).toMatch(/import \{[^}]*paymentChoices[^}]*\} from '\.\.\/lib\/paymentMethods'/)
    }
    expect(SOURCES['/src/components/ManualPayment.tsx']).toMatch(
      /import \{[^}]*paymentMethods[^}]*\} from '\.\.\/lib\/paymentMethods'/)
    expect(SOURCES['/src/pages/Order.tsx']).toMatch(/<ManualPayment\b/)
    expect(SOURCES['/src/components/CompletePayment.tsx']).toMatch(/<ManualPayment\b/)
  })

  it('nobody lists the methods by hand any more', () => {
    const byHand = Object.entries(SOURCES)
      .filter(([, text]) => /method === '(UPI|PAYPAL|CRYPTO)'\) list\.push/.test(text)
        || /\[\.\.\.options\.map\(\(option\) => option\.method\), 'INTERNATIONAL'\]/.test(text))
      .map(([path]) => path)
    expect(byHand).toEqual([])
  })
})
