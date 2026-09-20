import { PageHeader } from '../../components/PageHeader'
import { useCallback, useEffect, useState } from 'react'
import { Alert, Button, Field, Input, Section, Skeleton } from '../../components/ui'
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

  const [rates, setRates] = useState<CoinRate[] | null>(null)
  const [draft, setDraft] = useState<Record<string, string>>({})
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  const load = useCallback(async () => {
    try {
      const next = await api.get<CoinRate[]>('/api/v1/admin/rates/coin-rates')
      setRates(next)
      // The draft is seeded from the server every load, so an abandoned edit never
      // survives a refresh and get saved later by accident.
      setDraft(Object.fromEntries(next.map((r) => [r.currency, r.per100k])))
      setError(null)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not load the coin rates.')
      setRates([])
    }
  }, [])

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
      <PageHeader eyebrow="Operations" title="Coin rates" intensity={0.3} />
      <Section className="rhythm-section">
        {error && <Alert tone="warn">{error}</Alert>}
        {saved && <Alert tone="ok">{saved}</Alert>}

        <Alert tone="neutral">
          Each currency is priced on its own. Nothing here is converted from the rupee
          price, so changing one leaves the other three untouched.
        </Alert>

        {!rates && <Skeleton className="mt-6 h-64 w-full" />}

        {rates && rates.length === 0 && !error && (
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
      </Section>
    </>
  )
}

/** What one step costs, and what a million costs, at the rate currently stored. */
function hintFor(rate: CoinRate): string {
  return `${rate.symbol}${rate.per10k} per 10,000 · ${rate.symbol}${perMillion(rate)} per 1,000,000`
}

function perMillion(rate: CoinRate): string {
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
