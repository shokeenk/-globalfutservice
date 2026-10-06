import { useEffect, useState } from 'react'
import { Badge, Card } from '../../../components/ui'
import { api } from '../../../lib/api'
import { dateTime } from '../../../lib/format'
import type { OrderPaymentStaffView, PaymentAttempt } from '../../../lib/types'

/**
 * How the customer is paying for this order.
 *
 * <p>The current method is the latest real attempt -- a Payop invoice opened or a payment
 * reference sent -- never a tab the customer merely looked at. Its breakdown is what that
 * method charges: the frozen price with the 2.5% card fee for UPI, PayPal and crypto, or
 * with the Payop method's own fee in its place. Every attempt is listed below it,
 * superseded Payop invoices included: Payop cannot cancel one, so it stays payable until
 * its 24 hours are up, and a payment on it still lands here.
 *
 * <p>Shown only when there has been an attempt; an order paid at checkout the ordinary way
 * has its claim on the Payments page as before.
 */
export function PaymentPanel({ publicRef }: { publicRef: string }) {
  const [view, setView] = useState<OrderPaymentStaffView | null>(null)

  useEffect(() => {
    let live = true
    api.get<OrderPaymentStaffView>(`/api/v1/admin/orders/${encodeURIComponent(publicRef)}/payment`)
      .then((found) => { if (live) setView(found) })
      .catch(() => { if (live) setView(null) })
    return () => { live = false }
  }, [publicRef])

  if (!view || view.attempts.length === 0) return null

  return (
    <div data-testid="payment-panel">
    <Card className="p-6">
      <p className="stamp mb-4">Payment</p>

      {view.current && (
        <div className="mb-5">
          <p className="text-[12px] text-chalk-faint">Current method</p>
          <p className="mt-1 text-[14px] font-semibold text-chalk">
            {view.current.method}{' '}
            <span className="text-[12px] font-normal text-chalk-muted">
              ({view.current.kind === 'PAYOP' ? 'Payop' : 'manual'}, {view.current.at ? dateTime(view.current.at) : '—'})
            </span>
          </p>
          {view.currentBreakdown && (
            <ul className="mt-3 space-y-2">
              {view.currentBreakdown.lines.map((line) => (
                <li key={line.code} className="flex justify-between gap-4 text-[13px]">
                  <span className="text-chalk-muted">{line.label}</span>
                  <span className={`tnum ${line.amountMinor < 0 ? 'text-ok' : 'text-chalk'}`}>
                    {line.amountFormatted}
                  </span>
                </li>
              ))}
              <li className="flex justify-between gap-4 border-t border-ink-400 pt-2 text-[13px] font-semibold">
                <span className="text-chalk">To pay with this method</span>
                <span className="tnum text-chalk">{view.currentBreakdown.totalFormatted}</span>
              </li>
            </ul>
          )}
        </div>
      )}

      <p className="text-[12px] text-chalk-faint">Every attempt, newest first</p>
      <div className="mt-2 overflow-x-auto">
        <table className="w-full text-left text-[12.5px]">
          <thead className="text-chalk-faint">
            <tr>
              <th className="py-1.5 pr-3 font-medium">When</th>
              <th className="py-1.5 pr-3 font-medium">Method</th>
              <th className="py-1.5 pr-3 font-medium">Status</th>
              <th className="py-1.5 pr-3 font-medium">Total</th>
              <th className="py-1.5 font-medium">Note</th>
            </tr>
          </thead>
          <tbody>
            {view.attempts.map((a, i) => <AttemptRow key={`${a.kind}-${a.at}-${i}`} attempt={a} />)}
          </tbody>
        </table>
      </div>
    </Card>
    </div>
  )
}

function AttemptRow({ attempt: a }: { attempt: PaymentAttempt }) {
  return (
    <tr className="border-t border-ink-400 align-top" data-testid="payment-attempt">
      <td className="py-2 pr-3 text-chalk-muted">{a.at ? dateTime(a.at) : '—'}</td>
      <td className="py-2 pr-3 text-chalk">
        {a.method}
        <span className="ml-1 text-chalk-faint">{a.kind === 'PAYOP' ? 'Payop' : 'manual'}</span>
      </td>
      <td className="py-2 pr-3">
        <span className="text-chalk">{a.status}</span>
        {a.superseded && <Badge tone="neutral" className="ml-1.5">Superseded</Badge>}
        {a.payableUntil && (
          <span className="block text-[11.5px] text-chalk-faint">payable until {dateTime(a.payableUntil)}</span>
        )}
      </td>
      <td className="tnum py-2 pr-3 text-chalk">
        {a.totalFormatted}
        {a.feeFormatted && <span className="block text-[11.5px] text-chalk-faint">fee {a.feeFormatted}</span>}
      </td>
      <td className="py-2 text-chalk-muted">{a.kind === 'MANUAL' ? a.note : a.note === 'REPLACED' ? 'Customer chose another method' : a.note ?? ''}</td>
    </tr>
  )
}
