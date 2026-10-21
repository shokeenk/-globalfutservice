import { useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { ManualPayment } from '../components/ManualPayment'
import {
  ORDER_SUMMARY, PaySummaryProvider, PayopPayButton, payopFeeLine, payopLines, payopTotal, type PaySummary,
} from '../components/paySummary'
import { PlatformIcon } from '../components/PlatformIcon'
import { SiEa, SiEpicgames, SiSteam } from 'react-icons/si'
import { RankBadge, hasBadge } from '../components/RankBadge'
import { Alert, Badge, Button, FieldError, Input, Section, Spinner, revealFirstError } from '../components/ui'
import { useCatalogLabels } from '../content/catalogLabels'
import { useT } from '../i18n'
import { ApiError, api } from '../lib/api'
import { SupportCard } from '../components/support/SupportCard'
import { CredentialForm } from '../components/CredentialForm'
import { isStubGateway, openCheckout } from '../lib/razorpay'
import { paymentChoices, type PaymentChoiceKey } from '../lib/paymentMethods'
import { internationalCopy, useInternationalOffer } from '../components/internationalOffer'
import { StoreCreditRow } from '../components/StoreCredit'
import { ChampionsInfoCard } from '../components/champs/ChampionsInfoCard'
import { ListingDetails } from '../components/listing/ListingDetails'
import { isHourly, isListingSku, listingImageSrc, listingVariant } from '../lib/listings'
import { formatMinor } from '../lib/money'
import { SEASON, useSeo } from '../lib/seo'
import type {
  CatalogOption, CreateOrderResponse, ManualPaymentOption, Order, SignedQuote,
} from '../lib/types'
import { useAuth } from '../state/AuthContext'
import { useCatalog } from '../state/CatalogContext'
import { useLiveChatOrder } from '../components/LiveChatButton'
import { firePopupStep } from '../lib/popups'
import { useNoPopups } from '../state/popupContext'

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
 * <p><b>No sign-in is collected here; it is asked for right after payment.</b> The EA login
 * goes into the encrypted form -- on the confirmation, and on the order page -- and anything
 * else into the order's live chat. Never Discord, and never a password in a chat. The
 * checkout says so before the money rather than after it: a customer who finds out at the
 * end that they still have to hand over an account has been told the price of something
 * they had not agreed to.
 */

type Step = 'details' | 'pay' | 'processing' | 'done'
type PayChoice = PaymentChoiceKey
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
  const { catalog, policy, error: catalogError } = useCatalog()
  const labels = useCatalogLabels()
  const navigate = useNavigate()
  const [params] = useSearchParams()

  const [resumed, setResumed] = useState<Order | null>(null)

  /*
   * An Objectives or Tournaments listing, by the id the Boosting page passed: `?listing=12`.
   * Looked for in both services, because the id is the listing's whichever category it is
   * in. Not in the catalogue -- switched off, deleted, or not priced in this currency -- and
   * there is nothing to sell; an order already placed for one still resumes.
   */
  const listingParam = params.get('listing')
  const listingId = listingParam && /^\d+$/.test(listingParam) ? Number(listingParam) : null
  const listingMode = listingId != null || isListingSku(resumed?.sku)
  const listingFound = useMemo(() => {
    if (listingId == null || !catalog) return null
    for (const group of catalog.services) {
      if (!isListingSku(group.sku)) continue
      const option = group.options.find((o) => o.variant === listingVariant(listingId))
      if (option?.listing) return { sku: group.sku, option, listing: option.listing }
    }
    return null
  }, [catalog, listingId])
  const listing = listingFound?.listing ?? null
  const hourly = isHourly(listing)

  const sku = listingMode
    ? (listingFound?.sku ?? (isListingSku(resumed?.sku) ? resumed.sku : 'BOOST_OBJECTIVES'))
    : BOOST_SKUS.includes((params.get('service') ?? '').toUpperCase())
      ? (params.get('service') as string).toUpperCase()
      : 'BOOST_CHAMPS'
  const service = catalog?.services.find((s) => s.sku === sku)
  const options = useMemo(
    () => (listingMode ? (listingFound ? [listingFound.option] : []) : service?.options ?? []),
    [listingMode, listingFound, service],
  )

  const [variant, setVariant] = useState(listingId != null ? listingVariant(listingId) : params.get('variant') ?? '')
  const selected = options.find((o) => o.variant === variant) ?? options[0] ?? null

  /*
   * A listing's checkout survives a trip back to the services: "Edit" returns there, and
   * choosing the same listing again brings back the hours and the code already entered.
   * Per listing, for this tab only. Hours outside the listing's range are not restored.
   */
  const storageKey = listingId != null ? `gfs.listing-checkout.${listingId}` : null
  const stored = useMemo(() => {
    if (!storageKey) return null
    try {
      const raw = window.sessionStorage.getItem(storageKey)
      return raw ? (JSON.parse(raw) as { hours?: number | null; coupon?: string }) : null
    } catch {
      return null
    }
  }, [storageKey])
  const [hours, setHours] = useState<number | null>(null)
  const [acknowledged, setAcknowledged] = useState(false)
  // Brought back once the listing is known, and only when still in its range.
  const restored = useRef(false)
  useEffect(() => {
    if (restored.current || !listing) return
    restored.current = true
    const h = stored?.hours
    if (hourly && typeof h === 'number' && Number.isInteger(h)
        && h >= (listing.minHours ?? 1) && h <= (listing.maxHours ?? 50)) {
      setHours(h)
    }
  }, [listing, hourly, stored])

  const [step, setStep] = useState<Step>('details')
  // The payment step: never a popup here.
  useNoPopups(step === 'pay')
  // The Champs order and payment page is a step a popup can wait for: Champs only, not Rivals.
  useEffect(() => {
    if (sku === 'BOOST_CHAMPS') firePopupStep('champs:checkout')
  }, [sku])
  const [platform, setPlatform] = useState<BoostPlatform | null>(null)
  const [launcher, setLauncher] = useState<Launcher | null>(null)
  const [touched, setTouched] = useState(false)

  const [couponInput, setCouponInput] = useState(params.get('coupon') ?? stored?.coupon ?? '')
  const [couponCode, setCouponCode] = useState(params.get('coupon') ?? stored?.coupon ?? '')
  useEffect(() => {
    if (!storageKey) return
    try {
      window.sessionStorage.setItem(storageKey, JSON.stringify({ hours, coupon: couponCode }))
    } catch {
      /* private mode: the checkout still works, it just will not remember */
    }
  }, [storageKey, hours, couponCode])
  // Spend the customer's GFS Store Credit on this order; offered only when they hold some.
  const [useStoreCredit, setUseStoreCredit] = useState(false)
  const [quote, setQuote] = useState<SignedQuote | null>(null)
  const [quoting, setQuoting] = useState(false)

  const [methods, setMethods] = useState<ManualPaymentOption[] | null>(null)
  const [payChoice, setPayChoice] = useState<PayChoice | null>(null)
  const [placing, setPlacing] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // What the pay step says the summary should show: the order as placed, or a Payop payment.
  const [paySummary, setPaySummary] = useState<PaySummary>(ORDER_SUMMARY)

  const [created, setCreated] = useState<CreateOrderResponse | null>(null)
  const orderRef = created?.publicRef ?? resumed?.publicRef ?? null
  // The live chat shows staff which order this is: its reference, nothing else.
  useLiveChatOrder(orderRef)

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
    // Nothing to price: a quote would only come back "not available" behind the notice.
    if (!variant || !catalog || options.length === 0 || step === 'done') return
    // By the hour: no price until the hours are chosen. Never a duration assumed for them.
    if (listingMode && hourly && hours == null) {
      setQuote(null)
      return
    }
    let live = true
    setQuoting(true)
    api.post<SignedQuote>('/api/v1/quotes', {
      sku,
      platform: null,
      variant,
      quantity: listingMode && hourly ? String(hours) : '1',
      currency: catalog.currency,
      couponCode: couponCode.trim() || null,
      pointsToRedeem: 0,
      useStoreCredit,
    })
      .then((fresh) => { if (live) setQuote(fresh) })
      .catch((e) => { if (live) setError(e instanceof ApiError ? e.message : b.placeFailed) })
      .finally(() => { if (live) setQuoting(false) })
    return () => { live = false }
  }, [sku, variant, catalog, options.length, couponCode, useStoreCredit, step, b.placeFailed, listingMode, hourly,
    hours])

  /*
   * The ways to pay: the shared list every checkout uses (lib/paymentMethods). The order's
   * own currency decides what International holds.
   */
  const orderCurrency = created?.currency ?? resumed?.currency ?? catalog?.currency
  const offer = useInternationalOffer(orderCurrency)
  const payChoices: { key: PayChoice; title: string; body: string }[] = useMemo(() => {
    if (methods === null) return []
    const copy: Record<PayChoice, { title: string; body: string }> = {
      ONLINE: { title: b.payOnlineTitle, body: b.payOnlineBody },
      UPI: { title: b.payUpiTitle, body: b.payUpiBody },
      PAYPAL: { title: b.payPaypalTitle, body: b.payPaypalBody },
      CRYPTO: { title: b.payCryptoTitle, body: b.payCryptoBody },
      INTERNATIONAL: internationalCopy(t, offer.cards),
    }
    return paymentChoices(methods, !!policy?.onlinePaymentsEnabled).map((key) => ({ key, ...copy[key] }))
  }, [policy, methods, b, t, offer.cards])

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
  const blockedReason = listingMode
    ? (hourly && hours == null ? t.listingCheckout.needHours
      : hourly && !acknowledged ? t.listingCheckout.needAck
        : null)
    : !platform ? b.needPlatform
      : platform === 'PC' && !launcher ? b.needLauncher
        : null

  async function proceedToPayment(order: CreateOrderResponse) {
    // Covered in full by store credit: already paid, so there is no payment step.
    if (!order.payment) {
      setPlacing(false)
      setStep('done')
      window.scrollTo({ top: 0 })
      return
    }
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
    if (blockedReason) {
      revealFirstError()
      return
    }
    if (!quote || !account || (!listingMode && !platform) || placing) return
    // A price for other hours than the ones on screen is never the one placed.
    if (listingMode && hourly && Number(quote.quantity) !== hours) return

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
        // Objectives and Tournaments ask for no platform: the account is given after payment, in the encrypted form.
        boostPlatform: listingMode ? null : platform,
        pcLauncher: !listingMode && platform === 'PC' ? launcher : null,
        hourlyAcknowledged: listingMode && hourly ? acknowledged : null,
      })
      setCreated(order)
      navigate(`/boosting/checkout?order=${encodeURIComponent(order.publicRef)}${
        listingId != null ? `&listing=${listingId}` : ''}`, { replace: true })
      await proceedToPayment(order)
    } catch (e) {
      setPlacing(false)
      setError(e instanceof ApiError ? e.message : b.placeFailed)
    }
  }

  const stepNumber = step === 'details' ? 1 : step === 'done' ? 3 : 2
  // Back to this listing's category on the Boosting page.
  const servicesLink = `/boosting?category=${sku === 'BOOST_TOURNAMENTS' ? 'tournaments' : 'objectives'}`

  /*
   * What the order panel says about a listing: the listing being bought, or for an order
   * being resumed, the listing as that order froze it.
   */
  const snapshot = resumed?.listing ?? null
  const panelListing: PanelListing | null = !listingMode ? null
    : listing ? {
      title: listing.title, subtitle: listing.subtitle, imageUrl: listing.imageUrl, hourly,
      unitPriceMinor: selected?.unitPriceMinor ?? 0, unitPriceFormatted: selected?.unitPriceFormatted ?? '',
      hours, currency: catalog?.currency ?? 'INR', editTo: servicesLink,
    }
      : snapshot ? {
        title: snapshot.title, subtitle: snapshot.subtitle, imageUrl: snapshot.imageUrl,
        hourly: snapshot.pricingType === 'PER_HOUR', unitPriceMinor: snapshot.unitPriceMinor,
        unitPriceFormatted: snapshot.unitPriceFormatted, hours: snapshot.hours, currency: snapshot.currency,
        editTo: servicesLink,
      }
        : null

  /*
   * Nothing to sell, said before anything is asked for.
   *
   * With no tier priced -- a season with no rate cards, or a catalogue that failed to
   * load -- this page used to ask for a sign-in and a platform and then sit on an empty
   * total with no reason given. An order already placed is priced by its own quote, so
   * resuming or paying one carries on regardless.
   */
  const resuming = Boolean(params.get('order')) || created !== null || resumed !== null
  if (!resuming && listingMode && !catalogError && catalog && options.length === 0) {
    return (
      <Section className="rhythm-section">
        <div className="mx-auto max-w-lg">
          <Alert tone="warn" title={t.listingCheckout.notOnSaleTitle}>{t.listingCheckout.notOnSaleBody}</Alert>
          <Link to={servicesLink} className="mt-4 inline-block text-[13px] font-semibold text-brand-400 hover:underline">
            {t.listingCheckout.backToServices}
          </Link>
        </div>
      </Section>
    )
  }
  if (!resuming && (catalogError || (catalog && options.length === 0))) {
    return (
      <Section className="rhythm-section">
        <div className="mx-auto max-w-lg">
          <Alert tone="warn" title={t.common.pricesUnavailable}>{catalogError ?? t.common.notOnSale}</Alert>
        </div>
      </Section>
    )
  }

  if (!authLoading && !account) {
    return (
      <Section className="rhythm-section">
        <div className="mx-auto max-w-lg text-center">
          <h1 className="display text-display-md text-chalk">{b.signInTitle}</h1>
          <p className="mt-3 text-body-sm text-chalk-muted">{b.signInBody}</p>
          {/* Points need an account: a guest order earns none (OrderService.settleRewards). */}
          <p className="mt-2 text-[12.5px] text-chalk-faint">{t.order.guestPoints}</p>
          <div className="mt-6">
            <Button
              full
              size="lg"
              onClick={() => navigate('/login', {
                state: { from: listingId != null
                  ? `/boosting/checkout?listing=${listingId}`
                  : `/boosting/checkout?service=${sku}&variant=${variant}` },
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
          <Confirmation orderRef={orderRef} emailsEnabled={!!policy?.customerEmailsEnabled} />
        ) : (
          <div className="grid grid-cols-1 gap-5 lg:grid-cols-[1.35fr_1fr] lg:items-start">
            <div className="space-y-5">
              {step === 'details' && listingMode && listing && isListingSku(sku) && (
                <ListingDetails
                  sku={sku}
                  listing={listing}
                  hours={hours}
                  onHours={setHours}
                  acknowledged={acknowledged}
                  onAcknowledged={setAcknowledged}
                  touched={touched}
                  guidance={<AfterPaymentGuidance />}
                  /*
                    Pressable while the hours are still missing, so it can say so; not while
                    a new price for changed hours is on its way.
                  */
                  cta={<ContinueBlock onContinue={() => void placeOrder()} placing={placing}
                                      disabled={placing || quoting || (!quote && !(hourly && hours == null))} />}
                />
              )}
              {step === 'details' && !listingMode && (
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
                  touched={touched}
                  disabled={placing || !quote}
                  placing={placing}
                  onContinue={() => void placeOrder()}
                  champs={sku === 'BOOST_CHAMPS' && !!policy?.champsSettlementEnabled}
                />
              )}

              {step === 'pay' && orderRef && account && (
                <PaySummaryProvider publish={setPaySummary}>
                  <div className="space-y-5">
                    {payChoices.length > 1 && (
                      <PayChoices choices={payChoices} choice={payChoice} onChoice={setPayChoice} />
                    )}
                    <ManualPayment
                      publicRef={orderRef}
                      email={account.email}
                      sku={sku}
                      totalFormatted={created?.totalFormatted ?? resumed?.totalFormatted ?? ''}
                      currency={created?.currency ?? resumed?.currency}
                      initialMethod={payChoice && payChoice !== 'ONLINE' ? payChoice : undefined}
                      onSubmitted={() => { setStep('done'); window.scrollTo({ top: 0 }) }}
                    />
                  </div>
                </PaySummaryProvider>
              )}

              {step === 'processing' && orderRef && (
                <ProcessingStep orderRef={orderRef} onConfirmed={() => setStep('done')} />
              )}
            </div>

            <OrderPanel
              option={selected}
              serviceName={labels.service(service?.sku ?? sku, service?.displayName)}
              listing={panelListing}
              quote={quote}
              quoting={quoting}
              couponInput={couponInput}
              onCouponInput={setCouponInput}
              onApplyCoupon={() => setCouponCode(couponInput)}
              editable={step === 'details'}
              pay={step === 'pay' ? paySummary : ORDER_SUMMARY}
              useStoreCredit={useStoreCredit}
              onStoreCredit={setUseStoreCredit}
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
  touched, disabled, placing, onContinue, champs = false,
}: {
  platform: BoostPlatform | null
  onPlatform: (next: BoostPlatform) => void
  launcher: Launcher | null
  onLauncher: (next: Launcher) => void
  /** Continue has been pressed: missing answers are now errors. */
  touched: boolean
  disabled: boolean
  placing: boolean
  onContinue: () => void
  /** A Champions order: the information card goes between the platform and the after-payment note. */
  champs?: boolean
}) {
  const t = useT()
  const b = t.boostingCheckout
  const platformError = touched && !platform
  const launcherError = touched && platform === 'PC' && !launcher

  /*
   * Each storefront's own mark. SiEa is Electronic Arts' corporate logo rather than the EA
   * app's badge -- Simple Icons ships no separate one -- which is close enough to read at
   * 20px and honest about whose software it is.
   */
  const launcherCopy: Record<Launcher, { title: string; sub: string; icon: ReactNode }> = {
    STEAM: { title: b.steam, sub: b.steamSub, icon: <SiSteam size={20} className="text-platform-steam" /> },
    EA_APP: { title: b.eaApp, sub: b.eaAppSub, icon: <SiEa size={20} className="text-platform-ea" /> },
    EPIC: { title: b.epic, sub: b.epicSub, icon: <SiEpicgames size={20} className="text-platform-epic" /> },
  }

  return (
    <div className="hairline rounded-panel bg-paper p-5 sm:p-6">
      <h1 className="display text-display-sm text-chalk">{b.platformTitle}</h1>
      <p className="mt-1 text-body-sm text-chalk-muted">{b.platformLead}</p>

      <div
        role="group"
        aria-label={b.platformTitle}
        aria-describedby={platformError ? 'boost-platform-error' : undefined}
        className="mt-5 grid grid-cols-1 gap-3 sm:grid-cols-2"
      >
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
      {platformError && (
        <FieldError id="boost-platform-error" className="mt-3">{b.needPlatform}</FieldError>
      )}

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
          <div
            role="group"
            aria-label={b.pcTitle}
            aria-describedby={launcherError ? 'boost-launcher-error' : undefined}
            className="mt-4 grid grid-cols-1 gap-3 sm:grid-cols-3"
          >
            {LAUNCHERS.map((option) => (
              <ChoiceCard
                key={option}
                active={launcher === option}
                onSelect={() => onLauncher(option)}
                title={launcherCopy[option].title}
                sub={launcherCopy[option].sub}
                icon={launcherCopy[option].icon}
              />
            ))}
          </div>
          {launcherError && (
            <FieldError id="boost-launcher-error" className="mt-3">{b.needLauncher}</FieldError>
          )}
          <InfoNote>{b.pcNote}</InfoNote>
        </div>
      )}

      {champs && (
        <div className="mt-6 border-t border-ink-400 pt-6">
          <ChampionsInfoCard />
        </div>
      )}

      <AfterPaymentGuidance />

      <ContinueBlock onContinue={onContinue} disabled={disabled} placing={placing} />
    </div>
  )
}

/**
 * What happens after payment, said before the money: the same on every boosting checkout,
 * Champs, Rivals, Objectives and Tournaments. The EA login goes into the encrypted form on
 * the order page, and anything else into the order's live chat.
 */
function AfterPaymentGuidance() {
  const t = useT()
  const b = t.boostingCheckout
  return (
    <div className="mt-6 border-t border-ink-400 pt-6" data-testid="after-payment">
      <div className="flex gap-3 rounded-panel border border-ink-400 bg-paper p-4">
        <span aria-hidden="true" className="grid h-10 w-10 shrink-0 place-items-center rounded-full bg-ink-700 text-chalk">
          <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="1.8">
            <rect x="4" y="10" width="16" height="10" rx="2" />
            <path d="M8 10V7a4 4 0 0 1 8 0v3" />
          </svg>
        </span>
        <div className="min-w-0">
          <h2 className="text-body-sm font-semibold text-chalk">{b.afterPaymentTitle}</h2>
          <p className="mt-1 text-[13px] leading-relaxed text-chalk-muted">{b.afterPaymentBody}</p>
          <p className="mt-2 text-[12.5px] leading-relaxed text-chalk-faint">{t.orderSupport.neverInChatShort}</p>
        </div>
      </div>
    </div>
  )
}

/** Continue to Payment, the note under it and the terms: the same on every boosting checkout. */
function ContinueBlock({ onContinue, disabled, placing }: { onContinue: () => void; disabled: boolean; placing: boolean }) {
  const t = useT()
  const b = t.boostingCheckout
  return (
    <div className="mt-6">
      {/*
        Never greyed out for a missing answer. A disabled button explains nothing, and the
        reason used to sit beneath it in grey where it read as a footnote; pressing it now
        puts the red required-field error under the choice that is missing.
      */}
      <Button full size="lg" data-popup-avoid="" onClick={onContinue} disabled={disabled}>
        {placing ? <Spinner size={18} /> : <>{b.continueToPayment} <span aria-hidden="true" className="ml-1.5">&rarr;</span></>}
      </Button>
      <div className="mt-3 rounded-edge border border-brand-500/25 bg-brand-500/[0.05] px-4 py-3">
        <p className="text-center text-[12.5px] leading-relaxed text-chalk">{b.afterPaymentNote}</p>
      </div>
      <p className="mt-3 text-center text-[12px] leading-relaxed text-chalk-faint">
        {b.termsLead}{' '}
        <Link className="font-semibold text-brand-400 hover:underline" to="/terms">{b.termsTerms}</Link>,{' '}
        <Link className="font-semibold text-brand-400 hover:underline" to="/privacy">{b.termsPrivacy}</Link>{' '}
        {b.termsAnd}{' '}
        <Link className="font-semibold text-brand-400 hover:underline" to="/aml-kyc">{b.termsAml}</Link>.
      </p>
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
        {/*
          The name wraps rather than truncates: three storefronts across one row leaves
          "Epic Games" a few pixels short, and a clipped brand name is worse than a
          second line. The caption under it may still truncate -- it repeats the name.
        */}
        <span className="block text-sm font-semibold leading-snug text-chalk">{title}</span>
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
      <div className="mt-4 grid grid-cols-1 gap-3 sm:grid-cols-2">
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
function Confirmation({ orderRef, emailsEnabled }: { orderRef: string; emailsEnabled: boolean }) {
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
  const needsSignIn = order.nextAction === 'SUBMIT_CREDENTIALS'
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
        {/*
          The email half is said only where email is actually switched on. Telling a
          customer to check an inbox nothing was sent to is how a working order turns
          into a support message.
        */}
        {emailsEnabled && paid ? `${b.confirmedEmailed} ` : ''}
        {paid ? b.confirmedLead : b.submittedLead}
      </p>

      {/*
        The EA login, right here once the payment is confirmed: the encrypted form, the same
        one the order page has. Never asked for in the chat.
      */}
      {needsSignIn && !order.credentialsSubmitted && (
        <div className="mt-6 text-left" data-testid="confirmation-signin">
          <CredentialForm publicRef={order.publicRef} onSubmitted={setOrder} />
        </div>
      )}
      {needsSignIn && order.credentialsSubmitted && (
        <div className="mt-6 text-left">
          <Alert tone="ok" title={t.track.credDone}>{t.track.credDoneBody}</Alert>
        </div>
      )}

      {/*
        Everything else about this order goes through its support page on this site, with
        the chat inside it.
      */}
      <div className="mt-6">
        <SupportCard reference={order.publicRef} sku={order.sku} />
        <p className="mt-2 text-left text-[12.5px] leading-relaxed text-chalk-faint" data-testid="never-in-chat">
          {t.orderSupport.neverInChat}
        </p>
      </div>

      {/* An Objectives or Tournaments order asked for no platform: its hours, if any, instead. */}
      <dl className={`mt-7 grid grid-cols-1 gap-px overflow-hidden rounded-panel bg-ink-400 text-left ${
        order.listing && order.listing.hours == null ? 'sm:grid-cols-3' : 'sm:grid-cols-4'}`}>
        <SummaryCell label={b.orderId} value={`#${order.publicRef}`} />
        <SummaryCell label={b.service} value={order.serviceLabel} />
        {!order.listing && <SummaryCell label={b.platformLabel} value={platformLabel} />}
        {order.listing?.hours != null && (
          <SummaryCell label={t.listingCheckout.hoursBought} value={t.listingCheckout.hours(order.listing.hours)} />
        )}
        <SummaryCell
          label={paid ? b.amountPaid : b.amount}
          value={order.totalFormatted}
          badge={paid ? undefined : <Badge tone="gold">{b.statusVerifying}</Badge>}
        />
      </dl>

      <p className="mt-6 text-[12.5px] text-chalk-muted">
        {b.needHelp}{' '}
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

/** An Objectives or Tournaments listing in the order panel. */
interface PanelListing {
  title: string
  subtitle: string | null
  imageUrl: string | null
  hourly: boolean
  unitPriceMinor: number
  unitPriceFormatted: string
  /** Chosen, or null while they have not been. */
  hours: number | null
  currency: string
  /** Back to this category's services. */
  editTo: string
}

function OrderPanel({
  option, serviceName, listing = null, quote, quoting, couponInput, onCouponInput, onApplyCoupon, editable, pay,
  useStoreCredit = false, onStoreCredit,
}: {
  option: CatalogOption | null
  serviceName: string
  listing?: PanelListing | null
  quote: SignedQuote | null
  quoting: boolean
  couponInput: string
  onCouponInput: (v: string) => void
  onApplyCoupon: () => void
  editable: boolean
  /**
   * How the order is being paid, on the pay step. A Payop payment is shown as the server
   * priced it: no 2.5% card fee, the chosen method's own fee and total, and its pay button.
   */
  pay: PaySummary
  /** Spend GFS Store Credit: offered while the order can still be changed. */
  useStoreCredit?: boolean
  onStoreCredit?: (next: boolean) => void
}) {
  const t = useT()
  const b = t.boostingCheckout
  const labels = useCatalogLabels()

  const payop = pay.kind === 'payop' ? pay : null
  const base = quote?.lines.find((line) => line.code === 'BASE') ?? null
  const extras = !quote ? []
    : payop ? [
      ...payopLines(payop, quote.lines).filter((line) => line.code !== 'BASE' && line.amountMinor !== 0),
      payopFeeLine(payop, t.order.payopSelectMethod),
    ]
      : quote.lines.filter((line) => line.code !== 'BASE' && line.amountMinor !== 0)

  return (
    <aside className="hairline rounded-panel bg-paper p-5 lg:sticky lg:top-24">
      <div className="flex items-center justify-between gap-3">
        <h2 className="display text-[16px] text-chalk">{b.orderTitle}</h2>
        {editable && (
          <Link to={listing?.editTo ?? '/boosting'} className="text-[12.5px] font-semibold text-brand-400 hover:underline">
            {b.edit}
          </Link>
        )}
      </div>

      <div className="mt-4 flex items-start gap-3 border-b border-ink-400 pb-4">
        {listing?.imageUrl
          ? (
            <span className="grid h-14 w-11 shrink-0 place-items-center">
              <img src={listingImageSrc(listing.imageUrl) ?? undefined} alt="" aria-hidden="true"
                   className="h-full w-full object-contain" />
            </span>
          )
          : option?.variant && hasBadge(option.variant)
            ? <RankBadge variant={option.variant} size={36} />
            : <span className="grid h-9 w-9 shrink-0 place-items-center rounded-edge bg-ink-700 text-[11px] font-semibold text-chalk-muted">FC</span>}
        <div className="min-w-0 flex-1">
          <p className="text-[13.5px] font-semibold leading-snug text-chalk">
            {SEASON} · {serviceName}
          </p>
          {listing ? (
            <>
              <p className="mt-0.5 text-[13px] font-semibold leading-snug text-chalk">{listing.title}</p>
              {listing.subtitle && <p className="mt-0.5 text-[12px] text-chalk-muted">{listing.subtitle}</p>}
            </>
          ) : (
            <p className="mt-0.5 text-[12px] text-chalk-muted">{option ? labels.option(option) : ''}</p>
          )}
        </div>
        <p className="tnum shrink-0 text-[13.5px] font-semibold text-chalk">
          {listing?.hourly && listing.hours == null
            ? t.boosting.perHour(listing.unitPriceFormatted)
            : base?.amountFormatted ?? option?.unitPriceFormatted ?? ''}
        </p>
      </div>

      {/* By the hour: the sum, before anything is paid. */}
      {listing?.hourly && listing.hours != null && (
        <p className="tnum mt-3 text-[12.5px] font-semibold text-chalk" data-testid="hourly-calculation">
          {t.listingCheckout.calculation(listing.unitPriceFormatted, t.listingCheckout.hours(listing.hours),
            formatMinor(listing.unitPriceMinor * listing.hours, listing.currency))}
        </p>
      )}

      {/* The quote's own lines: the processing fee, a coupon, a tier discount. */}
      <dl className="mt-4 space-y-2">
        {extras.map((line) => (
          <div key={line.code} className="flex items-baseline justify-between gap-3">
            <dt className="text-[12.5px] text-chalk-muted">
              {line.code === 'PAYMENT_FEE' || line.code === 'STORE_CREDIT' ? labels.line(line) : line.label}
            </dt>
            <dd className={`tnum text-[12.5px] font-semibold ${line.amountMinor < 0 ? 'text-ok' : 'text-chalk'}`}>
              {line.amountFormatted}
            </dd>
          </div>
        ))}
      </dl>

      <div className="mt-4 flex gap-2" data-popup-avoid="">
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

      {editable && onStoreCredit && (
        <div className="mt-4">
          <StoreCreditRow quote={quote} use={useStoreCredit} onChange={onStoreCredit} busy={quoting} />
        </div>
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
        <p className="tnum text-[18px] font-semibold text-chalk" data-testid="summary-total" data-popup-avoid="">
          {listing?.hourly && listing.hours == null && !payop
            ? <span className="text-[12.5px] font-normal text-chalk-muted">{t.listingCheckout.hoursForTotal}</span>
            : quoting && !quote ? <Spinner size={16} /> : payop ? payopTotal(payop) : quote?.totalFormatted ?? ''}
        </p>
      </div>

      {/* A Payop payment starts here, under the total it charges. */}
      {payop && <div className="mt-4"><PayopPayButton selection={payop} /></div>}

      <ul className="mt-5 grid grid-cols-1 gap-4 border-t border-ink-400 pt-5 sm:grid-cols-3">
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
