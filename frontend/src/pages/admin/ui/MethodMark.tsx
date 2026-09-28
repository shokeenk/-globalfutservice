import { SiPaypal, SiTether } from 'react-icons/si'
import { METHOD_LABEL } from './status'

/**
 * How a customer paid: the rail's mark and its name.
 *
 * <p>PayPal and Tether (USDT) are drawn from their own marks. UPI has none in the icon set,
 * and its logo is NPCI's trademark with rules for its use, so it is set as the three
 * letters in UPI's colours rather than as a copy of the logo. Only the three methods the
 * site takes exist here.
 */
export function MethodMark({ method, compact = false }: { method: string; compact?: boolean }) {
  const label = METHOD_LABEL[method] ?? method
  const mark = method === 'PAYPAL'
    ? <SiPaypal aria-hidden="true" className="h-[18px] w-[18px] text-[#003087]" />
    : method === 'CRYPTO'
      ? <SiTether aria-hidden="true" className="h-[18px] w-[18px] text-[#26A17B]" />
      : method === 'UPI'
        ? (
          <span aria-hidden="true" className="inline-flex h-[18px] items-center text-[11px] font-extrabold italic tracking-tight">
            <span className="text-[#097939]">U</span><span className="text-[#ED752E]">P</span><span className="text-[#097939]">I</span>
          </span>
        )
        : null
  // Compact, beside an open panel: the mark alone, with the name for screen readers and on hover.
  return (
    <span className="inline-flex items-center gap-2 whitespace-nowrap" title={compact ? label : undefined}>
      {mark && <span className="grid w-7 place-items-center">{mark}</span>}
      <span className={compact && mark ? 'sr-only' : 'text-admin-muted'}>{label}</span>
    </span>
  )
}
