import type { ManualPaymentMethod, ManualPaymentOption } from './types'

/**
 * The ways to pay an order: one list for every kind of order -- coins, boosting and
 * coaching -- and for "Complete your payment".
 *
 * <p>The manual methods the server offers for the order (UPI, PayPal, crypto: whichever
 * have an address configured), then International. International is always listed; what
 * is inside it follows the order's currency (see {@link offersLocalMethods}).
 *
 * <p>Built here and nowhere else. Coaching's checkout once listed its methods by hand,
 * left International out, and its customers never saw "Pay with a local method".
 */
export const INTERNATIONAL = 'INTERNATIONAL' as const

export type PaymentMethodKey = ManualPaymentMethod | typeof INTERNATIONAL

/** Card payment through the gateway, where one is configured: offered before the rest. */
export const ONLINE = 'ONLINE' as const

export type PaymentChoiceKey = typeof ONLINE | PaymentMethodKey

export function paymentMethods(offered: readonly ManualPaymentOption[]): PaymentMethodKey[] {
  return [...offered.map((option) => option.method), INTERNATIONAL]
}

/** A checkout's choices: the gateway first when it is on, then {@link paymentMethods}. */
export function paymentChoices(offered: readonly ManualPaymentOption[], onlineEnabled: boolean): PaymentChoiceKey[] {
  return [...(onlineEnabled ? [ONLINE] : []), ...paymentMethods(offered)]
}

/**
 * Whether International holds Payop's local methods ("Pay with a local method") for an
 * order in this currency. Decided by the ORDER's currency -- never the display currency or
 * where the visitor is: INR orders never see Payop.
 */
export function offersLocalMethods(orderCurrency: string | null | undefined): boolean {
  return !!orderCurrency && orderCurrency !== 'INR'
}
