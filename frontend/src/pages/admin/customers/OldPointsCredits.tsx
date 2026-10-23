import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../../../lib/api'
import { shortDate } from '../ui/format'

export interface PointsCredit {
  orderRef: string
  customerKey: string
  customerName: string | null
  customerEmail: string
  points: number
  at: string
  reversed: boolean
  currentBalance: number
}

/**
 * Orders settled as store credit the old way, as loyalty points: the ones to look at. Each
 * opens its customer, whose Points section has the corrections. Shown only while there are any.
 */
export function OldPointsCredits({ onOpen }: { onOpen: (customerKey: string) => void }) {
  const [rows, setRows] = useState<PointsCredit[] | null>(null)
  useEffect(() => {
    api.get<PointsCredit[]>('/api/v1/admin/customers/points-credits').then(setRows).catch(() => setRows(null))
  }, [])
  if (!rows || rows.length === 0) return null
  const open = rows.filter((r) => !r.reversed).length
  return (
    <section aria-labelledby="old-points-credits" data-testid="old-points-credits"
      className="mb-4 rounded-admin-card border border-admin-line bg-white p-4 shadow-admin-card">
      <h2 id="old-points-credits" className="text-[15px] font-semibold text-admin-ink">
        Store credit paid as points ({open} to review)
      </h2>
      <p className="mt-0.5 text-[12.5px] text-admin-muted">
        These orders were settled as store credit before it had its own wallet, so the credit was added as reward
        points. Open the customer and use Points to correct it.
      </p>
      <div className="mt-3 overflow-x-auto">
        <table className="w-full min-w-[560px] text-left text-[12.5px]">
          <thead className="text-[11.5px] text-admin-faint">
            <tr>
              <th scope="col" className="pb-1 font-medium">Order</th>
              <th scope="col" className="pb-1 font-medium">Customer</th>
              <th scope="col" className="pb-1 font-medium">Settled</th>
              <th scope="col" className="pb-1 text-right font-medium">Points added</th>
              <th scope="col" className="pb-1 text-right font-medium">Balance now</th>
              <th scope="col" className="pb-1 font-medium"><span className="sr-only">Status</span></th>
            </tr>
          </thead>
          <tbody className="divide-y divide-admin-line">
            {rows.map((r) => (
              <tr key={r.orderRef}>
                <td className="py-1.5 pr-2">
                  <Link to={`/admin/orders/${r.orderRef}`} className="font-medium text-admin-ink hover:underline">#{r.orderRef}</Link>
                </td>
                <td className="py-1.5 pr-2">
                  <button type="button" onClick={() => onOpen(r.customerKey)} className="text-left text-admin-ink hover:underline">
                    {r.customerName || r.customerEmail}
                  </button>
                </td>
                <td className="py-1.5 pr-2 text-admin-muted">{shortDate(r.at)}</td>
                <td className="py-1.5 text-right tabular-nums">{r.points.toLocaleString('en-IN')}</td>
                <td className="py-1.5 text-right tabular-nums">{r.currentBalance.toLocaleString('en-IN')}</td>
                <td className="py-1.5 pl-2 text-admin-muted">{r.reversed ? 'Reversed' : ''}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  )
}
