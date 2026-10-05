import { useEffect, useMemo, useState } from 'react'
import { Alert, Button, Field, Select, SelectTile } from './ui'
import { useI18n } from '../i18n'
import { ApiError, api } from '../lib/api'
import { COUNTRY_CODES, countryCode, countryFromLocale, countryName } from '../lib/countries'

/**
 * Paying an international order through Payop, from the International tab.
 *
 * Every number here is the server's. The customer picks a country and a method; the list,
 * each method's fee and the total come back from the API, and starting the payment sends
 * only the method and the total that was on screen, which the server checks against its
 * own. Nothing here marks anything paid: the customer goes to Payop's page, and the order
 * moves when Payop's confirmation reaches the server.
 */

interface MethodOption {
  methodId: number
  name: string
  type: string
  feeMinor: number
  feeFormatted: string
  totalMinor: number
  totalFormatted: string
}

interface Options {
  currency: string
  netMinor: number
  netFormatted: string
  feeLabel: string
  methods: MethodOption[]
  /** Why nothing can be offered ("NO_RATE", "PAYOP_UNAVAILABLE"), or null. */
  unavailable: string | null
  /** While a Payop invoice for the order can still be paid. */
  claimsBlockedUntil: string | null
}

interface Started {
  redirectUrl: string
  invoiceId: string
  totalMinor: number
  totalFormatted: string
  payableUntil: string
}

export function PayopPayment({
  publicRef, email, onUnavailable,
}: {
  publicRef: string
  email: string
  /** Payop is switched off: the tab goes back to saying it is coming. */
  onUnavailable: () => void
}) {
  const { t, lang } = useI18n()
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
    api.post<Options>('/api/v1/payments/payop/options', { order: publicRef, email, country })
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
  }, [country, publicRef, email, reload, onUnavailable])

  const names = useMemo(
    () => COUNTRY_CODES
      .map((code) => ({ code, name: countryName(code, lang) }))
      .sort((a, b) => a.name.localeCompare(b.name, lang)),
    [lang],
  )
  const chosen = options?.methods.find((m) => m.methodId === methodId) ?? null

  async function start() {
    if (!chosen || !country || starting) return
    setStarting(true)
    setError(null)
    try {
      const started = await api.post<Started>('/api/v1/payments/payop/invoices', {
        order: publicRef,
        email,
        methodId: chosen.methodId,
        country,
        expectedTotalMinor: chosen.totalMinor,
        language: lang,
      })
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
      } else {
        setError(t.order.payopStartFailed)
      }
    } finally {
      setStarting(false)
    }
  }

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

      {options && chosen && (
        <dl className="divide-y divide-ink-400/70 rounded-panel border border-ink-400 bg-ink-700/40 px-4">
          <div className="flex justify-between gap-4 py-2.5 text-[13px]">
            <dt className="text-chalk-muted">{t.order.payopOrderPrice}</dt>
            <dd className="tnum text-chalk">{options.netFormatted}</dd>
          </div>
          <div className="flex justify-between gap-4 py-2.5 text-[13px]">
            <dt className="text-chalk-muted">{t.order.payopFeeLine}</dt>
            <dd className="tnum text-chalk">{chosen.feeFormatted}</dd>
          </div>
          <div className="flex justify-between gap-4 py-2.5 text-[14px] font-semibold">
            <dt className="text-chalk">{t.order.payopTotal}</dt>
            <dd className="tnum text-chalk">{chosen.totalFormatted}</dd>
          </div>
        </dl>
      )}

      {error && <Alert tone="warn">{error}</Alert>}

      {chosen && (
        <>
          <Button full size="lg" loading={starting} onClick={() => void start()}>
            {t.order.payopContinue(chosen.totalFormatted)}
          </Button>
          <p className="text-[12px] leading-snug text-chalk-faint">{t.order.payopRedirectNote}</p>
        </>
      )}
    </div>
  )
}
