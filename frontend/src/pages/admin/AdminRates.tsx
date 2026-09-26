import { useCallback, useEffect, useState } from 'react'
import { Alert, Button, Field, Input, Skeleton } from '../../components/ui'
import { AdminPage } from './shell/AdminPage'
import { ApiError, api } from '../../lib/api'
import { dateTime } from '../../lib/format'
import { useSeo } from '../../lib/seo'
import type { CoinRate } from '../../lib/types'

/**
 * Coin base rates.
 *
 * <p>Four prices, one per market, each set directly. They are not derived from one
 * another: there is no FX feed in this system and this screen does not add one. Changing
 * the rupee price leaves the pound price exactly where it was, which is the point — the
 * owner prices each market against what that market will pay, not against a conversion.
 *
 * <p><b>Why the field is per 100,000 coins.</b> The rate card stores a price per million,
 * because that is what the pricing engine multiplies. The business talks in hundreds of
 * thousands. Rather than make the owner move a decimal point in their head against a live
 * price list, the server translates: this screen sends what is typed here and gets back
 * what was stored.
 *
 * <p>A save takes effect on the next quote. There is no deploy and no cache to wait out —
 * the storefront asks the server for a price every time the slider moves.
 */
export default function AdminRates() {
  useSeo({ title: 'Coin rates', noindex: true })

  const [rates, setRates] = useState<RateRow[] | null>(null)
  const [draft, setDraft] = useState<Record<string, string>>({})
  /* Kept apart: a failed load has nothing to show and offers a retry; a failed save
     leaves the prices on screen and says nothing was changed. */
  const [loadError, setLoadError] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  const load = useCallback(async () => {
    setLoadError(null)
    let body: unknown
    try {
      body = await api.get<unknown>('/api/v1/admin/rates/coin-rates')
    } catch (e) {
      setLoadError(e instanceof ApiError ? e.message : 'Could not load the coin rates.')
      setRates(null)
      return
    }
    const next = toRows(body)
    if (!next) {
      setLoadError('The server sent the coin rates in a form this page does not recognise, '
        + 'so nothing is shown rather than a wrong price. Nothing has been changed.')
      setRates(null)
      return
    }
    setRates(next)
    // The draft is seeded from the server every load, so an abandoned edit never
    // survives a refresh and get saved later by accident.
    setDraft(Object.fromEntries(next.map((r) => [r.currency, r.per100k])))
  }, [])

  const retry = () => {
    setRates(null)
    void load()
  }

  useEffect(() => {
    void load()
  }, [load])

  /*
   * Only the currencies whose field was actually touched are sent.
   *
   * Posting all four every time would close and reopen four rate-card rows for a change
   * to one of them, and the price history — which exists so an old order can be explained
   * — would fill with rows recording that nothing happened.
   */
  const changed = (rates ?? []).filter((r) => {
    const next = (draft[r.currency] ?? '').trim()
    return next !== '' && !sameNumber(next, r.per100k)
  })

  const save = async () => {
    if (changed.length === 0) return
    setSaving(true)
    setError(null)
    setSaved(null)
    try {
      const updated = await api.post<CoinRate[]>('/api/v1/admin/rates/coin-rates', {
        rates: changed.map((r) => ({
          currency: r.currency,
          per100k: (draft[r.currency] ?? '').trim(),
        })),
      })
      const only = updated.length === 1 ? updated[0] : null
      setSaved(
        only
          ? `${only.currency} rate updated. It applies to the next quote.`
          : `${updated.length} rates updated. They apply to the next quote.`,
      )
      await load()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not save. Nothing was changed.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <>
      <AdminPage
        eyebrow="Services"
        title="Coin rates"
        description="What customers are charged for coins."
      >
        {error && <Alert tone="warn">{error}</Alert>}
        {saved && <Alert tone="ok">{saved}</Alert>}

        <Alert tone="neutral">
          Each currency is priced on its own. Nothing here is converted from the rupee
          price, so changing one leaves the other three untouched.
        </Alert>

        {!rates && !loadError && <Skeleton className="mt-6 h-64 w-full" />}

        {loadError && (
          <div className="mt-6 space-y-3">
            <Alert tone="warn" title="The coin rates did not load">{loadError}</Alert>
            <Button variant="secondary" onClick={retry}>Try again</Button>
          </div>
        )}

        {rates && rates.length === 0 && (
          <Alert tone="warn">
            No live coin rates were found. Check that the currency is enabled and that the
            rate card has a row for it.
          </Alert>
        )}

        {rates && rates.length > 0 && (
          <>
            <div className="surface mt-6 divide-y divide-ink-400">
              {rates.map((rate) => (
                <div key={rate.currency} className="p-5 sm:p-6">
                  <Field
                    label={`${rate.currency} — price per 100,000 coins`}
                    hint={hintFor(rate)}
                  >
                    {(props) => (
                      <div className="flex items-center gap-3">
                        <span
                          aria-hidden="true"
                          className="text-chalk-muted tabular-nums"
                        >
                          {rate.symbol}
                        </span>
                        <Input
                          {...props}
                          type="text"
                          inputMode="decimal"
                          className="max-w-[12rem] tabular-nums"
                          value={draft[rate.currency] ?? ''}
                          onChange={(e) =>
                            setDraft((d) => ({ ...d, [rate.currency]: e.target.value }))
                          }
                        />
                      </div>
                    )}
                  </Field>

                  <p className="mt-2 text-[12.5px] text-chalk-faint">
                    In use since {dateTime(rate.validFrom)}.
                    {' '}
                    {/*
                      Stated where the price is set, not in a runbook nobody opens. At
                      EUR 14.55 a 10,000-coin step is EUR 1.455, and half a cent cannot be
                      charged: the engine computes the whole order exactly and rounds once
                      at the total, so consecutive steps differ by a cent more or less.
                      The charge is never more than half a cent from the exact figure and
                      the error does not accumulate — but the owner should know before a
                      customer asks.
                    */}
                    {!rate.stepIsWholeMinorUnit && (
                      <span className="text-amber-300">
                        A 10,000-coin step is {rate.symbol}{rate.per10k} here, which is not
                        a whole {rate.currency === 'INR' ? 'paisa' : 'cent'}. Prices stay
                        exact — the total is rounded once — but steps on the slider will
                        differ by one either way. Set a price ending in a whole number of
                        cents per 10,000 to avoid it.
                      </span>
                    )}
                  </p>
                </div>
              ))}
            </div>

            <div className="mt-6 flex flex-wrap items-center gap-4">
              <Button
                variant="primary"
                onClick={() => void save()}
                disabled={saving || changed.length === 0}
              >
                {saving ? 'Saving…' : 'Save rates'}
              </Button>
              <span className="text-[13px] text-chalk-faint">
                {changed.length === 0
                  ? 'No changes to save.'
                  : `${changed.length} ${changed.length === 1 ? 'rate' : 'rates'} changed.`}
              </span>
            </div>

            <p className="mt-6 text-[12.5px] text-chalk-faint">
              Saving closes the current price and opens a new one — the old row is kept, so
              an order placed against yesterday&rsquo;s price can still be explained. Every
              change is recorded against the admin who made it.
            </p>
          </>
        )}
      </AdminPage>
    </>
  )
}

/** One currency's rate as this page works with it: prices as the text the inputs hold. */
export interface RateRow {
  currency: string
  symbol: string
  perMillionMinor: number
  per100k: string
  per10k: string
  stepIsWholeMinorUnit: boolean
  validFrom: string | null
}

/**
 * The server's coin rates, in the shape this page works with, or null if they are not
 * in a shape it recognises.
 *
 * <p><b>Why the prices are converted here.</b> `per100k` and `per10k` are Java
 * BigDecimals, which Jackson writes as JSON numbers -- `"per100k":1.6E+3` in the
 * response, exponent and all. The page's type said they were strings, and the render
 * called `.trim()` on the draft seeded from them: with any live rate at all, that threw
 * "trim is not a function" and the console's error boundary replaced the whole page. So
 * the conversion happens once, here, and nothing below depends on which of the two the
 * server sends.
 *
 * <p>A body that is not a list of rows, or a row without a currency or a price, is refused
 * rather than guessed at: a price screen showing a wrong number is worse than one that
 * says it could not load.
 */
export function toRows(body: unknown): RateRow[] | null {
  if (!Array.isArray(body)) return null
  const rows: RateRow[] = []
  for (const raw of body as CoinRate[]) {
    if (!raw || typeof raw.currency !== 'string') return null
    const per100k = decimalText(raw.per100k)
    if (per100k === null) return null
    rows.push({
      currency: raw.currency,
      symbol: typeof raw.symbol === 'string' ? raw.symbol : '',
      perMillionMinor: Number(raw.perMillionMinor),
      per100k,
      per10k: decimalText(raw.per10k) ?? '',
      stepIsWholeMinorUnit: raw.stepIsWholeMinorUnit !== false,
      validFrom: typeof raw.validFrom === 'string' ? raw.validFrom : null,
    })
  }
  return rows
}

/** A price as plain decimal text, from a JSON number or string; null if it is neither. */
function decimalText(value: unknown): string | null {
  if (typeof value === 'number' && Number.isFinite(value)) return String(value)
  if (typeof value === 'string' && value.trim() !== '' && Number.isFinite(Number(value))) {
    return String(Number(value))
  }
  return null
}

/** What one step costs, and what a million costs, at the rate currently stored. */
function hintFor(rate: RateRow): string {
  return `${rate.symbol}${rate.per10k} per 10,000 · ${rate.symbol}${perMillion(rate)} per 1,000,000`
}

function perMillion(rate: RateRow): string {
  const value = rate.perMillionMinor / 100
  return value.toFixed(2)
}

/**
 * Whether two typed prices mean the same number.
 *
 * <p>String comparison would treat "16.70" and "16.7" as a change and write a history row
 * for a save that moved nothing.
 */
function sameNumber(a: string, b: string): boolean {
  const x = Number(a)
  const y = Number(b)
  if (Number.isNaN(x) || Number.isNaN(y)) return a.trim() === b.trim()
  return x === y
}
