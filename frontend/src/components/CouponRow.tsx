import { useState } from 'react'
import { Button } from './ui'
import { useT } from '../i18n'

/**
 * The discount code field, in the order panel.
 *
 * <p>Holds its own draft so typing does not re-quote. The parent's `couponCode` is what
 * the pricing engine is asked about, and it only changes when Apply is pressed -- the
 * quote is re-signed server-side on every change, so a live-applied field turns one code
 * into six requests and five "no such coupon" messages before the sixth succeeds.
 *
 * <p>Remounted by the parent whenever the applied code changes (`key`), so the draft
 * cannot drift out of step with what is actually priced.
 *
 * <p>The message under the field is the server's, never a guess: "already used",
 * "expired", "needs a larger order". A coupon that silently does nothing produces a
 * support ticket every single time.
 */
export function CouponRow({
  initial, applied, message, busy, onApply,
}: {
  initial: string
  applied: string | null
  message: string | null
  busy: boolean
  onApply: (code: string) => void
}) {
  const t = useT()
  const [draft, setDraft] = useState(initial)

  const trimmed = draft.trim()
  const isApplied = applied != null && trimmed.toUpperCase() === applied.toUpperCase()
  const canApply = trimmed !== '' && !isApplied && !busy

  return (
    <div>
      <label htmlFor="coupon-code" className="stamp mb-2 block">
        {t.order.couponLabel}
      </label>
      <div className="flex gap-2">
        <input
          id="coupon-code"
          value={draft}
          onChange={(e) => setDraft(e.target.value.toUpperCase())}
          onKeyDown={(e) => { if (e.key === 'Enter' && canApply) { e.preventDefault(); onApply(trimmed) } }}
          placeholder="SAVE10"
          maxLength={32}
          autoComplete="off"
          spellCheck={false}
          className="h-11 min-w-0 flex-1 rounded-edge border border-ink-400 bg-paper px-3
                     text-[13px] uppercase tracking-[0.06em] text-chalk
                     placeholder:normal-case placeholder:tracking-normal placeholder:text-chalk-faint
                     focus-visible:outline focus-visible:outline-2
                     focus-visible:outline-offset-1 focus-visible:outline-brand-400"
        />
        <Button
          variant="secondary"
          onClick={() => canApply && onApply(trimmed)}
          disabled={!canApply}
        >
          {t.order.couponApply}
        </Button>
      </div>

      {/*
        One line, and only one. The server's reason wins over the generic hint, and the
        applied confirmation wins over both -- three stacked messages about a five
        character field is more explanation than the field is worth.
      */}
      <p className={`mt-1.5 text-[12px] leading-snug ${
        message ? 'text-brand-400' : isApplied ? 'text-ok' : 'text-chalk-faint'
      }`}>
        {message ?? (applied ? t.order.couponApplied(applied) : t.order.couponHint)}
      </p>
    </div>
  )
}
