import { useCallback, useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { CredentialForm } from './CredentialForm'
import { ManualPayment, ownerManualRoutes } from './ManualPayment'
import { ownerPayopRoutes } from './PayopPayment'
import { Alert, Button, ButtonLink } from './ui'
import { useI18n } from '../i18n'
import { ApiError, api } from '../lib/api'
import type { Order, OrderPaymentView } from '../lib/types'
import { ScheduleStep, formatSlot, type ChosenSlot } from '../pages/coaching/ScheduleStep'

/**
 * Completing the payment of an order that was placed and not paid.
 *
 * <p>The same order, at the price it was placed at, in its own currency: the amount here
 * is the order's frozen total, never re-quoted and never converted into the display
 * currency. The payment step is the checkout's own (UPI, PayPal, crypto, and Payop under
 * International for orders not in INR), reached through the signed-in owner's routes,
 * where every number comes from the server and no amount is sent back.
 *
 * <p>Before paying: a coaching order whose slot hold ran out has the slot checked again,
 * and the customer picks another time if it was taken; a coin order whose sign-in is gone
 * is asked for it again.
 */

/** Where a new order of the same kind is placed. */
export function newOrderPath(sku: string): string {
  if (sku === 'COACHING') return '/coaching/book'
  if (sku.startsWith('BOOST_')) return '/boosting'
  return '/order'
}

/**
 * A time in the customer's own zone, with the zone named. Spelt out field by field:
 * `dateStyle` and `timeStyle` cannot be combined with `timeZoneName`, and the browser
 * throws if they are.
 */
export function localTime(iso: string, lang: string): string {
  return new Date(iso).toLocaleString(lang, {
    day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit', timeZoneName: 'short',
  })
}

/** The customer has sent their payment details and nobody has checked them yet. */
export function PaymentSubmitted() {
  const { t } = useI18n()
  return (
    <Alert tone="ok" title={t.completePayment.submittedTitle}>
      <span data-testid="payment-submitted">{t.completePayment.submittedBody}</span>
    </Alert>
  )
}

/** Not paid in time and closed: the way forward is a new order. */
export function OrderExpired({ sku }: { sku: string }) {
  const { t } = useI18n()
  return (
    <div data-testid="order-expired" className="space-y-3">
      <Alert tone="warn" title={t.completePayment.expiredTitle}>{t.completePayment.expiredBody}</Alert>
      <ButtonLink to={newOrderPath(sku)} size="md">{t.completePayment.placeNew}</ButtonLink>
    </div>
  )
}

export function CompletePayment({
  order, signedIn, onChanged, startOpen = false,
}: {
  order: Order
  /** Paying is the signed-in owner's: anybody else is asked to sign in first. */
  signedIn: boolean
  /** The order as it is after something changed here, e.g. once payment details are in. */
  onChanged?: (order: Order) => void
  /** Open the payment step straight away, e.g. from "Complete your payment" in My Orders. */
  startOpen?: boolean
}) {
  const { t, lang } = useI18n()
  const c = t.completePayment
  const [params] = useSearchParams()
  const [open, setOpen] = useState(() => signedIn && (startOpen || params.get('pay') === '1'))

  useEffect(() => {
    if (signedIn && startOpen) setOpen(true)
  }, [signedIn, startOpen])

  return (
    <section data-testid="complete-payment"
             className="rounded-panel border border-brand-500/50 bg-brand-500/[0.06] p-5">
      <p className="text-[14px] leading-relaxed text-chalk">{c.banner}</p>
      <div className="mt-4 flex flex-wrap items-end justify-between gap-x-6 gap-y-2">
        <dl>
          <dt className="stamp text-[10.5px] text-chalk-faint">{c.amountDue}</dt>
          <dd className="tnum mt-1 text-[20px] font-semibold text-chalk" data-testid="amount-due">
            {order.totalFormatted}
          </dd>
        </dl>
        {order.payBy && (
          <p className="text-[12.5px] font-semibold text-chalk-muted" data-testid="pay-by">
            {c.payBy(localTime(order.payBy, lang))}
          </p>
        )}
      </div>

      {!open && (
        signedIn ? (
          <Button className="mt-4 w-full sm:w-auto" onClick={() => setOpen(true)}>{c.button}</Button>
        ) : (
          <ButtonLink to="/login" state={{ from: `/track?ref=${encodeURIComponent(order.publicRef)}&pay=1` }}
                      className="mt-4 w-full sm:w-auto">
            {c.signIn}
          </ButtonLink>
        )
      )}

      {open && <ResumePayment order={order} onChanged={onChanged} />}
    </section>
  )
}

function ResumePayment({ order, onChanged }: { order: Order; onChanged?: (order: Order) => void }) {
  const { t, lang } = useI18n()
  const c = t.completePayment
  const ref = order.publicRef
  const base = `/api/v1/orders/${encodeURIComponent(ref)}/payment`
  const manualRoutes = useMemo(() => ownerManualRoutes(ref), [ref])
  const payopRoutes = useMemo(() => ownerPayopRoutes(ref), [ref])

  const [view, setView] = useState<OrderPaymentView | null>(null)
  const [failed, setFailed] = useState(false)
  const [reload, setReload] = useState(0)
  /** The coaching slot: being checked again, to be picked anew, or settled. */
  const [slot, setSlot] = useState<'checking' | 'pick' | 'settled' | null>(null)
  const [picked, setPicked] = useState<ChosenSlot | null>(null)
  const [slotNotice, setSlotNotice] = useState<string | null>(null)
  const [slotRefresh, setSlotRefresh] = useState(0)

  /** The order page shows what the order is now, once something here has moved it. */
  const refreshOrder = useCallback(async () => {
    try {
      onChanged?.(await api.get<Order>(`/api/v1/orders/${encodeURIComponent(ref)}`))
    } catch {
      // The page keeps the order it has; the next visit shows the new state.
    }
  }, [ref, onChanged])

  useEffect(() => {
    let live = true
    setFailed(false)
    api.get<OrderPaymentView>(`${base}?lang=${encodeURIComponent(lang)}`)
      .then((found) => { if (live) setView(found) })
      .catch(() => { if (live) setFailed(true) })
    return () => { live = false }
  }, [base, lang, reload])

  useEffect(() => {
    if (view && view.paymentState !== 'UNPAID') void refreshOrder()
  }, [view, refreshOrder])

  // A slot hold that ran out: hold the same slot again if it is still free, once.
  useEffect(() => {
    if (view?.coaching?.state !== 'EXPIRED' || slot !== null) return
    setSlot('checking')
    api.post(`${base}/coaching-slot`, {})
      .then(() => { setSlot('settled'); setReload((n) => n + 1) })
      .catch((e) => {
        // Taken in the meantime (or no slot to check): a new time is picked first.
        setSlot('pick')
        setSlotNotice(e instanceof ApiError && (e.code === 'slot_unavailable' || e.code === 'slot_required')
          ? c.slotTaken : e instanceof ApiError ? e.message : c.loadFailed)
      })
  }, [view, slot, base, c.slotTaken, c.loadFailed])

  async function holdPicked() {
    if (!picked) return
    try {
      await api.post(`${base}/coaching-slot`,
        { coachId: picked.coachId, startsAt: picked.startsAt, timezone: picked.timezone })
      setSlot('settled')
      setPicked(null)
      setReload((n) => n + 1)
    } catch (e) {
      setSlotNotice(e instanceof ApiError && e.code === 'slot_unavailable' ? c.slotTaken
        : e instanceof ApiError ? e.message : c.loadFailed)
      setPicked(null)
      setSlotRefresh((n) => n + 1)
    }
  }

  if (failed) {
    return (
      <div className="mt-4">
        <Alert tone="warn">
          {c.loadFailed}{' '}
          <button type="button" onClick={() => setReload((n) => n + 1)}
                  className="font-semibold text-brand-400 hover:underline">
            {c.retry}
          </button>
        </Alert>
      </div>
    )
  }
  if (!view) {
    return <p className="mt-4 text-[13px] text-chalk-muted" aria-live="polite">{c.loading}</p>
  }
  if (view.paymentState !== 'UNPAID') return null

  if (view.coaching?.state === 'EXPIRED') {
    if (slot === 'pick') {
      return (
        <div className="mt-4" data-testid="slot-repick">
          <ScheduleStep
            variant={view.coaching.variant ?? order.variant ?? ''}
            sessionsInPack={1}
            value={picked}
            onChange={setPicked}
            onContinue={() => void holdPicked()}
            notice={slotNotice}
            refreshKey={slotRefresh}
          />
        </div>
      )
    }
    return <p className="mt-4 text-[13px] text-chalk-muted" aria-live="polite">{c.slotChecking}</p>
  }

  if (view.signInNeeded) {
    return (
      <div className="mt-4 space-y-3" data-testid="sign-in-again">
        <Alert tone="brand">{c.signInAgain}</Alert>
        <CredentialForm publicRef={ref} onSubmitted={() => setReload((n) => n + 1)} />
      </div>
    )
  }

  return (
    <div className="mt-4 space-y-3">
      {view.coaching?.state === 'HELD' && view.coaching.startsAt && (
        <p className="text-[13px] text-chalk-muted" data-testid="slot-held">
          {c.slotKept(formatSlot(view.coaching.startsAt))}
        </p>
      )}
      <ManualPayment
        publicRef={ref}
        sku={order.sku}
        totalFormatted={view.manual?.totalFormatted ?? view.amountDueFormatted}
        currency={view.currency}
        routes={manualRoutes}
        payopRoutes={payopRoutes}
        blocked={view.manualBlockedUntil
          ? { until: view.manualBlockedUntil, invoiceUrl: view.payableInvoice?.url ?? null }
          : null}
        onSubmitted={() => void refreshOrder()}
      />
    </div>
  )
}
