import { useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { EaSignInFields, validateEaSignIn } from '../components/EaSignInFields'
import { ManualPayment } from '../components/ManualPayment'
import { PlatformIcon } from '../components/PlatformIcon'
import { RankBadge, hasBadge } from '../components/RankBadge'
import { Alert, Badge, Button, Input, Section, Spinner } from '../components/ui'
import { BUSINESS } from '../content/business'
import { useCatalogLabels } from '../content/catalogLabels'
import { useT } from '../i18n'
import { ApiError, api } from '../lib/api'
import { isStubGateway, openCheckout } from '../lib/razorpay'
import { SEASON, useSeo } from '../lib/seo'
import type {
  CatalogOption, CreateOrderResponse, ManualPaymentMethod, ManualPaymentOption, Order, SignedQuote,
} from '../lib/types'
import { useAuth } from '../state/AuthContext'
import { useCatalog } from '../state/CatalogContext'

/**
 * Boosting, from "which account" to "we have your payment".
 *
 * <p>Its own page rather than another branch of the coin configurator. A boosting order
 * asks a different question from a coin order -- not how much, but where and how we sign
 * in -- and the two checkouts had been drifting into one form with half its fields hidden
 * from whoever was looking at it.
 *
 * <p><b>Three steps, and the third is the honest one.</b> A gateway payment is confirmed by
 * the gateway. A scan-and-pay payment is confirmed by a person checking the account, so
 * until that happens the confirmation says the payment was submitted and is being checked,
 * and turns itself into "confirmed" when the order moves. Telling somebody their payment
 * succeeded before anyone has looked is the one thing a checkout must not do.
 *
 * <p><b>The sign-in never goes near Discord.</b> It is collected here, sealed with a key
 * unique to the order before it reaches the database, and destroyed when the order is done
 * -- the same vault the coin checkout uses. Discord is where the booster talks to the
 * customer, not where passwords are typed.
 */

type Step = 'details' | 'pay' | 'processing' | 'done'
type PayChoice = 'ONLINE' | ManualPaymentMethod
type BoostPlatform = 'PLAYSTATION' | 'PC'
type Launcher = 'STEAM' | 'EA_APP' | 'EPIC'

const LAUNCHERS: Launcher[] = ['STEAM', 'EA_APP', 'EPIC']
const BOOST_SKUS = ['BOOST_CHAMPS', 'BOOST_RIVALS']

/** States from which the money has been found. */
function isPaid(order: Order | null): boolean {
  return !!order && !['DRAFT', 'AWAITING_PAYMENT', 'ABANDONED'].includes(order.status)
}

export default function BoostingCheckout() {
  const t = useT()
  const b = t.boostingCheckout
  useSeo({ title: b.seoTitle, noindex: true })

  const { account, loading: authLoading } = useAuth()
  const { catalog, policy } = useCatalog()
  const labels = useCatalogLabels()
  const navigate = useNavigate()
  const [params] = useSearchParams()

  const sku = BOOST_SKUS.includes((params.get('service') ?? '').toUpperCase())
    ? (params.get('service') as string).toUpperCase()
    : 'BOOST_CHAMPS'
  const service = catalog?.services.find((s) => s.sku === sku)
  const options = useMemo(() => service?.options ?? [], [service])

  const [variant, setVariant] = useState(params.get('variant') ?? '')
  const selected = options.find((o) => o.variant === variant) ?? options[0] ?? null

  const [step, setStep] = useState<Step>('details')
  const [platform, setPlatform] = useState<BoostPlatform | null>(null)
  const [launcher, setLauncher] = useState<Launcher | null>(null)
  const [eaEmail, setEaEmail] = useState('')
  const [eaPassword, setEaPassword] = useState('')
  const [backupCodes, setBackupCodes] = useState(['', '', ''])
  const [credErrors, setCredErrors] = useState<Record<string, string>>({})
  const [touched, setTouched] = useState(false)

  const [couponInput, setCouponInput] = useState(params.get('coupon') ?? '')
  const [couponCode, setCouponCode] = useState(params.get('coupon') ?? '')
  const [quote, setQuote] = useState<SignedQuote | null>(null)
  const [quoting, setQuoting] = useState(false)

  const [methods, setMethods] = useState<ManualPaymentOption[] | null>(null)
  const [payChoice, setPayChoice] = useState<PayChoice | null>(null)
  const [placing, setPlacing] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const [created, setCreated] = useState<CreateOrderResponse | null>(null)
  const [resumed, setResumed] = useState<Order | null>(null)
  const orderRef = created?.publicRef ?? resumed?.publicRef ?? null

  /*
   * A variant that came from a link but is not in the live catalogue would price nothing,
   * so it falls back to the first real tier rather than to a dead request.
   */
  useEffect(() => {
    if (options.length > 0 && !options.some((o) => o.variant === variant)) {
      setVariant(options[0]?.variant ?? '')
    }
  }, [options, variant])

  // An order reference in the URL resumes that order instead of starting another.
  const started = useRef(false)
  useEffect(() => {
    if (started.current || authLoading) return
    started.current = true
    const ref = params.get('order')
    if (ref && account) {
      api.get<Order>(`/api/v1/orders/${encodeURIComponent(ref)}`)
        .then((found) => {
          setResumed(found)
          setStep(isPaid(found) ? 'done' : 'pay')
        })
        .catch(() => setError(b.loadFailed))
    }
  }, [authLoading, account, params, b.loadFailed])

  useEffect(() => {
    let live = true
    api.get<ManualPaymentOption[]>(`/api/v1/payments/methods?sku=${sku}`)
      .then((found) => { if (live) setMethods(found) })
      .catch(() => { if (live) setMethods([]) })
    return () => { live = false }
  }, [sku])

  /*
   * The price is the engine's. The tier price is not the total -- payment processing is
   * added, a coupon or a tier discount may come off -- so the panel renders the quote's
   * own lines rather than arithmetic done here.
   */
  useEffect(() => {
    if (!variant || !catalog || step === 'done') return
    let live = true
    setQuoting(true)
    api.post<SignedQuote>('/api/v1/quotes', {
      sku,
      platform: null,
      variant,
      quantity: '1',
      currency: catalog.currency,
      couponCode: couponCode.trim() || null,
      pointsToRedeem: 0,
    })
      .then((fresh) => { if (live) setQuote(fresh) })
      .catch((e) => { if (live) setError(e instanceof ApiError ? e.message : b.placeFailed) })
      .finally(() => { if (live) setQuoting(false) })
    return () => { live = false }
  }, [sku, variant, catalog, couponCode, step, b.placeFailed])

  const payChoices: { key: PayChoice; title: string; body: string }[] = useMemo(() => {
    const list: { key: PayChoice; title: string; body: string }[] = []
    if (policy?.onlinePaymentsEnabled) list.push({ key: 'ONLINE', title: b.payOnlineTitle, body: b.payOnlineBody })
    for (const m of methods ?? []) {
      if (m.method === 'UPI') list.push({ key: 'UPI', title: b.payUpiTitle, body: b.payUpiBody })
      if (m.method === 'PAYPAL') list.push({ key: 'PAYPAL', title: b.payPaypalTitle, body: b.payPaypalBody })
      if (m.method === 'CRYPTO') list.push({ key: 'CRYPTO', title: b.payCryptoTitle, body: b.payCryptoBody })
    }
    return list
  }, [policy, methods, b])

  useEffect(() => {
    const first = payChoices[0]
    if (!payChoice && first) setPayChoice(first.key)
  }, [payChoice, payChoices])

  /*
   * Why the customer cannot continue yet, or null when they can.
   *
   * PlayStation is a complete answer on its own; PC is not, because the same EA account
   * opened through Steam and through the EA app is two different sign-ins. The server
   * refuses both cases too -- this is so they are told at the step that asks.
   */
  const blockedReason = !platform ? b.needPlatform
    : platform === 'PC' && !launcher ? b.needLauncher
      : null

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
    setTouched(true)
    const found = validateEaSignIn(t, eaEmail, eaPassword, backupCodes)
    setCredErrors(found)
    if (blockedReason || Object.keys(found).length > 0) return
    if (!quote || !account || !platform || placing) return

    setPlacing(true)
    setError(null)
    try {
      // One order per checkout: coming back here pays for the order that exists rather
      // than placing a second one.
      if (created) {
        await proceedToPayment(created)
        return
      }
      const order = await api.post<CreateOrderResponse>('/api/v1/orders', {
        quote,
        email: account.email,
        fullName: account.displayName ?? null,
        acceptedTerms: true,
        boostPlatform: platform,
        pcLauncher: platform === 'PC' ? launcher : null,
      })
      /*
       * The sign-in is a second request on purpose. `/orders` writes plain columns; the
       * vault endpoint seals with a per-order key before anything reaches the database,
       * so bundling the password into the first call would have put it in plaintext.
       */
      await api.post(`/api/v1/orders/${order.publicRef}/credentials`, {
        eaEmail: eaEmail.trim(),
        eaPassword,
        backupCodes: backupCodes.map((code) => code.trim()),
        platformHandle: null,
        note: null,
        acknowledgedSignedOut: true,
        acknowledgedMarketUnlocked: true,
        acknowledgedItemsClear: true,
        acceptedTerms: true,
      })
      // Cleared the moment the vault has it: nothing here outlives the request.
      setEaPassword('')
      setBackupCodes(['', '', ''])
      setCreated(order)
      navigate(`/boosting/checkout?order=${encodeURIComponent(order.publicRef)}`, { replace: true })
      await proceedToPayment(order)
    } catch (e) {
      setPlacing(false)
      setError(e instanceof ApiError ? e.message : b.placeFailed)
    }
  }

  const stepNumber = step === 'details' ? 1 : step === 'done' ? 3 : 2

  if (!authLoading && !account) {
    return (
      <Section className="rhythm-section">
        <div className="mx-auto max-w-lg text-center">
          <h1 className="display text-display-md text-chalk">{b.signInTitle}</h1>
          <p className="mt-3 text-body-sm text-chalk-muted">{b.signInBody}</p>
          <div className="mt-6">
            <Button
              full
              size="lg"
              onClick={() => navigate('/login', {
                state: { from: `/boosting/checkout?service=${sku}&variant=${variant}` },
              })}
            >
              {b.signInCta}
            </Button>
          </div>
        </div>
      </Section>
    )
  }

  return (
    <Section className="rhythm-section">
      <div className="mx-auto max-w-6xl">
        <CheckoutHeader current={stepNumber} />

        {error && <div className="mb-6"><Alert tone="warn">{error}</Alert></div>}

        {step === 'done' && orderRef ? (
          <Confirmation orderRef={orderRef} />
        ) : (
          <div className="grid gap-5 lg:grid-cols-[1.35fr_1fr] lg:items-start">
            <div className="space-y-5">
              {step === 'details' && (
                <DetailsStep
                  platform={platform}
                  onPlatform={(next) => {
                    setPlatform(next)
                    // A console order has no launcher, so one picked before the switch
                    // must not survive it.
                    if (next !== 'PC') setLauncher(null)
                  }}
                  launcher={launcher}
                  onLauncher={setLauncher}
                  eaEmail={eaEmail} setEaEmail={setEaEmail}
                  eaPassword={eaPassword} setEaPassword={setEaPassword}
                  backupCodes={backupCodes} setBackupCodes={setBackupCodes}
                  credErrors={credErrors}
                  blockedReason={touched ? blockedReason : null}
                  disabled={Boolean(blockedReason) || placing || !quote}
                  placing={placing}
                  onContinue={() => void placeOrder()}
                />
              )}

              {step === 'pay' && orderRef && account && (
                <div className="space-y-5">
                  {payChoices.length > 1 && (
                    <PayChoices choices={payChoices} choice={payChoice} onChoice={setPayChoice} />
                  )}
                  <ManualPayment
                    publicRef={orderRef}
                    email={account.email}
                    sku={sku}
                    totalFormatted={created?.totalFormatted ?? resumed?.totalFormatted ?? ''}
                    initialMethod={payChoice && payChoice !== 'ONLINE' ? payChoice : undefined}
                    onSubmitted={() => { setStep('done'); window.scrollTo({ top: 0 }) }}
                  />
                </div>
              )}

              {step === 'processing' && orderRef && (
                <ProcessingStep orderRef={orderRef} onConfirmed={() => setStep('done')} />
              )}
            </div>

            <OrderPanel
              option={selected}
              serviceName={labels.service(service?.sku, service?.displayName)}
              quote={quote}
              quoting={quoting}
              couponInput={couponInput}
              onCouponInput={setCouponInput}
              onApplyCoupon={() => setCouponCode(couponInput)}
              editable={step === 'details'}
            />
          </div>
        )}
      </div>
    </Section>
  )
}

/* --------------------------------------------------------------------- header --- */

function CheckoutHeader({ current }: { current: number }) {
  const t = useT()
  const b = t.boostingCheckout
  const steps = [b.stepDetails, b.stepPayment, b.stepConfirmation]

  return (
    <div className="mb-6 flex flex-col gap-4 border-b border-ink-400 pb-5 sm:flex-row sm:items-center sm:justify-between">
      <div className="flex items-center gap-3">
        <span className="grid h-10 w-10 shrink-0 place-items-center rounded-full bg-ink-700 text-chalk">
          <svg aria-hidden="true" viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="2">
            <rect x="4" y="10" width="16" height="10" rx="2" />
            <path d="M8 10V7a4 4 0 0 1 8 0v3" />
          </svg>
        </span>
        <div>
          <p className="display text-[16px] leading-tight text-chalk">{b.secureCheckout}</p>
          <p className="text-[12.5px] text-chalk-muted">{b.secureLead}</p>
        </div>
      </div>

      <ol className="flex flex-wrap items-center gap-x-2 gap-y-2 sm:gap-x-3">
        {steps.map((label, index) => {
          const number = index + 1
          const active = number === current
          const done = number < current
          return (
            <li key={label} className="flex items-center gap-2 sm:gap-3">
              <span className="flex items-center gap-2">
                <span
                  aria-current={active ? 'step' : undefined}
                  className={[
                    'tnum grid h-7 w-7 shrink-0 place-items-center rounded-full text-[12px] font-semibold',
                    active || done ? 'bg-brand-500 text-paper' : 'bg-ink-700 text-chalk-muted',
                  ].join(' ')}
                >
                  {number}
                </span>
                <span className={`text-[12.5px] font-semibold ${active ? 'text-chalk' : 'text-chalk-muted'}`}>
                  {label}
                </span>
              </span>
              {number < steps.length && (
                <span aria-hidden="true" className="hidden h-px w-5 bg-ink-400 sm:block sm:w-8" />
              )}
            </li>
          )
        })}
      </ol>
    </div>
  )
}

/* ---------------------------------------------------------------- step: details --- */

function DetailsStep({
  platform, onPlatform, launcher, onLauncher,
  eaEmail, setEaEmail, eaPassword, setEaPassword, backupCodes, setBackupCodes, credErrors,
  blockedReason, disabled, placing, onContinue,
}: {
  platform: BoostPlatform | null
  onPlatform: (next: BoostPlatform) => void
  launcher: Launcher | null
  onLauncher: (next: Launcher) => void
  eaEmail: string
  setEaEmail: (v: string) => void
  eaPassword: string
  setEaPassword: (v: string) => void
  backupCodes: string[]
  setBackupCodes: (codes: string[]) => void
  credErrors: Record<string, string>
  blockedReason: string | null
  disabled: boolean
  placing: boolean
  onContinue: () => void
}) {
  const t = useT()
  const b = t.boostingCheckout

  const launcherCopy: Record<Launcher, { title: string; sub: string }> = {
    STEAM: { title: b.steam, sub: b.steamSub },
    EA_APP: { title: b.eaApp, sub: b.eaAppSub },
    EPIC: { title: b.epic, sub: b.epicSub },
  }

  return (
    <div className="hairline rounded-panel bg-paper p-5 sm:p-6">
      <h1 className="display text-display-sm text-chalk">{b.platformTitle}</h1>
      <p className="mt-1 text-body-sm text-chalk-muted">{b.platformLead}</p>

      <div className="mt-5 grid gap-3 sm:grid-cols-2">
        <ChoiceCard
          active={platform === 'PLAYSTATION'}
          onSelect={() => onPlatform('PLAYSTATION')}
          title={b.playstation}
          sub={b.playstationSub}
          icon={<PlatformIcon platform="PLAYSTATION" className="h-6 w-6" />}
        />
        <ChoiceCard
          active={platform === 'PC'}
          onSelect={() => onPlatform('PC')}
          title={b.pc}
          sub={b.pcSub}
          icon={<PlatformIcon platform="PC" className="h-6 w-6" />}
        />
      </div>

      {/*
        One note, and which one depends on the answer. PlayStation is complete on its own
        and says so; PC opens a question that is real on PC and meaningless on console,
        which is why the sub-selector is not simply always on screen.
      */}
      {platform === 'PLAYSTATION' && <InfoNote>{b.psNote}</InfoNote>}

      {platform === 'PC' && (
        <div className="mt-6 border-t border-ink-400 pt-6">
          <h2 className="display text-[16px] text-chalk">{b.pcTitle}</h2>
          <p className="mt-1 text-body-sm text-chalk-muted">{b.pcLead}</p>
          <div className="mt-4 grid gap-3 sm:grid-cols-3">
            {LAUNCHERS.map((option) => (
              <ChoiceCard
                key={option}
                active={launcher === option}
                onSelect={() => onLauncher(option)}
                title={launcherCopy[option].title}
                sub={launcherCopy[option].sub}
                icon={<PlatformIcon platform="PC" className="h-5 w-5" />}
              />
            ))}
          </div>
          <InfoNote>{b.pcNote}</InfoNote>
        </div>
      )}

      {/*
        The sign-in, on the site and not in a chat window.

        A booster has to log in and play the games, so this is unavoidable — what is
        avoidable is where it lives. Sealed with a key unique to this order before it
        reaches the database, opened by the person working the order, destroyed when it
        is done.
      */}
      <div className="mt-6 border-t border-ink-400 pt-6">
        <h2 className="display text-[16px] text-chalk">{b.signInSectionTitle}</h2>
        <p className="mb-5 mt-1 text-body-sm text-chalk-muted">{b.signInSectionLead}</p>
        <EaSignInFields
          eaEmail={eaEmail} setEaEmail={setEaEmail}
          eaPassword={eaPassword} setEaPassword={setEaPassword}
          backupCodes={backupCodes} setBackupCodes={setBackupCodes}
          errors={credErrors}
        />
      </div>

      <div className="mt-6">
        <Button full size="lg" onClick={onContinue} disabled={disabled}>
          {placing ? <Spinner size={18} /> : <>{b.continueToPayment} <span aria-hidden="true" className="ml-1.5">&rarr;</span></>}
        </Button>
        {blockedReason && (
          <p className="mt-2 text-center text-[12.5px] text-chalk-faint">{blockedReason}</p>
        )}
        <p className="mt-3 text-center text-[12px] leading-relaxed text-chalk-faint">
          {b.termsLead}{' '}
          <Link className="font-semibold text-brand-400 hover:underline" to="/terms">{b.termsTerms}</Link>,{' '}
          <Link className="font-semibold text-brand-400 hover:underline" to="/privacy">{b.termsPrivacy}</Link>{' '}
          {b.termsAnd}{' '}
          <Link className="font-semibold text-brand-400 hover:underline" to="/aml-kyc">{b.termsAml}</Link>.
        </p>
      </div>
    </div>
  )
}

function ChoiceCard({
  active, onSelect, title, sub, icon,
}: {
  active: boolean
  onSelect: () => void
  title: string
  sub: string
  /** Omitted where there is nothing to draw -- a payment method is a word, not a mark. */
  icon?: ReactNode
}) {
  return (
    <button
      type="button"
      onClick={onSelect}
      aria-pressed={active}
      className={[
        'flex items-center gap-3 rounded-panel border p-4 text-left transition-colors duration-200',
        active
          ? 'border-brand-500 bg-brand-500/[0.06]'
          : 'border-ink-400 bg-paper hover:border-ink-300',
      ].join(' ')}
    >
      {icon && (
        <span className="grid h-10 w-10 shrink-0 place-items-center rounded-full bg-ink-700 text-chalk">
          {icon}
        </span>
      )}
      <span className="min-w-0 flex-1">
        <span className="block truncate text-sm font-semibold text-chalk">{title}</span>
        <span className="block truncate text-[12px] text-chalk-muted">{sub}</span>
      </span>
      {/* The second, non-colour channel that says "this one". */}
      <span
        aria-hidden="true"
        className={[
          'grid h-5 w-5 shrink-0 place-items-center rounded-full border',
          active ? 'border-brand-500 bg-brand-500 text-paper' : 'border-ink-300',
        ].join(' ')}
      >
        {active && (
          <svg viewBox="0 0 20 20" className="h-3 w-3" fill="none" stroke="currentColor" strokeWidth="3">
            <path d="m5 10.5 3.5 3.5L15 7" />
          </svg>
        )}
      </span>
    </button>
  )
}

function InfoNote({ children }: { children: ReactNode }) {
  return (
    <div className="mt-4 flex gap-2.5 rounded-edge bg-ink-700 px-4 py-3">
      <svg aria-hidden="true" viewBox="0 0 20 20" className="mt-px h-4 w-4 shrink-0 text-chalk-muted"
           fill="none" stroke="currentColor" strokeWidth="1.8">
        <circle cx="10" cy="10" r="7.5" />
        <path d="M10 9v5M10 6.2v.2" strokeLinecap="round" />
      </svg>
      <p className="text-[12.5px] leading-relaxed text-chalk-muted">{children}</p>
    </div>
  )
}

/* ------------------------------------------------------------------ step: pay --- */

function PayChoices({
  choices, choice, onChoice,
}: {
  choices: { key: PayChoice; title: string; body: string }[]
  choice: PayChoice | null
  onChoice: (next: PayChoice) => void
}) {
  const t = useT()
  return (
    <div className="hairline rounded-panel bg-paper p-5">
      <h2 className="display text-[16px] text-chalk">{t.boostingCheckout.payTitle}</h2>
      <div className="mt-4 grid gap-3 sm:grid-cols-2">
        {choices.map((entry) => (
          <ChoiceCard
            key={entry.key}
            active={choice === entry.key}
            onSelect={() => onChoice(entry.key)}
            title={entry.title}
            sub={entry.body}
          />
        ))}
      </div>
    </div>
  )
}

/**
 * The wait between a card payment and the order moving.
 *
 * <p>Only the gateway path reaches this. It polls the order rather than assuming, because
 * "the sheet closed" and "the money arrived" are different events.
 */
function ProcessingStep({ orderRef, onConfirmed }: { orderRef: string; onConfirmed: () => void }) {
  const t = useT()
  const b = t.boostingCheckout

  useEffect(() => {
    let stop = false
    let timer: number | undefined
    const tick = async () => {
      try {
        const found = await api.get<Order>(`/api/v1/orders/${encodeURIComponent(orderRef)}`)
        if (stop) return
        if (isPaid(found)) { onConfirmed(); return }
      } catch {
        /* keep waiting: a dropped poll is not a failed payment */
      }
      if (!stop) timer = window.setTimeout(tick, 2_500)
    }
    void tick()
    return () => { stop = true; if (timer) window.clearTimeout(timer) }
  }, [orderRef, onConfirmed])

  return (
    <div className="hairline rounded-panel bg-paper p-8 text-center">
      <Spinner size={32} />
      <h2 className="display mt-4 text-[18px] text-chalk">{b.processingTitle}</h2>
      <p className="mt-2 text-body-sm text-chalk-muted">{b.processingBody}</p>
    </div>
  )
}

/* --------------------------------------------------------- step: confirmation --- */

/**
 * What happened, in the order's own words.
 *
 * <p>Polls while the order is unpaid so a screen left open turns itself into the confirmed
 * version the moment an operator verifies the payment — which is the difference between a
 * customer refreshing a page and a customer opening a support ticket.
 */
function Confirmation({ orderRef }: { orderRef: string }) {
  const t = useT()
  const b = t.boostingCheckout
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

  if (!order) {
    return <div className="grid min-h-[40vh] place-items-center"><Spinner size={32} /></div>
  }

  const paid = isPaid(order)
  const platformLabel = order.platform === 'PLAYSTATION' ? b.playstation
    : order.platform === 'PC'
      ? `${b.pc}${order.pcLauncher === 'STEAM' ? ` · ${b.steam}`
        : order.pcLauncher === 'EA_APP' ? ` · ${b.eaApp}`
          : order.pcLauncher === 'EPIC' ? ` · ${b.epic}` : ''}`
      : '—'

  return (
    <div className="mx-auto max-w-2xl text-center">
      <span className={`mx-auto grid h-20 w-20 place-items-center rounded-full ${paid ? 'bg-ok/10 text-ok' : 'bg-gold-500/15 text-gold-500'}`}>
        <svg aria-hidden="true" viewBox="0 0 24 24" className="h-10 w-10" fill="none" stroke="currentColor" strokeWidth="2.2">
          {paid ? <path d="m5 12.5 4.5 4.5L19 7.5" /> : <><circle cx="12" cy="12" r="8.5" /><path d="M12 7.5V12l3 2" /></>}
        </svg>
      </span>

      <h1 className="display mt-5 text-display-md text-chalk">{paid ? b.confirmedTitle : b.submittedTitle}</h1>
      <p className="mt-2 text-body-sm text-chalk-muted">{paid ? b.confirmedBody : b.submittedBody}</p>
      <p className="mx-auto mt-3 max-w-xl text-body-sm leading-relaxed text-chalk-muted">
        {paid ? b.confirmedLead : b.submittedLead}
      </p>

      <div className="mt-6">
        <a
          href={BUSINESS.discordInvite}
          target="_blank"
          rel="noreferrer"
          className="inline-flex min-h-[52px] items-center justify-center gap-2.5 rounded-edge
                     bg-brand-500 px-6 text-body-sm font-semibold text-paper transition-colors
                     duration-200 hover:bg-brand-400 focus-visible:outline focus-visible:outline-2
                     focus-visible:outline-offset-2 focus-visible:outline-brand-400"
        >
          <svg aria-hidden="true" viewBox="0 0 24 24" className="h-5 w-5" fill="currentColor">
            <path d="M20.3 4.4A19.8 19.8 0 0 0 15.4 3l-.24.5a18.3 18.3 0 0 1 4.3 1.4c-2-1.1-4.1-1.6-6.4-1.6-2.3 0-4.4.5-6.4 1.6A18.3 18.3 0 0 1 11 3.5L10.7 3a19.8 19.8 0 0 0-4.9 1.4C2.6 9.1 1.7 13.7 2.1 18.2a19.9 19.9 0 0 0 6 3c.5-.65.9-1.35 1.25-2.1-.7-.25-1.35-.55-1.95-.9.16-.12.32-.25.47-.38a14.2 14.2 0 0 0 12.2 0c.16.14.31.26.47.38-.62.36-1.27.66-1.96.9.36.75.78 1.45 1.25 2.1a19.8 19.8 0 0 0 6-3c.5-5.2-.85-9.75-3.5-13.8ZM8.7 15.4c-1.18 0-2.15-1.07-2.15-2.4S7.5 10.6 8.7 10.6s2.17 1.08 2.15 2.4c0 1.33-.96 2.4-2.15 2.4Zm6.6 0c-1.18 0-2.15-1.07-2.15-2.4s.95-2.4 2.15-2.4 2.17 1.08 2.15 2.4c0 1.33-.95 2.4-2.15 2.4Z" />
          </svg>
          {b.joinDiscord}
          <svg aria-hidden="true" viewBox="0 0 20 20" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M12 4h4v4M16 4l-7 7M14 12v4H4V6h4" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </a>
        <p className="mt-2 text-[12px] text-chalk-faint">{b.joinCaption}</p>
      </div>

      <dl className="mt-7 grid gap-px overflow-hidden rounded-panel bg-ink-400 text-left sm:grid-cols-4">
        <SummaryCell label={b.orderId} value={`#${order.publicRef}`} />
        <SummaryCell label={b.service} value={order.serviceLabel} />
        <SummaryCell label={b.platformLabel} value={platformLabel} />
        <SummaryCell
          label={paid ? b.amountPaid : b.amount}
          value={order.totalFormatted}
          badge={paid ? undefined : <Badge tone="gold">{b.statusVerifying}</Badge>}
        />
      </dl>

      <p className="mt-6 text-[12.5px] text-chalk-muted">
        {b.needHelp}{' '}
        <a className="font-semibold text-brand-400 hover:underline" href={BUSINESS.discordInvite} target="_blank" rel="noreferrer">
          {b.joinOurDiscord}
        </a>{' '}
        {b.orWord}{' '}
        <Link className="font-semibold text-brand-400 hover:underline" to="/support">{b.contactSupport}</Link>.
      </p>
    </div>
  )
}

function SummaryCell({ label, value, badge }: { label: string; value: string; badge?: ReactNode }) {
  return (
    <div className="bg-paper px-4 py-3">
      <dt className="stamp text-[10.5px] text-chalk-faint">{label}</dt>
      <dd className="mt-1 break-words text-[13px] font-semibold text-chalk">
        {value}
        {badge && <span className="ml-2 align-middle">{badge}</span>}
      </dd>
    </div>
  )
}

/* ---------------------------------------------------------------- order panel --- */

function OrderPanel({
  option, serviceName, quote, quoting, couponInput, onCouponInput, onApplyCoupon, editable,
}: {
  option: CatalogOption | null
  serviceName: string
  quote: SignedQuote | null
  quoting: boolean
  couponInput: string
  onCouponInput: (v: string) => void
  onApplyCoupon: () => void
  editable: boolean
}) {
  const t = useT()
  const b = t.boostingCheckout
  const labels = useCatalogLabels()

  const base = quote?.lines.find((line) => line.code === 'BASE') ?? null
  const extras = quote?.lines.filter((line) => line.code !== 'BASE' && line.amountMinor !== 0) ?? []

  return (
    <aside className="hairline rounded-panel bg-paper p-5 lg:sticky lg:top-24">
      <div className="flex items-center justify-between gap-3">
        <h2 className="display text-[16px] text-chalk">{b.orderTitle}</h2>
        {editable && (
          <Link to="/boosting" className="text-[12.5px] font-semibold text-brand-400 hover:underline">
            {b.edit}
          </Link>
        )}
      </div>

      <div className="mt-4 flex items-start gap-3 border-b border-ink-400 pb-4">
        {option?.variant && hasBadge(option.variant)
          ? <RankBadge variant={option.variant} size={36} />
          : <span className="grid h-9 w-9 shrink-0 place-items-center rounded-edge bg-ink-700 text-[11px] font-semibold text-chalk-muted">FC</span>}
        <div className="min-w-0 flex-1">
          <p className="text-[13.5px] font-semibold leading-snug text-chalk">
            {SEASON} · {serviceName}
          </p>
          <p className="mt-0.5 text-[12px] text-chalk-muted">{option ? labels.option(option) : ''}</p>
        </div>
        <p className="tnum shrink-0 text-[13.5px] font-semibold text-chalk">
          {base?.amountFormatted ?? option?.unitPriceFormatted ?? ''}
        </p>
      </div>

      {/* The quote's own lines: the processing fee, a coupon, a tier discount. */}
      <dl className="mt-4 space-y-2">
        {extras.map((line) => (
          <div key={line.code} className="flex items-baseline justify-between gap-3">
            <dt className="text-[12.5px] text-chalk-muted">{line.label}</dt>
            <dd className={`tnum text-[12.5px] font-semibold ${line.amountMinor < 0 ? 'text-ok' : 'text-chalk'}`}>
              {line.amountFormatted}
            </dd>
          </div>
        ))}
      </dl>

      <div className="mt-4 flex gap-2">
        <Input
          value={couponInput}
          onChange={(e) => onCouponInput(e.target.value)}
          placeholder={b.couponPlaceholder}
          aria-label={b.couponPlaceholder}
          maxLength={32}
        />
        <Button variant="secondary" onClick={onApplyCoupon} disabled={!couponInput.trim() || quoting}>
          {b.apply}
        </Button>
      </div>
      {quote?.couponMessage && (
        <p className="mt-2 text-[12px] text-chalk-faint">{quote.couponMessage}</p>
      )}

      {/*
        Points are earned, not granted here: the wording is the one the rewards page and
        the coin checkout already use, because three different sentences about when points
        land is how a customer ends up asking where they went.
      */}
      {quote && quote.pointsEarned > 0 && (
        <div className="mt-4 rounded-edge border border-ok/30 bg-ok/[0.07] p-3.5">
          <p className="text-[12.5px] font-semibold text-chalk">{b.rewardsTitle}</p>
          <p className="mt-1 text-[12px] leading-relaxed text-chalk-muted">
            {t.order.earnsPoints(quote.pointsEarned)}
          </p>
        </div>
      )}

      <div className="mt-4 flex items-baseline justify-between gap-3 rounded-edge bg-brand-500/[0.06] px-4 py-3">
        <p className="text-[13px] font-semibold text-chalk">{b.total}</p>
        <p className="tnum text-[18px] font-semibold text-chalk">
          {quoting && !quote ? <Spinner size={16} /> : quote?.totalFormatted ?? ''}
        </p>
      </div>

      <ul className="mt-5 grid gap-4 border-t border-ink-400 pt-5 sm:grid-cols-3">
        <TrustBadge title={b.trustSecureTitle} body={b.trustSecureBody} />
        <TrustBadge title={b.trustFastTitle} body={b.trustFastBody} />
        <TrustBadge title={b.trustSupportTitle} body={b.trustSupportBody} />
      </ul>
    </aside>
  )
}

function TrustBadge({ title, body }: { title: string; body: string }) {
  return (
    <li className="text-center">
      <p className="text-[12px] font-semibold text-chalk">{title}</p>
      <p className="mt-0.5 text-[11.5px] leading-snug text-chalk-muted">{body}</p>
    </li>
  )
}
