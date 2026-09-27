import { useEffect, useRef, useState, type FormEvent } from 'react'
import { LuX } from 'react-icons/lu'
import { ApiError, api } from '../../../lib/api'
import type { AdminListing, AdminListingCategory, AdminListingsOverview } from '../../../lib/types'
import { buttonClasses } from '../ui/controls'
import { CURRENCY_SYMBOL, fromMajor, fromPercent, toMajor, toPercent } from './money'

const field = 'mt-1 h-10 w-full rounded-admin-control border border-admin-line bg-white px-3 text-[13.5px] '
  + 'text-admin-ink focus:border-admin-red focus:outline-none focus:ring-2 focus:ring-admin-red/20'
const readOnly = `${field} bg-admin-page text-admin-muted`

const CATEGORY_NAME: Record<string, string> = {
  BOOST_CHAMPS: 'Champs Wins', BOOST_RIVALS: 'Rivals Divisions', COACHING: 'Coaching',
}

/**
 * The right-hand panel: edit a listing, or add a boosting tier.
 *
 * <p>What can change is what the server lets change: the price in each currency the site
 * sells in, on or off, and for boosting the success rate and the Best Value tag. The title
 * is shown but not edited here, because the storefront takes it from its English, Spanish
 * and French translation files; a title typed here would change nothing a customer reads.
 *
 * <p>Saving asks first, naming what will change, because it changes what customers are
 * charged from the next order on. Orders already placed keep their price.
 */
export function ListingEditor({
  mode, category, listing, title, currencies, onClose, onSaved, onAdded,
}: {
  mode: 'edit' | 'add'
  category: AdminListingCategory
  listing: AdminListing | null
  title: string
  currencies: string[]
  onClose: () => void
  onSaved: (overview: AdminListingsOverview, message: string) => void
  onAdded: (sku: string, variant: string) => void
}) {
  const boosting = category.sku !== 'COACHING'
  const [addSku, setAddSku] = useState<string>(category.sku === 'COACHING' ? 'BOOST_CHAMPS' : category.sku)
  const [name, setName] = useState('')
  const [prices, setPrices] = useState<Record<string, string>>({})
  const [rate, setRate] = useState('')
  const [best, setBest] = useState(false)
  const [active, setActive] = useState(true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const firstField = useRef<HTMLInputElement>(null)

  useEffect(() => {
    setError(null)
    setName('')
    if (mode === 'edit' && listing) {
      setPrices(Object.fromEntries(currencies.map((c) => [c, listing.prices[c] ? toMajor(listing.prices[c]!.minor) : ''])))
      setRate(listing.successRateBps != null ? toPercent(listing.successRateBps) : '')
      setBest(listing.bestValue)
      setActive(listing.active)
    } else {
      setPrices(Object.fromEntries(currencies.map((c) => [c, ''])))
      setRate('')
      setBest(false)
      setActive(true)
    }
    firstField.current?.focus()
  }, [mode, listing, currencies])

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault()
        onClose()
      }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose])

  const parsed = () => {
    const out: Record<string, number> = {}
    for (const c of currencies) {
      const text = (prices[c] ?? '').trim()
      if (!text) continue
      const minor = fromMajor(text)
      if (minor === null) throw new Error(`The ${c} price is not a number.`)
      if (minor <= 0) throw new Error(`The ${c} price must be more than zero.`)
      out[c] = minor
    }
    const bps = rate.trim() ? fromPercent(rate) : null
    if (rate.trim() && (bps === null || bps < 1 || bps > 10000)) throw new Error('A success rate is between 0.01% and 100%.')
    return { out, bps }
  }

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (saving) return
    setError(null)
    let values: { out: Record<string, number>; bps: number | null }
    try {
      values = parsed()
    } catch (e) {
      setError((e as Error).message)
      return
    }

    if (mode === 'add') {
      if (name.trim().length < 3) {
        setError('Give the listing a name of at least 3 characters.')
        return
      }
      if (Object.keys(values.out).length === 0) {
        setError('Give it a price in at least one currency.')
        return
      }
      if (!window.confirm(`Add "${name.trim()}" to ${CATEGORY_NAME[addSku]}? It goes on sale on the boosting page straight away.`)) return
      setSaving(true)
      try {
        const added = await api.post<{ sku: string; variant: string }>('/api/v1/admin/listings', {
          sku: addSku, name: name.trim(), prices: values.out, successRateBps: values.bps,
        })
        onAdded(added.sku, added.variant)
      } catch (e) {
        setError(e instanceof ApiError ? e.message : 'The listing could not be added. Nothing has changed.')
      } finally {
        setSaving(false)
      }
      return
    }

    if (!listing) return
    const changed = currencies.filter((c) => values.out[c] !== undefined && values.out[c] !== listing.prices[c]?.minor)
    const lines = [
      ...changed.map((c) => `${c}: ${listing.prices[c]?.formatted ?? 'not sold'} → ${CURRENCY_SYMBOL[c] ?? ''}${toMajor(values.out[c]!)}`),
      ...(active !== listing.active ? [active ? 'Turned back on: it returns to the site.' : 'Turned off: it disappears from the site.'] : []),
    ]
    if (lines.length > 0 && !window.confirm(`Save changes to ${title}?\n\n${lines.join('\n')}\n\nPrices apply to orders placed from now on; orders already placed keep theirs.`)) return
    setSaving(true)
    try {
      const overview = await api.put<AdminListingsOverview>(
        `/api/v1/admin/listings/${listing.sku}/${encodeURIComponent(listing.variant)}`,
        { prices: values.out, active, successRateBps: boosting ? values.bps : null, bestValue: boosting && active && best },
      )
      onSaved(overview, `Saved ${title}.`)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'The listing could not be saved. Nothing has changed.')
    } finally {
      setSaving(false)
    }
  }

  const currentBest = category.listings.find((l) => l.bestValue && l.variant !== listing?.variant)
  const addingBoosting = mode === 'add'

  return (
    <>
      <div aria-hidden="true" onClick={onClose} className="fixed inset-0 z-40 bg-black/40 xl:hidden" />
      <aside
        aria-label={mode === 'add' ? 'Add listing' : 'Edit listing'}
        className="fixed inset-y-0 right-0 z-50 w-full max-w-[400px] overflow-y-auto bg-white shadow-admin-pop
                   xl:sticky xl:top-[70px] xl:z-auto xl:max-h-[calc(100vh-84px)] xl:max-w-none xl:rounded-admin-card
                   xl:border xl:border-admin-line xl:shadow-admin-card"
      >
        <form onSubmit={(e) => void submit(e)} className="p-5">
          <div className="mb-4 flex items-center justify-between gap-3">
            <h2 className="text-[18px] font-semibold text-admin-ink">{mode === 'add' ? 'Add Listing' : 'Edit Listing'}</h2>
            <button type="button" onClick={onClose} aria-label="Close"
              className="grid h-8 w-8 place-items-center rounded-admin-control text-admin-ink hover:bg-admin-page
                         focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-admin-red">
              <LuX aria-hidden="true" className="h-5 w-5" />
            </button>
          </div>

          {mode === 'add' ? (
            <>
              <label className="block text-[12.5px] font-medium text-admin-ink">
                Category
                <select value={addSku} onChange={(e) => setAddSku(e.target.value)} className={field}>
                  <option value="BOOST_CHAMPS">Champs Wins</option>
                  <option value="BOOST_RIVALS">Rivals Divisions</option>
                </select>
              </label>
              <label className="mt-3 block text-[12.5px] font-medium text-admin-ink">
                Title
                <input ref={firstField} value={name} onChange={(e) => setName(e.target.value)} maxLength={60}
                  placeholder="e.g. 15 wins · Elite I + 5 extra" className={field} />
              </label>
              <p className="mt-1 text-[11.5px] text-admin-faint">
                Shown in English in every language until it is added to the translation files, and on a numbered
                card until it has rank artwork.
              </p>
            </>
          ) : (
            <>
              <label className="block text-[12.5px] font-medium text-admin-ink">
                Title
                <input value={title} readOnly className={readOnly} />
              </label>
              <p className="mt-1 text-[11.5px] text-admin-faint">
                Titles come from the site&rsquo;s English, Spanish and French wording, so they are the same everywhere.
              </p>
              <div className="mt-3 grid grid-cols-2 gap-3">
                <label className="block text-[12.5px] font-medium text-admin-ink">
                  Category
                  <input value={CATEGORY_NAME[category.sku] ?? category.name} readOnly className={readOnly} />
                </label>
                <label className="block text-[12.5px] font-medium text-admin-ink">
                  Code
                  <input value={listing?.variant ?? ''} readOnly className={`${readOnly} font-mono text-[12px]`} />
                </label>
              </div>
            </>
          )}

          <fieldset className="mt-4">
            <legend className="text-[12.5px] font-medium text-admin-ink">Price</legend>
            <div className="mt-1 grid grid-cols-2 gap-3">
              {currencies.map((c, i) => (
                <label key={c} className="block text-[12px] text-admin-muted">
                  {c}
                  <span className="mt-1 flex h-10 items-center rounded-admin-control border border-admin-line bg-white
                                   focus-within:border-admin-red focus-within:ring-2 focus-within:ring-admin-red/20">
                    <span aria-hidden="true" className="pl-3 text-[13.5px] text-admin-faint">{CURRENCY_SYMBOL[c] ?? c}</span>
                    <input
                      ref={mode === 'edit' && i === 0 ? firstField : undefined}
                      inputMode="decimal"
                      value={prices[c] ?? ''}
                      onChange={(e) => setPrices((p) => ({ ...p, [c]: e.target.value }))}
                      aria-label={`Price in ${c}`}
                      className="min-w-0 flex-1 bg-transparent px-2 text-[13.5px] text-admin-ink focus:outline-none"
                    />
                  </span>
                </label>
              ))}
            </div>
          </fieldset>

          {(boosting || addingBoosting) && (
            <label className="mt-4 block text-[12.5px] font-medium text-admin-ink">
              Success Rate <span className="font-normal text-admin-faint">(Optional)</span>
              <span className="mt-1 flex h-10 items-center rounded-admin-control border border-admin-line bg-white
                               focus-within:border-admin-red focus-within:ring-2 focus-within:ring-admin-red/20">
                <input inputMode="decimal" value={rate} onChange={(e) => setRate(e.target.value)} placeholder="e.g. 92"
                  aria-label="Success rate in percent"
                  className="min-w-0 flex-1 bg-transparent px-3 text-[13.5px] text-admin-ink focus:outline-none" />
                <span aria-hidden="true" className="border-l border-admin-line px-3 text-admin-faint">%</span>
              </span>
              <span className="mt-1 block text-[11.5px] font-normal text-admin-faint">
                A figure the business stands behind; the site does not measure it. Leave empty to show none.
                {listing?.successRateSource === 'CONFIGURATION' && ' Currently from the server settings.'}
              </span>
            </label>
          )}

          {boosting && mode === 'edit' && (
            <label className="mt-4 flex items-start gap-3 text-[13px] text-admin-ink">
              <input type="checkbox" checked={best && active} disabled={!active} onChange={(e) => setBest(e.target.checked)}
                className="mt-0.5 h-4 w-4 accent-[#DB1825]" />
              <span>
                Best Value tag
                <span className="block text-[11.5px] text-admin-faint">
                  One listing per service carries it{currentBest ? `; choosing this moves it from ${currentBest.label}` : ''}.
                </span>
              </span>
            </label>
          )}

          {mode === 'edit' && (
            <div className="mt-5 flex items-start gap-3">
              <button
                type="button"
                role="switch"
                aria-checked={active}
                aria-label="Active"
                onClick={() => setActive((a) => !a)}
                className={`relative mt-0.5 h-6 w-11 shrink-0 rounded-full transition-colors focus-visible:outline-none
                            focus-visible:ring-2 focus-visible:ring-admin-red focus-visible:ring-offset-2 ${
                              active ? 'bg-admin-red' : 'bg-[#C9CDD4]'}`}
              >
                <span aria-hidden="true" className={`absolute top-0.5 h-5 w-5 rounded-full bg-white shadow transition-[left] ${
                  active ? 'left-[22px]' : 'left-0.5'}`} />
              </button>
              <span>
                <span className="block text-[13.5px] font-medium text-admin-ink">Active</span>
                <span className="block text-[12px] text-admin-faint">
                  {active ? 'This listing will be visible on the website.' : 'Hidden: customers cannot buy it. Its prices are kept.'}
                </span>
              </span>
            </div>
          )}

          {error && <p role="alert" className="mt-4 rounded-admin-control bg-admin-red-tint px-3 py-2 text-[13px] text-admin-red-ink">{error}</p>}

          <div className="mt-6 grid grid-cols-2 gap-3">
            <button type="button" onClick={onClose} className={buttonClasses('outline')}>Cancel</button>
            <button type="submit" disabled={saving} className={buttonClasses('primary')}>
              {saving ? 'Saving…' : mode === 'add' ? 'Add Listing' : 'Save Changes'}
            </button>
          </div>
        </form>
      </aside>
    </>
  )
}
