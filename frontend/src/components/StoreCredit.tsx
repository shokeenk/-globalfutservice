import { useEffect, useState } from 'react'
import { useT } from '../i18n'
import { api } from '../lib/api'
import type { SignedQuote } from '../lib/types'

/**
 * "Use my GFS Store Credit", in a checkout's order panel.
 *
 * <p>Shown only when the customer holds credit in the order's currency -- the quote says so,
 * whether or not it is being used. A yes or no, never an amount: the server spends as much
 * as the order costs, up to the balance, and charges the payment fee only on what is left.
 */
export function StoreCreditRow({
  quote, use, onChange, busy,
}: {
  quote: SignedQuote | null
  use: boolean
  onChange: (next: boolean) => void
  busy?: boolean
}) {
  const t = useT()
  const s = t.storeCredit
  const available = quote?.storeCreditAvailableFormatted
  if (!quote || !available || !quote.storeCreditAvailableMinor) return null

  const spent = quote.storeCreditMinor ?? 0
  const spentLine = quote.lines.find((line) => line.code === 'STORE_CREDIT')
  return (
    <div className="rounded-edge border border-ink-400 bg-paper p-3.5" data-testid="store-credit">
      <label className="flex cursor-pointer items-start gap-3">
        <input
          type="checkbox"
          className="mt-0.5 h-4 w-4 shrink-0 accent-brand-500"
          checked={use}
          disabled={busy}
          onChange={(e) => onChange(e.target.checked)}
        />
        <span className="min-w-0">
          <span className="block text-[13px] font-semibold text-chalk">{s.use}</span>
          <span className="mt-0.5 block text-[12px] text-chalk-muted">{s.available(available)}</span>
          {use && spent > 0 && spentLine && (
            <span className="mt-1 block text-[12px] font-semibold text-ok">
              {s.applied(spentLine.amountFormatted.replace(/^-/, ''))}
            </span>
          )}
          {use && spent > 0 && quote.totalMinor === 0 && (
            <span className="mt-1 block text-[12px] text-chalk-muted">{s.coversAll}</span>
          )}
        </span>
      </label>
    </div>
  )
}

export interface StoreCreditBalances {
  balances: { currency: string; minor: number; formatted: string }[]
  statement: { type: string; currency: string; amountMinor: number; amountFormatted: string;
               description: string; at: string }[]
}

/**
 * The customer's GFS Store Credit, in each currency they hold it in. On Rewards and the
 * account page. Says plainly when there is none, and what credit is for.
 */
export function StoreCreditBalance({ className = '' }: { className?: string }) {
  const t = useT()
  const s = t.storeCredit
  const [data, setData] = useState<StoreCreditBalances | null>(null)

  useEffect(() => {
    let live = true
    api.get<StoreCreditBalances>('/api/v1/account/store-credit')
      .then((found) => { if (live) setData(found) })
      .catch(() => { if (live) setData(null) })
    return () => { live = false }
  }, [])

  if (!data) return null
  return (
    <div className={className} data-testid="store-credit-balance">
      <p className="text-[10.5px] font-semibold uppercase tracking-[0.16em] text-brand-400">{s.balanceTitle}</p>
      {data.balances.length === 0 ? (
        <p className="mt-3 text-[13px] text-chalk-muted">{s.balanceNone}</p>
      ) : (
        <ul className="mt-3 space-y-1">
          {data.balances.map((b) => (
            <li key={b.currency} className="tnum display text-[22px] text-chalk">{b.formatted}</li>
          ))}
        </ul>
      )}
      <p className="mt-3 text-[12px] leading-relaxed text-chalk-faint">{s.balanceHelp}</p>
    </div>
  )
}
