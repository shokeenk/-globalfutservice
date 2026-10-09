import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Alert, Field, Select, SelectTile } from './ui'
import { useI18n } from '../i18n'
import { ApiError, api } from '../lib/api'
import { COUNTRY_CODES, countryCode, countryFromLocale, countryName } from '../lib/countries'
import type { QuoteLine } from '../lib/types'
import { ORDER_SUMMARY, usePublishPaySummary } from './paySummary'

/**
 * Paying an international order through Payop, from the International tab: the country and
 * the methods, on the left of the pay step.
 *
 * Every number is the server's. The customer picks a country and a method; the list, each
 * method's fee from the fee sheet and the total come back from the API -- the order's price
 * without the 2.5% card fee, which a Payop payment never carries, plus that fee -- and a
 * payment is started from the token the server sealed that price into. No amount is ever
 * sent. Nothing here marks anything paid: the customer goes to Payop's page, and the order
 * moves when Payop's confirmation reaches the server.
 *
 * The fee, the total and the button that starts the payment are the page's order summary's
 * to show (see paySummary): this reports the choice there, so the total appears once.
 *
 * Two sets of routes: checkout's, authenticated by reference and email, and a signed-in
 * owner's own ({@link ownerPayopRoutes}), used to complete the payment of an unpaid order.
 */

export interface MethodOption {
  methodId: number
  name: string
  type: string
  feeMinor: number
  feeFormatted: string
  totalMinor: number
  totalFormatted: string
  /** The server's sealed price for this method: the only thing that starts it. */
  token: string
}

export interface Options {
  currency: string
  netMinor: number
  netFormatted: string
  /** The order's lines before any payment fee: no 2.5% card fee among them. */
  lines: QuoteLine[]
  methods: MethodOption[]
  /** Why nothing can be offered ("NO_RATE", "PAYOP_UNAVAILABLE"), or null. */
  unavailable: string | null
  /** While a Payop invoice for the order can still be paid. */
  claimsBlockedUntil: string | null
}

export interface Started {
  redirectUrl: string
  invoiceId: string
  totalMinor: number
  totalFormatted: string
  payableUntil: string
}

/** Where the methods come from and where a payment is started. */
export interface PayopRoutes {
  options: (country: string) => Promise<Options>
  start: (method: MethodOption, country: string, language: string) => Promise<Started>
}

/** Checkout's routes: the order is named by its reference and the email on it. */
function guestPayopRoutes(publicRef: string, email: string): PayopRoutes {
  return {
    options: (country) => api.post<Options>('/api/v1/payments/payop/options', { order: publicRef, email, country }),
    start: (method, _country, language) => api.post<Started>('/api/v1/payments/payop/invoices', {
      order: publicRef, email, token: method.token, language,
    }),
  }
}

interface OwnerOptions {
  currency: string
  netMinor: number
  netFormatted: string
  lines: QuoteLine[]
  unavailable: string | null
  methods: {
    methodId: number; name: string; type: string; feeMinor: number; feeFormatted: string
    breakdown: { totalMinor: number; totalFormatted: string }
    token: string
  }[]
  manualBlockedUntil: string | null
}

/**
 * A signed-in owner's routes, for an order of theirs. Each method comes with the token the
 * server sealed its price into; starting a payment sends that token and nothing else.
 */
export function ownerPayopRoutes(publicRef: string): PayopRoutes {
  const base = `/api/v1/orders/${encodeURIComponent(publicRef)}/payment/payop`
  return {
    options: async (country) => {
      const o = await api.post<OwnerOptions>(`${base}/options`, { country })
      return {
        currency: o.currency,
        netMinor: o.netMinor,
        netFormatted: o.netFormatted,
        lines: o.lines ?? [],
        unavailable: o.unavailable,
        claimsBlockedUntil: o.manualBlockedUntil,
        methods: o.methods.map((m) => ({
          methodId: m.methodId, name: m.name, type: m.type, feeMinor: m.feeMinor, feeFormatted: m.feeFormatted,
          totalMinor: m.breakdown.totalMinor, totalFormatted: m.breakdown.totalFormatted, token: m.token,
        })),
      }
    },
    start: (method, _country, language) => api.post<Started>(`${base}/invoices`, { token: method.token, language }),
  }
}

/** When a limit or a time frees up, from the error's `details.retryAt`, in the customer's own time. */
export function retryTime(e: ApiError, lang: string): string {
  const at = e.details?.retryAt?.[0]
  return at ? new Date(at).toLocaleString(lang, { dateStyle: 'medium', timeStyle: 'short' }) : ''
}

export function PayopPayment({
  publicRef, email, onUnavailable, routes,
}: {
  publicRef: string
  /** The email on the order: checkout's routes only. */
  email?: string
  /** Payop is switched off: the tab goes back to saying it is coming. */
  onUnavailable: () => void
  /** Another way to reach the order than checkout's; see {@link ownerPayopRoutes}. */
  routes?: PayopRoutes
}) {
  const { t, lang } = useI18n()
  const via = useMemo(() => routes ?? guestPayopRoutes(publicRef, email ?? ''), [routes, publicRef, email])
  const [country, setCountry] = useState<string | null>(null)
  const [guessed, setGuessed] = useState(false)
  const [options, setOptions] = useState<Options | null>(null)
  const [loading, setLoading] = useState(false)
  const [failed, setFailed] = useState(false)
  const [methodId, setMethodId] = useState<number | null>(null)
  const [starting, setStarting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [reload, setReload] = useState(0)

  // A first guess at the country: Cloudflare's, then the browser's. Always changeable.
  useEffect(() => {
    let live = true
    api.get<{ country: string | null }>('/api/v1/payments/payop/country')
      .then((found) => countryCode(found.country))
      .catch(() => null)
      .then((fromIp) => {
        if (!live) return
        const guess = fromIp ?? countryFromLocale(window.navigator.language)
        setCountry((current) => current ?? guess)
        setGuessed(guess !== null)
      })
    return () => { live = false }
  }, [])

  useEffect(() => {
    if (!country) return
    let live = true
    setLoading(true)
    setFailed(false)
    via.options(country)
      .then((found) => {
        if (!live) return
        setOptions(found)
        setMethodId((current) => (found.methods.some((m) => m.methodId === current) ? current : null))
      })
      .catch((e) => {
        if (!live) return
        if (e instanceof ApiError && e.status === 404) {
          onUnavailable()
          return
        }
        setFailed(true)
      })
      .finally(() => { if (live) setLoading(false) })
    return () => { live = false }
  }, [country, via, reload, onUnavailable])

  const names = useMemo(
    () => COUNTRY_CODES
      .map((code) => ({ code, name: countryName(code, lang) }))
      .sort((a, b) => a.name.localeCompare(b.name, lang)),
    [lang],
  )
  const chosen = options?.methods.find((m) => m.methodId === methodId) ?? null

  /*
   * The choice, reported to the order summary beside this step: the lines the fee is added
   * to, the chosen method's fee and total, and the way to start it. Back to the order as
   * placed when this tab is left.
   */
  const publish = usePublishPaySummary()
  const startRef = useRef<() => Promise<void>>(async () => {})
  const pay = useCallback(() => { void startRef.current() }, [])
  useEffect(() => {
    publish?.({
      kind: 'payop',
      lines: options?.lines ?? [],
      method: chosen && options && !options.unavailable ? {
        name: chosen.name, feeMinor: chosen.feeMinor, feeFormatted: chosen.feeFormatted,
        totalFormatted: chosen.totalFormatted,
      } : null,
      pay,
      paying: starting,
      error,
    })
  }, [publish, options, chosen, starting, error, pay])
  useEffect(() => () => publish?.(ORDER_SUMMARY), [publish])

  async function start() {
    if (!chosen || !country || starting) return
    setStarting(true)
    setError(null)
    try {
      const started = await via.start(chosen, country, lang)
      // Only ever off to an https page the server named: never a value from this page.
      if (!started.redirectUrl.startsWith('https://')) {
        setError(t.order.payopStartFailed)
        return
      }
      window.location.assign(started.redirectUrl)
    } catch (e) {
      if (e instanceof ApiError && e.code === 'price_changed') {
        setError(t.order.payopPriceChanged)
        setReload((n) => n + 1)
      } else if (e instanceof ApiError && e.code === 'method_unavailable') {
        setError(t.order.payopMethodGone)
        setReload((n) => n + 1)
      } else if (e instanceof ApiError && e.code === 'payment_starting') {
        setError(t.order.payopStarting)
      } else if (e instanceof ApiError && e.code === 'payment_token_expired') {
        setError(t.completePayment.tokenExpired)
        setReload((n) => n + 1)
      } else if (e instanceof ApiError && e.code === 'payment_attempts_order') {
        setError(t.completePayment.limitOrder(retryTime(e, lang)))
      } else if (e instanceof ApiError && e.code === 'payment_attempts_account') {
        setError(t.completePayment.limitAccount(retryTime(e, lang)))
      } else if (e instanceof ApiError && e.code === 'pay_by_passed') {
        setError(t.completePayment.payByPassed)
      } else {
        setError(t.order.payopStartFailed)
      }
    } finally {
      setStarting(false)
    }
  }
  startRef.current = start

  const blockedUntil = options?.claimsBlockedUntil
    ? new Date(options.claimsBlockedUntil).toLocaleString(lang, { dateStyle: 'medium', timeStyle: 'short' })
    : null

  return (
    <div role="tabpanel" className="space-y-4">
      <div>
        <h4 className="display text-[15px] text-chalk">{t.order.payopTitle}</h4>
        <p className="mt-1.5 text-[13px] leading-relaxed text-chalk-muted">{t.order.payopIntro}</p>
      </div>

      <Field label={t.order.payopCountryLabel} hint={guessed ? t.order.payopCountryHint : undefined}>
        {(props) => (
          <Select
            {...props}
            value={country ?? ''}
            onChange={(e) => { setCountry(countryCode(e.target.value)); setError(null) }}
          >
            {!country && <option value="">{t.order.payopCountryPick}</option>}
            {names.map((c) => <option key={c.code} value={c.code}>{c.name}</option>)}
          </Select>
        )}
      </Field>

      {blockedUntil && <Alert tone="neutral">{t.order.payopOpenUntil(blockedUntil)}</Alert>}

      {country && loading && !options && (
        <p className="text-[13px] text-chalk-muted" aria-live="polite">{t.order.payopLoading}</p>
      )}

      {failed && <Alert tone="warn" title={t.order.payopUnavailable}>{t.order.payopUnavailableHint}</Alert>}

      {options && options.unavailable && (
        <Alert tone="warn" title={t.order.payopUnavailable}>{t.order.payopUnavailableHint}</Alert>
      )}

      {options && !options.unavailable && options.methods.length === 0 && country && (
        <Alert tone="neutral" title={t.order.payopNone(countryName(country, lang))}>
          {t.order.payopNoneHint}
        </Alert>
      )}

      {options && !options.unavailable && options.methods.length > 0 && (
        <div role="group" aria-label={t.order.payopMethodsLabel} className="space-y-2">
          <p className="text-[13px] font-medium text-chalk-muted">{t.order.payopMethodsLabel}</p>
          <div className="grid gap-2 sm:grid-cols-2">
            {options.methods.map((m) => (
              <SelectTile
                key={m.methodId}
                active={m.methodId === methodId}
                onSelect={() => { setMethodId(m.methodId); setError(null) }}
                label={m.name}
                sub={`${t.order.payopFeeLine}: ${m.feeFormatted}`}
              />
            ))}
          </div>
          <p className="text-[12px] leading-snug text-chalk-faint">{t.order.payopFeeNote}</p>
        </div>
      )}
    </div>
  )
}
