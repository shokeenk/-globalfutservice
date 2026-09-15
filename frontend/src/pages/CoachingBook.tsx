import { useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { CoachIcon, DiscordMark } from '../components/CoachingIcons'
import type { CoachIconName } from '../components/CoachingIcons'
import { ManualPayment } from '../components/ManualPayment'
import { PlatformIcon } from '../components/PlatformIcon'
import { Alert, Badge, Button, ButtonLink, Checkbox, Field, Input, Section, Select, Spinner, Textarea } from '../components/ui'
import { BUSINESS } from '../content/business'
import { useCatalogLabels } from '../content/catalogLabels'
import { useT } from '../i18n'
import { ApiError, api } from '../lib/api'
import { isStubGateway, openCheckout } from '../lib/razorpay'
import { useSeo } from '../lib/seo'
import type {
  CatalogOption, CreateOrderResponse, ManualPaymentMethod, ManualPaymentOption, Order, SignedQuote,
} from '../lib/types'
import { useAuth } from '../state/AuthContext'
import { useCatalog } from '../state/CatalogContext'

/**
 * Coaching, from choosing a package to joining Discord.
 *
 * <p>One page and a step machine rather than a route per step. The first three steps hold
 * nothing but choices, so losing them to a refresh costs a few clicks; once an order exists
 * its reference goes into the URL, and a refresh from there resumes at payment or at the
 * confirmation, whichever the order has reached.
 *
 * <p><b>It places a real order through the same checkout as coins and boosting.</b> Same
 * signed quote, same order endpoint, same payment rails: the gateway where one is configured,
 * and the scan-and-pay methods everywhere. The coaching details ride on that order.
 *
 * <p><b>Nothing here says "paid" before the money is found.</b> A gateway payment is
 * confirmed by the gateway, so the processing screen waits for the order to actually move.
 * A scan-and-pay payment is confirmed by a person checking the account, so the screens after
 * it say "submitted" and switch to "confirmed" on their own when that happens.
 */

type Step = 'option' | 'details' | 'review' | 'pay' | 'processing' | 'success' | 'discord' | 'done'
type PayChoice = 'ONLINE' | ManualPaymentMethod
type Variant = 'SINGLE_SESSION' | 'MONTHLY_6_SESSIONS'
type CoachingPlatform = 'PLAYSTATION' | 'XBOX' | 'PC'

const VARIANTS: Variant[] = ['SINGLE_SESSION', 'MONTHLY_6_SESSIONS']
const PLATFORMS: CoachingPlatform[] = ['PLAYSTATION', 'XBOX', 'PC']

/**
 * Division Rivals, top to bottom of the ladder. Stored in English whatever the page
 * language, because the value is for the coach reading the order, not for the customer.
 */
const RANKS: { value: string; division: number | 'ELITE' | 'NOT_SURE' }[] = [
  { value: 'Elite Division', division: 'ELITE' },
  ...Array.from({ length: 10 }, (_, i) => ({ value: `Division ${i + 1}`, division: i + 1 })),
  { value: 'Not sure', division: 'NOT_SURE' },
]

/** States from which the money has been found. */
function isPaid(order: Order | null): boolean {
  return !!order && !['DRAFT', 'AWAITING_PAYMENT', 'ABANDONED'].includes(order.status)
}

export default function CoachingBook() {
  const t = useT()
  const b = t.coachingBook
  useSeo({ title: b.seoTitle, noindex: true })

  const { account, loading: authLoading } = useAuth()
  const { catalog, policy } = useCatalog()
  const navigate = useNavigate()
  const [params] = useSearchParams()

  const options = useMemo(
    () => catalog?.services.find((s) => s.sku === 'COACHING')?.options ?? [],
    [catalog],
  )

  const [variant, setVariant] = useState<Variant>(() =>
    (VARIANTS as string[]).includes(params.get('variant') ?? '')
      ? (params.get('variant') as Variant) : 'SINGLE_SESSION')
  const [step, setStep] = useState<Step>('option')

  const [handle, setHandle] = useState('')
  const [platform, setPlatform] = useState<CoachingPlatform | null>(null)
  const [rank, setRank] = useState('')
  const [focus, setFocus] = useState('')
  const [detailsTouched, setDetailsTouched] = useState(false)

  const [methods, setMethods] = useState<ManualPaymentOption[] | null>(null)
  const [payChoice, setPayChoice] = useState<PayChoice | null>(null)
  const [acceptedTerms, setAcceptedTerms] = useState(false)
  const [reviewTouched, setReviewTouched] = useState(false)
  const [placing, setPlacing] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const [quote, setQuote] = useState<SignedQuote | null>(null)
  const [created, setCreated] = useState<CreateOrderResponse | null>(null)
  const [resumed, setResumed] = useState<Order | null>(null)
  const orderRef = created?.publicRef ?? resumed?.publicRef ?? null

  const selected = options.find((o) => o.variant === variant) ?? null

  /*
   * Where to start. A signed-in customer returning from the sign-in page lands on details,
   * which is the step the account gate sent them from. A URL carrying an order reference
   * resumes that order. Read once: after this, the step machine owns the page.
   */
  const started = useRef(false)
  useEffect(() => {
    if (started.current || authLoading) return
    started.current = true
    const ref = params.get('order')
    if (ref && account) {
      api.get<Order>(`/api/v1/orders/${encodeURIComponent(ref)}`)
        .then((found) => {
          setResumed(found)
          setStep(isPaid(found) ? 'success' : 'pay')
        })
        .catch(() => setError(b.loadOrderFailed))
    } else if (params.get('step') === 'details' && account) {
      setStep('details')
    }
  }, [authLoading, account, params, b.loadOrderFailed])

  // The ways to pay that actually exist for coaching on this install.
  useEffect(() => {
    let live = true
    api.get<ManualPaymentOption[]>('/api/v1/payments/methods?sku=COACHING')
      .then((found) => { if (live) setMethods(found) })
      .catch(() => { if (live) setMethods([]) })
    return () => { live = false }
  }, [])

  const payChoices: { key: PayChoice; title: string; body: string; icon: CoachIconName }[] = useMemo(() => {
    const list: { key: PayChoice; title: string; body: string; icon: CoachIconName }[] = []
    if (policy?.onlinePaymentsEnabled) {
      list.push({ key: 'ONLINE', title: b.payOnlineTitle, body: b.payOnlineBody, icon: 'card' })
    }
    for (const m of methods ?? []) {
      if (m.method === 'UPI') list.push({ key: 'UPI', title: b.payUpiTitle, body: b.payUpiBody, icon: 'qr' })
      if (m.method === 'PAYPAL') list.push({ key: 'PAYPAL', title: b.payPaypalTitle, body: b.payPaypalBody, icon: 'globe' })
      if (m.method === 'CRYPTO') list.push({ key: 'CRYPTO', title: b.payCryptoTitle, body: b.payCryptoBody, icon: 'lock' })
    }
    return list
  }, [policy, methods, b])

  useEffect(() => {
    const first = payChoices[0]
    if (!payChoice && first) setPayChoice(first.key)
  }, [payChoice, payChoices])

  const handleMissing = handle.trim().length === 0
  const platformMissing = platform === null

  function goToDetails() {
    setError(null)
    setStep('details')
    window.scrollTo({ top: 0 })
  }

  /*
   * The price comes from the engine, not from the card. Coaching carries the same
   * payment-processing line as every other order, so the total a customer is about to
   * pay is not the package price, and the review screen shows the quote's own lines.
   */
  async function fetchQuote(): Promise<SignedQuote> {
    const fresh = await api.post<SignedQuote>('/api/v1/quotes', {
      sku: 'COACHING',
      platform: null,
      variant,
      quantity: '1',
      currency: catalog?.currency,
      couponCode: null,
      pointsToRedeem: 0,
    })
    setQuote(fresh)
    return fresh
  }

  function goToReview() {
    setDetailsTouched(true)
    if (handleMissing || platformMissing) return
    setError(null)
    setQuote(null)
    setStep('review')
    window.scrollTo({ top: 0 })
    fetchQuote().catch((e) => setError(e instanceof ApiError ? e.message : b.placeFailed))
  }

  async function proceedToPayment(order: CreateOrderResponse) {
    if (payChoice === 'ONLINE' && !isStubGateway(order.payment)) {
      await openCheckout(order.payment, order.publicRef, {
        onDismiss: () => setPlacing(false),
        onSuccess: () => { setStep('processing'); window.scrollTo({ top: 0 }) },
      })
      return
    }
    setPlacing(false)
    setStep('pay')
    window.scrollTo({ top: 0 })
  }

  async function placeOrder() {
    setReviewTouched(true)
    if (!acceptedTerms || !payChoice || !selected || !catalog || !account || placing) return
    setPlacing(true)
    setError(null)
    try {
      // One order per checkout. Coming back to this screen and pressing again pays for the
      // order that already exists rather than placing a second one.
      if (created) {
        await proceedToPayment(created)
        return
      }
      // Re-priced if the one on screen is about to expire, so the order is never refused
      // for a stale quote the customer had no way to see go stale.
      const priced = quote && new Date(quote.expiresAt).getTime() - Date.now() > 30_000
        ? quote : await fetchQuote()
      const order = await api.post<CreateOrderResponse>('/api/v1/orders', {
        quote: priced,
        email: account.email,
        fullName: account.displayName ?? undefined,
        acceptedTerms: true,
        eaPlatformHandle: handle.trim(),
        coachingPlatform: platform,
        currentRank: rank || null,
        improvementFocus: focus.trim() || null,
      })
      setCreated(order)
      // A refresh from here resumes this order instead of starting another.
      navigate(`/coaching/book?order=${encodeURIComponent(order.publicRef)}`, { replace: true })
      await proceedToPayment(order)
    } catch (e) {
      setPlacing(false)
      setError(e instanceof ApiError ? e.message : b.placeFailed)
    }
  }

  const indicatorStep = step === 'option' ? 1 : step === 'details' ? 2 : 3
  const showIndicator = step === 'option' || step === 'details' || step === 'review' || step === 'pay'

  return (
    <Section className="rhythm-section">
      <div className="mx-auto max-w-4xl">
        {showIndicator && (
          <div className="mb-8 flex items-center justify-between gap-4">
            {step === 'details' || step === 'review' ? (
              <button
                type="button"
                onClick={() => setStep(step === 'review' ? 'details' : 'option')}
                className="inline-flex items-center gap-1.5 text-body-sm font-semibold text-chalk-muted hover:text-chalk"
              >
                <span aria-hidden="true">&larr;</span> {b.back}
              </button>
            ) : (
              <Link to="/coaching" className="inline-flex items-center gap-1.5 text-body-sm font-semibold text-chalk-muted hover:text-chalk">
                <span aria-hidden="true">&larr;</span> {b.back}
              </Link>
            )}
            <StepIndicator current={indicatorStep} labels={[b.stepService, b.stepDetails, b.stepPayment]} />
          </div>
        )}

        {error && <div className="mb-6"><Alert tone="warn">{error}</Alert></div>}

        {step === 'option' && (
          <OptionStep
            options={options}
            currency={catalog?.currency ?? 'INR'}
            variant={variant}
            onVariant={setVariant}
            onContinue={goToDetails}
            signedIn={!!account}
          />
        )}

        {step === 'details' && (
          <DetailsStep
            handle={handle} onHandle={setHandle}
            platform={platform} onPlatform={setPlatform}
            rank={rank} onRank={setRank}
            focus={focus} onFocus={setFocus}
            touched={detailsTouched}
            onContinue={goToReview}
          />
        )}

        {step === 'review' && selected && (
          <ReviewStep
            option={selected}
            quote={quote}
            platform={platform}
            handle={handle}
            rank={rank}
            focus={focus}
            choices={payChoices}
            choice={payChoice}
            onChoice={setPayChoice}
            acceptedTerms={acceptedTerms}
            onAcceptedTerms={setAcceptedTerms}
            touched={reviewTouched}
            placing={placing}
            onPay={() => void placeOrder()}
          />
        )}

        {step === 'pay' && orderRef && account && (
          <div className="mx-auto max-w-2xl">
            <ManualPayment
              publicRef={orderRef}
              email={account.email}
              sku="COACHING"
              totalFormatted={created?.totalFormatted ?? resumed?.totalFormatted ?? ''}
              initialMethod={payChoice && payChoice !== 'ONLINE' ? payChoice : undefined}
              onSubmitted={() => { setStep('success'); window.scrollTo({ top: 0 }) }}
            />
          </div>
        )}

        {step === 'processing' && orderRef && (
          <ProcessingStep orderRef={orderRef} onConfirmed={() => setStep('success')} />
        )}

        {(step === 'success' || step === 'discord' || step === 'done') && orderRef && (
          <ConfirmedSteps
            step={step}
            orderRef={orderRef}
            email={account?.email ?? ''}
            emailsEnabled={!!policy?.customerEmailsEnabled}
            onStep={(next) => { setStep(next); window.scrollTo({ top: 0 }) }}
          />
        )}
      </div>
    </Section>
  )
}

/* ------------------------------------------------------------------ indicator --- */

function StepIndicator({ current, labels }: { current: number; labels: string[] }) {
  return (
    <ol className="flex items-center gap-2 sm:gap-3" aria-label="Progress">
      {labels.map((label, i) => {
        const n = i + 1
        const done = n < current
        const active = n === current
        return (
          <li key={label} className="flex items-center gap-2 sm:gap-3">
            <span className="flex flex-col items-center gap-1">
              <span
                aria-current={active ? 'step' : undefined}
                className={[
                  'grid h-7 w-7 place-items-center rounded-full text-[12px] font-bold',
                  active || done ? 'bg-brand-500 text-paper' : 'bg-ink-600 text-chalk-faint',
                ].join(' ')}
              >
                {done ? <CoachIcon name="check" className="h-3.5 w-3.5" strokeWidth={3} /> : n}
              </span>
              <span className={`text-[11px] font-semibold ${active ? 'text-chalk' : 'text-chalk-faint'}`}>
                {label}
              </span>
            </span>
            {n < labels.length && <span aria-hidden="true" className="mb-5 h-px w-6 bg-ink-400 sm:w-12" />}
          </li>
        )
      })}
    </ol>
  )
}

/* ---------------------------------------------------------------------- step 2 --- */

function OptionStep({
  options, currency, variant, onVariant, onContinue, signedIn,
}: {
  options: CatalogOption[]
  currency: string
  variant: Variant
  onVariant: (v: Variant) => void
  onContinue: () => void
  signedIn: boolean
}) {
  const t = useT()
  const b = t.coachingBook
  const labels = useCatalogLabels()
  const pack = options.find((o) => o.variant === 'MONTHLY_6_SESSIONS')
  // Same derivation as the coaching page: six 40-minute sessions at their own rate.
  const listMinor = pack ? Math.round(pack.unitPriceMinor / 0.9) : 0
  const savingPercent = pack && listMinor > pack.unitPriceMinor
    ? Math.round(((listMinor - pack.unitPriceMinor) / listMinor) * 100) : 0

  return (
    <div className="mx-auto max-w-2xl">
      <h1 className="display text-display-md text-chalk">{b.chooseTitle}</h1>
      <p className="mt-2 text-body-sm text-chalk-muted">{b.chooseLead}</p>

      <div role="radiogroup" aria-label={b.chooseTitle} className="mt-6 grid gap-4 sm:grid-cols-2">
        {options.length === 0 && <Spinner />}
        {options.map((option) => {
          const isPack = option.variant === 'MONTHLY_6_SESSIONS'
          const checked = option.variant === variant
          return (
            <button
              key={option.variant}
              type="button"
              role="radio"
              aria-checked={checked}
              onClick={() => onVariant(option.variant as Variant)}
              className={[
                'relative flex flex-col rounded-panel border-2 bg-paper p-5 text-left transition-colors duration-200',
                checked ? 'border-brand-500 shadow-e2' : 'border-ink-400 hover:border-ink-300',
              ].join(' ')}
            >
              <span className="flex items-start justify-between gap-3">
                <span className="text-body-sm font-semibold text-chalk">{labels.option(option)}</span>
                <span
                  aria-hidden="true"
                  className={[
                    'grid h-5 w-5 shrink-0 place-items-center rounded-full border-2',
                    checked ? 'border-brand-500 bg-brand-500 text-paper' : 'border-ink-300',
                  ].join(' ')}
                >
                  {checked && <CoachIcon name="check" className="h-3 w-3" strokeWidth={3.5} />}
                </span>
              </span>
              {isPack && savingPercent > 0 && (
                <span className="tnum mt-2 text-body-sm text-chalk-faint line-through">
                  {new Intl.NumberFormat(undefined, {
                    style: 'currency', currency, maximumFractionDigits: 0,
                  }).format(listMinor / 100)}
                </span>
              )}
              <span className={`tnum display ${isPack && savingPercent > 0 ? '' : 'mt-2'} text-[1.9rem] leading-tight text-chalk`}>
                {option.unitPriceFormatted}
              </span>
              <ul className="mt-4 space-y-2">
                {(isPack ? b.packBullets : b.singleBullets).map((line) => (
                  <li key={line} className="flex items-center gap-2 text-[13px] text-chalk-muted">
                    <CoachIcon name="check" className="h-4 w-4 shrink-0 text-ok" strokeWidth={2.4} />
                    {line}
                  </li>
                ))}
                {isPack && savingPercent > 0 && (
                  <li className="flex items-center gap-2 text-[13px] text-chalk-muted">
                    <CoachIcon name="check" className="h-4 w-4 shrink-0 text-ok" strokeWidth={2.4} />
                    {b.saveBadge(savingPercent)}
                  </li>
                )}
              </ul>
            </button>
          )
        })}
      </div>

      {signedIn ? (
        <Button full size="lg" className="mt-6" onClick={onContinue}>
          {b.continue} <CoachIcon name="arrowRight" className="ml-1.5 h-4 w-4" />
        </Button>
      ) : (
        /*
         * The account gate, between choosing and typing. Placed here rather than at payment
         * because the next step holds typed fields a sign-in round trip would throw away,
         * and the choice made above survives in the return address.
         */
        <div className="mt-6 space-y-2">
          <ButtonLink
            to="/login"
            full
            size="lg"
            state={{ from: `/coaching/book?variant=${variant}&step=details` }}
          >
            {b.signInToContinue}
          </ButtonLink>
          <p className="text-center text-[12.5px] text-chalk-faint">{b.signInWhy}</p>
        </div>
      )}

      <div className="mt-6 flex gap-3 rounded-panel border border-ink-400 bg-ink-700/40 p-4">
        <span className="grid h-9 w-9 shrink-0 place-items-center rounded-full bg-brand-500/10 text-brand-500">
          <CoachIcon name="bulb" className="h-5 w-5" />
        </span>
        <div>
          <p className="text-body-sm font-semibold text-chalk">{b.unsureTitle}</p>
          <p className="mt-1 text-[13px] leading-relaxed text-chalk-muted">{b.unsureBody}</p>
        </div>
      </div>
    </div>
  )
}

/* ---------------------------------------------------------------------- step 3 --- */

function DetailsStep({
  handle, onHandle, platform, onPlatform, rank, onRank, focus, onFocus, touched, onContinue,
}: {
  handle: string
  onHandle: (v: string) => void
  platform: CoachingPlatform | null
  onPlatform: (p: CoachingPlatform) => void
  rank: string
  onRank: (v: string) => void
  focus: string
  onFocus: (v: string) => void
  touched: boolean
  onContinue: () => void
}) {
  const t = useT()
  const b = t.coachingBook
  const platformLabel: Record<CoachingPlatform, string> = {
    PLAYSTATION: b.platformPlayStation,
    XBOX: b.platformXbox,
    PC: b.platformPc,
  }

  return (
    <div className="mx-auto max-w-2xl">
      <h1 className="display text-display-md text-chalk">{b.detailsTitle}</h1>
      <p className="mt-2 text-body-sm text-chalk-muted">{b.detailsLead}</p>

      <div className="mt-6 space-y-5 rounded-panel border border-ink-400 bg-paper p-5 shadow-e1 sm:p-6">
        <Field
          label={b.handleLabel}
          required
          error={touched && handle.trim().length === 0 ? b.handleRequired : undefined}
        >
          {(props) => (
            <Input
              {...props}
              value={handle}
              maxLength={64}
              autoComplete="off"
              spellCheck={false}
              placeholder={b.handlePlaceholder}
              onChange={(e) => onHandle(e.target.value)}
            />
          )}
        </Field>

        <fieldset>
          <legend className="block text-[13px] font-medium text-chalk-muted">
            {b.platformLabel}<span className="ml-1 text-brand-400">*</span>
          </legend>
          <div role="radiogroup" aria-label={b.platformLabel} className="mt-2 grid grid-cols-3 gap-2">
            {PLATFORMS.map((p) => {
              const checked = platform === p
              return (
                <button
                  key={p}
                  type="button"
                  role="radio"
                  aria-checked={checked}
                  onClick={() => onPlatform(p)}
                  className={[
                    'flex min-h-[76px] flex-col items-center justify-center gap-1.5 rounded-edge border-2 px-2 py-3',
                    'text-[12.5px] font-semibold transition-colors duration-200',
                    checked
                      ? 'border-brand-500 bg-brand-500/[0.06] text-chalk'
                      : 'border-ink-400 text-chalk-muted hover:border-ink-300 hover:text-chalk',
                  ].join(' ')}
                >
                  <PlatformIcon platform={p} className="h-7 w-7" />
                  {platformLabel[p]}
                </button>
              )
            })}
          </div>
          {touched && platform === null && (
            <p role="alert" className="mt-2 text-[12px] text-warn">{b.platformRequired}</p>
          )}
        </fieldset>

        <Field label={`${b.rankLabel} ${b.optional}`}>
          {(props) => (
            <Select {...props} value={rank} onChange={(e) => onRank(e.target.value)}>
              <option value="">{b.rankPlaceholder}</option>
              {RANKS.map((r) => (
                <option key={r.value} value={r.value}>
                  {r.division === 'ELITE' ? b.rankElite
                    : r.division === 'NOT_SURE' ? b.rankNotSure
                      : b.rankDivision(r.division)}
                </option>
              ))}
            </Select>
          )}
        </Field>

        <Field label={`${b.focusLabel} ${b.optional}`}>
          {(props) => (
            <Textarea
              {...props}
              rows={4}
              value={focus}
              maxLength={500}
              placeholder={b.focusPlaceholder}
              onChange={(e) => onFocus(e.target.value)}
            />
          )}
        </Field>
      </div>

      <Button full size="lg" className="mt-6" onClick={onContinue}>
        {b.continue} <CoachIcon name="arrowRight" className="ml-1.5 h-4 w-4" />
      </Button>
    </div>
  )
}

/* ---------------------------------------------------------------------- step 4 --- */

function ReviewStep({
  option, quote, platform, handle, rank, focus, choices, choice, onChoice,
  acceptedTerms, onAcceptedTerms, touched, placing, onPay,
}: {
  option: CatalogOption
  quote: SignedQuote | null
  platform: CoachingPlatform | null
  handle: string
  rank: string
  focus: string
  choices: { key: PayChoice; title: string; body: string; icon: CoachIconName }[]
  choice: PayChoice | null
  onChoice: (c: PayChoice) => void
  acceptedTerms: boolean
  onAcceptedTerms: (v: boolean) => void
  touched: boolean
  placing: boolean
  onPay: () => void
}) {
  const t = useT()
  const b = t.coachingBook
  const labels = useCatalogLabels()
  const platformLabel = platform === 'PLAYSTATION' ? b.platformPlayStation
    : platform === 'XBOX' ? b.platformXbox : platform === 'PC' ? b.platformPc : '—'

  return (
    <div>
      <h1 className="display text-display-md text-chalk">{b.reviewTitle}</h1>

      <div className="mt-6 grid gap-5 lg:grid-cols-[1.4fr_1fr] lg:items-start">
        <div className="space-y-5">
          <div className="rounded-panel border border-ink-400 bg-paper p-5 shadow-e1">
            <p className="text-[12.5px] font-semibold text-chalk-faint">{b.coachingSession}</p>
            <div className="mt-1 flex items-baseline justify-between gap-4">
              <p className="text-body-sm font-semibold text-chalk">{labels.option(option)}</p>
              <p className="tnum text-body-sm font-semibold text-chalk">{option.unitPriceFormatted}</p>
            </div>
            <dl className="mt-4 space-y-2 border-t border-ink-400 pt-4 text-[13px]">
              <SummaryRow label={b.summaryPlatform}>
                <span className="inline-flex items-center gap-1.5">
                  {platform && <PlatformIcon platform={platform} className="h-4 w-4" />}{platformLabel}
                </span>
              </SummaryRow>
              <SummaryRow label={b.summaryHandle}>{handle.trim()}</SummaryRow>
              {rank && <SummaryRow label={b.summaryRank}>{rank}</SummaryRow>}
              {focus.trim() && <SummaryRow label={b.summaryFocus}>{focus.trim()}</SummaryRow>}
            </dl>
          </div>

          <div>
            <p className="text-body-sm font-semibold text-chalk">{b.paymentMethod}</p>
            <div role="radiogroup" aria-label={b.paymentMethod} className="mt-3 space-y-2">
              {choices.length === 0 && <Spinner />}
              {choices.map((c, i) => {
                const checked = choice === c.key
                return (
                  <button
                    key={c.key}
                    type="button"
                    role="radio"
                    aria-checked={checked}
                    onClick={() => onChoice(c.key)}
                    className={[
                      'flex w-full items-center gap-3 rounded-edge border-2 bg-paper px-4 py-3 text-left transition-colors duration-200',
                      checked ? 'border-brand-500' : 'border-ink-400 hover:border-ink-300',
                    ].join(' ')}
                  >
                    <span
                      aria-hidden="true"
                      className={[
                        'grid h-5 w-5 shrink-0 place-items-center rounded-full border-2',
                        checked ? 'border-brand-500 bg-brand-500 text-paper' : 'border-ink-300',
                      ].join(' ')}
                    >
                      {checked && <CoachIcon name="check" className="h-3 w-3" strokeWidth={3.5} />}
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="flex flex-wrap items-center gap-2 text-body-sm font-semibold text-chalk">
                        {c.title}
                        {/* The first listed is the recommended one: online when a gateway exists, UPI otherwise. */}
                        {i === 0 && <Badge tone="gold">{b.recommended}</Badge>}
                      </span>
                      <span className="block text-[12.5px] text-chalk-faint">{c.body}</span>
                    </span>
                    <CoachIcon name={c.icon} className="h-6 w-6 shrink-0 text-chalk-muted" />
                  </button>
                )
              })}
            </div>
          </div>
        </div>

        <div className="space-y-5">
          <div className="rounded-panel border border-ink-400 bg-paper p-5 shadow-e2">
            <p className="text-body-sm font-semibold text-chalk">{b.orderSummary}</p>
            {!quote ? (
              <div className="mt-4 flex justify-center"><Spinner /></div>
            ) : (
              <>
                <ul className="mt-4 space-y-2">
                  {quote.lines.filter((line) => line.amountMinor !== 0).map((line) => (
                    <li key={line.code} className="flex justify-between gap-4 text-[13px] text-chalk-muted">
                      <span>{line.label}</span>
                      <span className="tnum shrink-0">{line.amountFormatted}</span>
                    </li>
                  ))}
                </ul>
                <div className="mt-3 flex items-baseline justify-between border-t border-ink-400 pt-3">
                  <span className="text-body-sm font-semibold text-chalk">{b.total}</span>
                  <span className="tnum display text-[1.4rem] text-chalk">{quote.totalFormatted}</span>
                </div>
              </>
            )}

            <div className="mt-5">
              <Checkbox
                checked={acceptedTerms}
                onChange={onAcceptedTerms}
                error={touched && !acceptedTerms ? b.acceptRequired : undefined}
              >
                {b.acceptPrefix}{' '}
                <Link to="/terms" className="text-brand-400 hover:underline">{b.terms}</Link>{' '}
                {b.and}{' '}
                <Link to="/privacy" className="text-brand-400 hover:underline">{b.privacy}</Link>.
              </Checkbox>
            </div>

            <Button full size="lg" className="mt-5" loading={placing} disabled={!quote} onClick={onPay}>
              <CoachIcon name="lock" className="mr-1.5 h-4 w-4" /> {b.payNow}
            </Button>
            <p className="mt-2 flex items-center justify-center gap-1.5 text-center text-[11.5px] text-chalk-faint">
              <CoachIcon name="lock" className="h-3 w-3" />
              {choice === 'ONLINE' ? b.payCaptionOnline : b.payCaptionManual}
            </p>
          </div>

          <div className="rounded-panel border border-ink-400 bg-paper p-5">
            <p className="flex items-center gap-2 text-body-sm font-semibold text-chalk">
              <CoachIcon name="shieldCheck" className="h-5 w-5 text-chalk-muted" /> {b.trustTitle}
            </p>
            <ul className="mt-3 space-y-2">
              {[b.trustEncrypted, b.trustPurpose, b.trustPayments].map((line) => (
                <li key={line} className="flex items-start gap-2 text-[12.5px] text-chalk-muted">
                  <CoachIcon name="check" className="mt-0.5 h-4 w-4 shrink-0 text-ok" strokeWidth={2.4} />
                  {line}
                </li>
              ))}
            </ul>
          </div>
        </div>
      </div>
    </div>
  )
}

function SummaryRow({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex items-start justify-between gap-4">
      <dt className="text-chalk-faint">{label}</dt>
      <dd className="min-w-0 text-right text-chalk" style={{ overflowWrap: 'anywhere' }}>{children}</dd>
    </div>
  )
}

/* ---------------------------------------------------------------------- step 5 --- */

/**
 * Waiting on a gateway payment to land.
 *
 * <p>The three stages are tied to what is actually happening: the first is done the moment
 * the payment window reported success, the second lasts while the order has not moved, and
 * the third only lights when it has. A timer would have them finish on schedule whether or
 * not the money arrived.
 */
function ProcessingStep({ orderRef, onConfirmed }: { orderRef: string; onConfirmed: () => void }) {
  const t = useT()
  const b = t.coachingBook
  const [stage, setStage] = useState(1)
  const [slow, setSlow] = useState(false)

  useEffect(() => {
    let stop = false
    let timer: number | undefined
    const began = Date.now()
    const tick = async () => {
      try {
        const order = await api.get<Order>(`/api/v1/orders/${encodeURIComponent(orderRef)}`)
        if (stop) return
        if (isPaid(order)) {
          setStage(2)
          timer = window.setTimeout(onConfirmed, 900)
          return
        }
      } catch {
        /* transient; keep waiting */
      }
      if (Date.now() - began > 60_000) setSlow(true)
      if (!stop) timer = window.setTimeout(tick, 2500)
    }
    void tick()
    return () => { stop = true; if (timer) window.clearTimeout(timer) }
  }, [orderRef, onConfirmed])

  const stages: { label: string; icon: CoachIconName }[] = [
    { label: b.stageSecuring, icon: 'lock' },
    { label: b.stageVerifying, icon: 'check' },
    { label: b.stageFinalising, icon: 'clock' },
  ]

  return (
    <div className="mx-auto max-w-xl rounded-panel border border-ink-400 bg-paper p-8 text-center shadow-e2">
      <div className="mx-auto grid h-20 w-20 place-items-center"><Spinner size={64} /></div>
      <h1 className="display mt-6 text-display-md text-chalk">{b.processingTitle}</h1>
      <p className="mt-2 text-body-sm text-chalk-muted">{b.processingBody}</p>

      <ol className="mt-8 grid grid-cols-3 gap-2">
        {stages.map((s, i) => {
          const done = i < stage || (i === 2 && stage === 2)
          const active = i === stage && stage < 2
          return (
            <li key={s.label} className="flex flex-col items-center gap-2">
              <span
                className={[
                  'grid h-10 w-10 place-items-center rounded-full border-2',
                  done ? 'border-ok bg-ok text-paper'
                    : active ? 'border-brand-500 text-brand-500' : 'border-ink-400 text-chalk-faint',
                ].join(' ')}
              >
                <CoachIcon name={done ? 'check' : s.icon} className="h-4 w-4" strokeWidth={2.4} />
              </span>
              <span className="text-[12px] font-semibold text-chalk-muted">{s.label}</span>
            </li>
          )
        })}
      </ol>

      <p className="mt-8 rounded-edge bg-ink-700/40 px-4 py-3 text-[12.5px] text-chalk-muted">
        {slow ? b.processingSlow : b.doNotClose}
      </p>
    </div>
  )
}

/* ------------------------------------------------------------------ steps 6–8 --- */

/**
 * The confirmation, Discord and "all set" screens, sharing one live view of the order.
 *
 * <p>Polled while the payment is unconfirmed and left alone once it is, so a customer who
 * paid by scan-and-pay and stays on this page watches "submitted" become "confirmed"
 * without refreshing.
 */
function ConfirmedSteps({
  step, orderRef, email, emailsEnabled, onStep,
}: {
  step: 'success' | 'discord' | 'done'
  orderRef: string
  email: string
  emailsEnabled: boolean
  onStep: (next: 'success' | 'discord' | 'done') => void
}) {
  const t = useT()
  const b = t.coachingBook
  const [order, setOrder] = useState<Order | null>(null)

  useEffect(() => {
    let stop = false
    let timer: number | undefined
    const tick = async () => {
      try {
        const found = await api.get<Order>(`/api/v1/orders/${encodeURIComponent(orderRef)}`)
        if (stop) return
        setOrder(found)
        if (isPaid(found)) return
      } catch {
        /* keep the last good view */
      }
      if (!stop) timer = window.setTimeout(tick, 15_000)
    }
    void tick()
    return () => { stop = true; if (timer) window.clearTimeout(timer) }
  }, [orderRef])

  const paid = isPaid(order)

  if (!order) {
    return <div className="grid min-h-[40vh] place-items-center"><Spinner size={32} /></div>
  }

  if (step === 'success') {
    const platformLabel = order.coachingPlatform === 'PLAYSTATION' ? b.platformPlayStation
      : order.coachingPlatform === 'XBOX' ? b.platformXbox
        : order.coachingPlatform === 'PC' ? b.platformPc : '—'
    return (
      <div className="mx-auto max-w-xl rounded-panel border border-ink-400 bg-paper p-6 text-center shadow-e2 sm:p-8">
        <span
          className={`mx-auto grid h-16 w-16 place-items-center rounded-full ${paid ? 'bg-ok' : 'bg-gold-500'} text-paper`}
        >
          <CoachIcon name={paid ? 'check' : 'clock'} className="h-8 w-8" strokeWidth={2.6} />
        </span>
        <h1 className="display mt-5 text-display-md text-chalk">{paid ? b.successTitle : b.submittedTitle}</h1>
        <p className="mt-2 text-body-sm text-chalk-muted">{paid ? b.successBody : b.submittedBody}</p>

        <dl className="mt-6 space-y-2.5 rounded-edge border border-ink-400 p-4 text-left text-[13px]">
          <SummaryRow label={b.orderNumber}><span className="font-semibold">#{order.publicRef}</span></SummaryRow>
          <SummaryRow label={b.service}>{order.serviceLabel}</SummaryRow>
          <SummaryRow label={b.amount}><span className="tnum">{order.totalFormatted}</span></SummaryRow>
          <SummaryRow label={b.summaryPlatform}>{platformLabel}</SummaryRow>
          {order.eaPlatformHandle && <SummaryRow label={b.summaryHandle}>{order.eaPlatformHandle}</SummaryRow>}
          <SummaryRow label={b.date}>
            {new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(order.createdAt))}
          </SummaryRow>
          <SummaryRow label={b.status}>
            <Badge tone={paid ? 'ok' : 'gold'}>{paid ? b.statusConfirmed : b.statusVerifying}</Badge>
          </SummaryRow>
        </dl>

        <Button full size="lg" className="mt-6" onClick={() => onStep('discord')}>
          {b.continue} <CoachIcon name="arrowRight" className="ml-1.5 h-4 w-4" />
        </Button>
        {emailsEnabled && email && (
          <p className="mt-3 flex items-center justify-center gap-1.5 text-[12px] text-chalk-faint">
            <CoachIcon name="lock" className="h-3 w-3" />
            {paid ? b.emailSent(email) : b.emailWhenConfirmed(email)}
          </p>
        )}
      </div>
    )
  }

  if (step === 'discord') {
    return (
      <div className="mx-auto max-w-xl rounded-panel border border-ink-400 bg-paper p-6 text-center shadow-e2 sm:p-8">
        <span className="mx-auto grid h-16 w-16 place-items-center rounded-2xl bg-[#5865F2] text-white">
          <DiscordMark className="h-9 w-9" />
        </span>
        <h1 className="display mt-5 text-display-md text-chalk">{b.discordTitle}</h1>
        <p className="mt-2 text-body-sm text-chalk-muted">{b.discordBody}</p>

        <ul className="mt-6 space-y-2.5 text-left">
          {b.discordSteps.map((line) => (
            <li key={line} className="flex items-start gap-2.5 text-[13px] text-chalk-muted">
              <CoachIcon name="checkCircle" className="h-5 w-5 shrink-0 text-ok" />
              {line}
            </li>
          ))}
        </ul>

        <a
          href={BUSINESS.discordInvite}
          target="_blank"
          rel="noopener noreferrer"
          className="mt-7 inline-flex h-12 w-full items-center justify-center gap-2 rounded-press bg-[#5865F2]
                     text-body-sm font-semibold text-white shadow-e2 transition-colors duration-200
                     hover:bg-[#4752C4] focus-visible:outline focus-visible:outline-2
                     focus-visible:outline-offset-2 focus-visible:outline-[#5865F2]"
        >
          <DiscordMark className="h-5 w-5" /> {b.joinDiscord}
          <span aria-hidden="true">&#8599;</span>
        </a>
        {emailsEnabled && (
          <p className="mt-3 text-[12px] text-chalk-faint">{paid ? b.discordEmailed : b.discordEmailLater}</p>
        )}

        <Button variant="secondary" full className="mt-5" onClick={() => onStep('done')}>
          {b.continue}
        </Button>
      </div>
    )
  }

  return (
    <div className="mx-auto grid max-w-3xl overflow-hidden rounded-panel border border-ink-400 bg-paper shadow-e2 md:grid-cols-[1.2fr_1fr]">
      <div className="p-6 sm:p-8">
        <h1 className="display text-display-md text-chalk">{b.allSetTitle}</h1>
        <p className="mt-1 text-body-sm text-chalk-muted">{b.allSetLead}</p>
        <ul className="mt-6 space-y-3">
          <AllSetRow done={paid} label={paid ? b.allSetPaid : b.allSetVerifying} />
          <AllSetRow done label={b.allSetDiscord} />
          <AllSetRow done label={b.allSetGuide} />
          <AllSetRow done label={b.allSetReady} />
        </ul>
        <div className="mt-7 flex flex-wrap gap-3">
          <ButtonLink to="/account" size="md">{b.viewAccount}</ButtonLink>
          <ButtonLink to="/coaching" variant="secondary" size="md">{b.backToCoaching}</ButtonLink>
        </div>
      </div>
      <div className="flex flex-col justify-center gap-3 bg-[#111318] p-6 text-white sm:p-8">
        <CoachIcon name="quote" className="h-7 w-7 text-brand-400" strokeWidth={2.4} />
        <p className="display text-[1.35rem] leading-snug">{b.quote}</p>
        <p className="text-[12px] font-semibold uppercase tracking-[0.14em] text-white/60">Global FUT Services</p>
      </div>
    </div>
  )
}

function AllSetRow({ done, label }: { done: boolean; label: string }) {
  return (
    <li className="flex items-center gap-2.5 text-body-sm text-chalk">
      <span className={`grid h-6 w-6 shrink-0 place-items-center rounded-full ${done ? 'bg-ok' : 'bg-gold-500'} text-paper`}>
        <CoachIcon name={done ? 'check' : 'clock'} className="h-3.5 w-3.5" strokeWidth={3} />
      </span>
      {label}
    </li>
  )
}
