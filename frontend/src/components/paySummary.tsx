import { createContext, useCallback, useContext, useRef, type ReactNode } from 'react'
import { Alert, Button } from './ui'
import { useT } from '../i18n'
import type { QuoteLine } from '../lib/types'

/**
 * What the order summary beside a pay step shows: it follows how the customer is paying.
 *
 * Every way but International -- UPI, PayPal, USDT, a card -- pays the order's own total,
 * 2.5% card fee included, so the summary shows the order as placed. International pays
 * through Payop, which never carries the card fee: the order's lines without it, the chosen
 * method's own fee from the fee sheet, and that total, then the button that starts the
 * payment. Every figure is the server's; nothing here adds anything up. Before a method is
 * chosen the fee line says so, and there is no total yet.
 *
 * The pay step reports it (ManualPayment, PayopPayment) through {@link PaySummaryProvider};
 * the page's summary card reads it. The total appears there, once.
 */
export interface PayopSelection {
  /** The order's lines before any payment fee, as the server sent them: no 2.5% card fee. */
  lines: QuoteLine[]
  /** The chosen method, priced by the server; null until one is chosen. */
  method: { name: string; feeMinor: number; feeFormatted: string; totalFormatted: string } | null
  /** Starts the payment with the chosen method. */
  pay: () => void
  paying: boolean
  error: string | null
}

export type PaySummary = { kind: 'order' } | ({ kind: 'payop' } & PayopSelection)

/** The order as placed: what every way of paying but International is charged. */
export const ORDER_SUMMARY: PaySummary = { kind: 'order' }

const Publish = createContext<((summary: PaySummary) => void) | null>(null)

/**
 * Around a pay step: what it reports goes to `publish`, for the page's summary card. The step
 * is handed one function for as long as it is mounted, whatever `publish` is from render to
 * render, so reporting never sets off another report.
 */
export function PaySummaryProvider({ publish, children }: {
  publish: (summary: PaySummary) => void
  children: ReactNode
}) {
  const latest = useRef(publish)
  latest.current = publish
  const stable = useCallback((summary: PaySummary) => latest.current(summary), [])
  return <Publish.Provider value={stable}>{children}</Publish.Provider>
}

/** Where a pay step reports what the summary should show. */
export function usePublishPaySummary(): ((summary: PaySummary) => void) | null {
  return useContext(Publish)
}

/** Line codes that are a payment fee: the card fee, or a method's own. */
const FEE_CODES = ['GATEWAY_FEE', 'PAYMENT_FEE']

/** The order's lines without their payment fee: the price a Payop method's fee is added to. */
export function withoutPaymentFees(lines: readonly QuoteLine[]): QuoteLine[] {
  return lines.filter((l) => !FEE_CODES.includes(l.code))
}

/**
 * The lines a Payop payment is priced on: the server's, and until they arrive, the order's
 * own lines without their payment fee -- the same lines, as the order was frozen.
 */
export function payopLines(selection: PayopSelection, orderLines: readonly QuoteLine[]): QuoteLine[] {
  return selection.lines.length > 0 ? selection.lines : withoutPaymentFees(orderLines)
}

/**
 * The Payop fee as a line of the summary: the method's fee, named, or "Select a payment
 * method" in the amount's place until one is chosen.
 */
export function payopFeeLine(selection: { method: PayopSelection['method'] } | null, selectText: string): QuoteLine {
  const method = selection?.method ?? null
  return {
    code: 'PAYMENT_FEE',
    label: '',
    amountMinor: method?.feeMinor ?? 0,
    amountFormatted: method?.feeFormatted ?? selectText,
    method: method?.name ?? null,
  }
}

/** The total a Payop payment comes to, or a dash until a method is chosen. */
export function payopTotal(selection: PayopSelection): string {
  return selection.method?.totalFormatted ?? '—'
}

/** Under the total: the button that starts the Payop payment, what went wrong, and where it goes. */
export function PayopPayButton({ selection }: { selection: PayopSelection }) {
  const t = useT()
  if (!selection.method) return null
  return (
    <div className="space-y-2" data-testid="payop-pay">
      {selection.error && <Alert tone="warn">{selection.error}</Alert>}
      <Button full size="lg" loading={selection.paying} onClick={selection.pay}>
        {t.order.payopPay}
      </Button>
      <p className="text-[12px] leading-snug text-chalk-faint">{t.order.payopRedirectNote}</p>
    </div>
  )
}
