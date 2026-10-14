import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react'
import { LuCopy, LuEye, LuImage, LuTrash2, LuUpload, LuX } from 'react-icons/lu'
import { ListingCard } from '../../../components/ListingCard'
import { ApiError, api } from '../../../lib/api'
import { listingImageSrc } from '../../../lib/listings'
import { dateTime } from '../../../lib/format'
import { formatMinor } from '../../../lib/money'
import type { AdminServiceListing, ListingSku } from '../../../lib/types'
import { buttonClasses } from '../ui/controls'
import { CURRENCY_SYMBOL, fromMajor, toMajor } from './money'

const field = 'mt-1 h-10 w-full rounded-admin-control border border-admin-line bg-white px-3 text-[13.5px] '
  + 'text-admin-ink focus:border-admin-red focus:outline-none focus:ring-2 focus:ring-admin-red/20'
const area = 'mt-1 w-full rounded-admin-control border border-admin-line bg-white px-3 py-2 text-[13.5px] '
  + 'leading-relaxed text-admin-ink focus:border-admin-red focus:outline-none focus:ring-2 focus:ring-admin-red/20'
const label = 'block text-[12.5px] font-medium text-admin-ink'
const hint = 'mt-1 block text-[11.5px] font-normal text-admin-faint'

/** The categories an admin can add to. Champs and Rivals open their own, simpler form. */
export const ADD_CATEGORIES: { sku: string; name: string }[] = [
  { sku: 'BOOST_CHAMPS', name: 'Champs Wins' },
  { sku: 'BOOST_RIVALS', name: 'Rivals Divisions' },
  { sku: 'BOOST_OBJECTIVES', name: 'Objectives' },
  { sku: 'BOOST_TOURNAMENTS', name: 'Tournaments' },
]

const MAX_BYTES = 2 * 1024 * 1024
const PNG_SIGNATURE = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]

/** Whether a file starts with the PNG signature, read from its bytes rather than its name. */
async function looksLikePng(file: File): Promise<boolean> {
  const head = await new Promise<ArrayBuffer>((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(reader.result as ArrayBuffer)
    reader.onerror = () => reject(reader.error)
    reader.readAsArrayBuffer(file.slice(0, 8))
  })
  const bytes = new Uint8Array(head)
  return PNG_SIGNATURE.every((b, i) => bytes[i] === b)
}

/** A data: URL for the preview: the site's policy lets data: images in, not blob: ones. */
function dataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result))
    reader.onerror = () => reject(reader.error)
    reader.readAsDataURL(file)
  })
}

interface Form {
  sku: ListingSku
  title: string
  subtitle: string
  overview: string
  requirements: string
  requiredGames: string
  estimatedTime: string
  schedulingNote: string
  reward: string
  customerInstructions: string
  pricingType: 'FIXED' | 'PER_HOUR'
  maxHours: string
  showImage: boolean
  active: boolean
  displayOrder: string
  prices: Record<string, string>
}

function blank(sku: ListingSku, currencies: string[]): Form {
  return {
    sku, title: '', subtitle: '', overview: '', requirements: '', requiredGames: '', estimatedTime: '',
    schedulingNote: '', reward: '', customerInstructions: '', pricingType: 'FIXED', maxHours: '50',
    showImage: true, active: true, displayOrder: '',
    prices: Object.fromEntries(currencies.map((c) => [c, ''])),
  }
}

function fromListing(l: AdminServiceListing, currencies: string[]): Form {
  return {
    sku: l.sku, title: l.title, subtitle: l.subtitle ?? '', overview: l.overview ?? '',
    requirements: l.requirements ?? '', requiredGames: l.requiredGames != null ? String(l.requiredGames) : '',
    estimatedTime: l.estimatedTime ?? '', schedulingNote: l.schedulingNote ?? '', reward: l.reward ?? '',
    customerInstructions: l.customerInstructions ?? '', pricingType: l.pricingType, maxHours: String(l.maxHours),
    showImage: l.showImage, active: l.active, displayOrder: String(l.displayOrder),
    prices: Object.fromEntries(currencies.map((c) => [c, l.prices[c] ? toMajor(l.prices[c]!.minor) : ''])),
  }
}

/**
 * Add / Edit Listing for Objectives and Tournaments: everything the card and the checkout
 * show, written here and live without a deploy.
 *
 * <p>The artwork waits for Save like everything else: a PNG chosen, replaced or removed
 * here changes nothing a customer sees until the listing is saved, and Cancel undoes it.
 * Delete asks first, and when orders name the listing it recommends switching it off
 * instead; deleting it anyway hides it everywhere and keeps it for those orders.
 */
export function ServiceListingEditor({
  mode, sku, listing, currencies, onClose, onSaved, onCategory, onDeleted,
}: {
  mode: 'add' | 'edit'
  sku: ListingSku
  /** The listing being edited, as the overview had it. */
  listing: AdminServiceListing | null
  currencies: string[]
  onClose: () => void
  /** After any save, duplicate or upload: the listing to show, and what to say. */
  onSaved: (saved: AdminServiceListing, message: string) => void
  /** A category picked that this form does not edit: Champs or Rivals. */
  onCategory: (sku: string) => void
  onDeleted: (message: string) => void
}) {
  const [form, setForm] = useState<Form>(() => (listing ? fromListing(listing, currencies) : blank(sku, currencies)))
  const [full, setFull] = useState<AdminServiceListing | null>(listing)
  const [file, setFile] = useState<File | null>(null)
  const [preview, setPreview] = useState<string | null>(null)
  const [removeImage, setRemoveImage] = useState(false)
  const [showPreview, setShowPreview] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const firstField = useRef<HTMLInputElement>(null)
  const fileInput = useRef<HTMLInputElement>(null)

  useEffect(() => {
    setForm(listing ? fromListing(listing, currencies) : blank(sku, currencies))
    setFull(listing)
    setFile(null)
    setPreview(null)
    setRemoveImage(false)
    setError(null)
    firstField.current?.focus()
    // The listing's history and its creator: only on its own read.
    if (mode === 'edit' && listing) {
      let live = true
      api.get<AdminServiceListing>(`/api/v1/admin/service-listings/${listing.id}`)
        .then((found) => { if (live) setFull(found) })
        .catch(() => { /* the form still works without the history */ })
      return () => { live = false }
    }
    return undefined
  }, [mode, listing, sku, currencies])

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

  const set = <K extends keyof Form>(key: K, value: Form[K]) => setForm((f) => ({ ...f, [key]: value }))
  const hourly = form.pricingType === 'PER_HOUR'
  const imageShown = removeImage ? null : preview ?? listingImageSrc(full?.image?.url ?? listing?.image?.url)

  async function choose(chosen: File | undefined) {
    if (!chosen) return
    setError(null)
    if (chosen.size > MAX_BYTES) {
      setError('The image is larger than 2MB. Save it smaller and upload it again.')
      return
    }
    try {
      if (!(await looksLikePng(chosen))) {
        setError('That file is not a PNG image. Upload a .png file.')
        return
      }
      setPreview(await dataUrl(chosen))
    } catch {
      setError('That file could not be read.')
      return
    }
    setFile(chosen)
    setRemoveImage(false)
  }

  /** The form as the API takes it, or why it cannot be sent. */
  function body(): Record<string, unknown> {
    const title = form.title.trim()
    if (!title) throw new Error('Give the listing a title.')
    const prices: Record<string, number | null> = {}
    for (const c of currencies) {
      const text = (form.prices[c] ?? '').trim()
      if (!text) { prices[c] = null; continue }
      const minor = fromMajor(text)
      if (minor === null) throw new Error(`The ${c} price is not a number.`)
      if (minor <= 0) throw new Error(`The ${c} price must be more than zero.`)
      prices[c] = minor
    }
    if (form.active && Object.values(prices).every((p) => p == null)) {
      throw new Error('Give it a price in at least one currency before making it active.')
    }
    const whole = (text: string, min: number, max: number, message: string): number | null => {
      if (!text.trim()) return null
      const n = Number(text)
      if (!Number.isInteger(n) || n < min || n > max) throw new Error(message)
      return n
    }
    return {
      sku: form.sku,
      title,
      subtitle: form.subtitle,
      overview: form.overview,
      requirements: form.requirements,
      requiredGames: whole(form.requiredGames, 1, 1000, 'Required games is a whole number from 1 to 1,000, or empty.'),
      estimatedTime: form.estimatedTime,
      schedulingNote: form.schedulingNote,
      reward: form.reward,
      customerInstructions: form.customerInstructions,
      pricingType: form.pricingType,
      maxHours: whole(form.maxHours, 1, 1000, 'The most hours per order is a whole number from 1 to 1,000.') ?? 50,
      showImage: form.showImage,
      active: form.active,
      displayOrder: whole(form.displayOrder, 0, 9999, 'Display order is a whole number from 0 to 9,999.'),
      prices,
    }
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (saving) return
    setError(null)
    let payload: Record<string, unknown>
    try {
      payload = body()
    } catch (e) {
      setError((e as Error).message)
      return
    }
    if (mode === 'edit' && listing && listing.active) {
      const changed = currencies.filter((c) => (payload.prices as Record<string, number | null>)[c]
        !== (listing.prices[c]?.minor ?? null))
      if (changed.length > 0 && !window.confirm(`Change the prices of ${listing.title}?\n\n${changed.map((c) => `${c}: ${
        listing.prices[c]?.formatted ?? 'not sold'} → ${(payload.prices as Record<string, number | null>)[c] == null
        ? 'not sold' : `${CURRENCY_SYMBOL[c] ?? ''}${toMajor((payload.prices as Record<string, number>)[c]!)}`}`).join('\n')}\n\n`
        + 'Prices apply to orders placed from now on; orders already placed keep theirs.')) return
    }
    setSaving(true)
    try {
      let saved = mode === 'add'
        ? await api.post<AdminServiceListing>('/api/v1/admin/service-listings', payload)
        : await api.put<AdminServiceListing>(`/api/v1/admin/service-listings/${listing!.id}`, payload)
      if (file) {
        const upload = new FormData()
        upload.append('file', file)
        saved = await api.upload<AdminServiceListing>(`/api/v1/admin/service-listings/${saved.id}/image`, upload)
      } else if (removeImage && saved.image) {
        saved = await api.del<AdminServiceListing>(`/api/v1/admin/service-listings/${saved.id}/image`)
      }
      onSaved(saved, mode === 'add'
        ? `Added ${saved.title}.${saved.active ? ' It is on the website now.' : ' It is saved as inactive.'}`
        : `Saved ${saved.title}.`)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'The listing could not be saved. Nothing has changed.')
    } finally {
      setSaving(false)
    }
  }

  async function duplicate() {
    if (!listing || saving) return
    setSaving(true)
    setError(null)
    try {
      const copy = await api.post<AdminServiceListing>(`/api/v1/admin/service-listings/${listing.id}/duplicate`)
      onSaved(copy, `Copied as an inactive draft: ${copy.title}. Switch it on when it is ready.`)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'The listing could not be copied.')
    } finally {
      setSaving(false)
    }
  }

  async function remove() {
    if (!listing || saving) return
    setError(null)
    let orders = 0
    try {
      orders = (await api.get<{ orders: number }>(`/api/v1/admin/service-listings/${listing.id}/references`)).orders
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not check the orders for this listing. Nothing has changed.')
      return
    }
    const question = orders > 0
      ? `${orders} order${orders === 1 ? '' : 's'} ${orders === 1 ? 'was' : 'were'} placed for "${listing.title}".\n\n`
        + 'We recommend deactivating it instead: switch Active off and save. It disappears from the website '
        + 'and stays here to switch back on.\n\nDelete it anyway? It will be hidden everywhere and kept for '
        + 'those orders\' history. Their details do not change.'
      : `Delete "${listing.title}"?\n\nIt disappears from the website and from this page.`
    if (!window.confirm(question)) return
    setSaving(true)
    try {
      await api.del(`/api/v1/admin/service-listings/${listing.id}`)
      onDeleted(`Deleted ${listing.title}.`)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'The listing could not be deleted. Nothing has changed.')
      setSaving(false)
    }
  }

  const previewPrice = (() => {
    const c = currencies.find((code) => (form.prices[code] ?? '').trim()) ?? null
    if (!c) return '—'
    const minor = fromMajor(form.prices[c] ?? '')
    // Written as the website writes it: "₹1,200.00".
    const text = minor == null ? form.prices[c] ?? '' : formatMinor(minor, c)
    return hourly ? `${text} / hour` : text
  })()

  return (
    <>
      <div aria-hidden="true" onClick={onClose} className="fixed inset-0 z-40 bg-black/40 xl:hidden" />
      <aside
        aria-label={mode === 'add' ? 'Add listing' : 'Edit listing'}
        className="fixed inset-y-0 right-0 z-50 w-full max-w-[420px] overflow-y-auto bg-white shadow-admin-pop
                   xl:sticky xl:top-[70px] xl:z-auto xl:max-h-[calc(100vh-84px)] xl:max-w-none xl:rounded-admin-card
                   xl:border xl:border-admin-line xl:shadow-admin-card"
      >
        <form onSubmit={(e) => void submit(e)} className="p-5" noValidate>
          <div className="mb-4 flex items-center justify-between gap-2">
            <h2 className="text-[18px] font-semibold text-admin-ink">Add / Edit Listing</h2>
            <div className="flex items-center gap-1.5">
              {mode === 'edit' && (
                <button type="button" onClick={() => void duplicate()} disabled={saving}
                  className={`${buttonClasses('outline', 'sm')} gap-1.5`}>
                  <LuCopy aria-hidden="true" className="h-3.5 w-3.5" />
                  Duplicate Listing
                </button>
              )}
              <button type="button" onClick={onClose} aria-label="Close"
                className="grid h-8 w-8 place-items-center rounded-admin-control text-admin-ink hover:bg-admin-page
                           focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-admin-red">
                <LuX aria-hidden="true" className="h-5 w-5" />
              </button>
            </div>
          </div>

          <label className={label}>
            Listing Category
            <select
              value={form.sku}
              onChange={(e) => {
                const next = e.target.value
                if (next === 'BOOST_OBJECTIVES' || next === 'BOOST_TOURNAMENTS') set('sku', next)
                else onCategory(next)
              }}
              className={field}
            >
              {ADD_CATEGORIES
                .filter((c) => mode === 'add' || c.sku === 'BOOST_OBJECTIVES' || c.sku === 'BOOST_TOURNAMENTS')
                .map((c) => <option key={c.sku} value={c.sku}>{c.name}</option>)}
            </select>
          </label>

          <label className={`${label} mt-3`}>
            Title
            <input ref={firstField} value={form.title} onChange={(e) => set('title', e.target.value)} maxLength={80}
              placeholder="e.g. Claire Hutton Objective" className={field} />
          </label>
          <label className={`${label} mt-3`}>
            Subtitle
            <input value={form.subtitle} onChange={(e) => set('subtitle', e.target.value)} maxLength={120}
              placeholder="e.g. 84 CDM · Special Card" className={field} aria-describedby="listing-subtitle-hint" />
          </label>
          <span id="listing-subtitle-hint" className={hint}>Title and subtitle show on the card and in checkout.</span>

          <div className="mt-4">
            <p className={label}>Card Image</p>
            <div className="mt-1 flex items-start gap-3">
              <span className="grid h-[104px] w-[84px] shrink-0 place-items-center overflow-hidden rounded-admin-control
                               border border-admin-line bg-admin-page" data-testid="image-preview">
                {imageShown
                  ? <img src={imageShown} alt="Card image preview" className="h-full w-full object-contain" />
                  : <LuImage aria-hidden="true" className="h-6 w-6 text-admin-faint" />}
              </span>
              <div className="min-w-0">
                <div className="flex flex-wrap gap-2">
                  <button type="button" onClick={() => fileInput.current?.click()}
                    className={`${buttonClasses('outline', 'sm')} gap-1.5`}>
                    <LuUpload aria-hidden="true" className="h-3.5 w-3.5" />
                    {imageShown ? 'Replace PNG' : 'Upload PNG'}
                  </button>
                  {imageShown && (
                    <button type="button" className={buttonClasses('outline', 'sm')}
                      onClick={() => { setFile(null); setPreview(null); setRemoveImage(true) }}>
                      Remove
                    </button>
                  )}
                </div>
                <input ref={fileInput} type="file" accept="image/png" className="sr-only" aria-label="Card image PNG"
                  data-testid="image-input"
                  onChange={(e) => { void choose(e.target.files?.[0]); e.target.value = '' }} />
                <p className="mt-1.5 text-[11.5px] leading-snug text-admin-faint">
                  PNG, transparent background recommended. Recommended size: 400 × 500 px
                </p>
                <p className="text-[11.5px] text-admin-faint">2MB at most. Changes when you save.</p>
              </div>
            </div>
          </div>

          <Toggle
            on={form.showImage}
            onChange={(v) => set('showImage', v)}
            name="Show image on listing card"
            detail="Display this image in the listings grid on the website."
          />

          <fieldset className="mt-4">
            <legend className="text-[12.5px] font-medium text-admin-ink">Pricing type</legend>
            <div className="mt-1 grid grid-cols-2 gap-2">
              {(['FIXED', 'PER_HOUR'] as const).map((type) => (
                <label key={type} className={`flex h-10 cursor-pointer items-center justify-center gap-2 rounded-admin-control
                  border text-[13px] font-medium ${form.pricingType === type
                    ? 'border-admin-red bg-[#FEF1F2] text-admin-red-text' : 'border-admin-line text-admin-ink'}`}>
                  <input type="radio" name="pricing-type" value={type} checked={form.pricingType === type}
                    onChange={() => set('pricingType', type)} className="sr-only" />
                  {type === 'FIXED' ? 'Fixed price' : 'Per hour'}
                </label>
              ))}
            </div>
          </fieldset>

          <fieldset className="mt-4">
            <legend className="text-[12.5px] font-medium text-admin-ink">{hourly ? 'Price per hour' : 'Price'}</legend>
            <div className="mt-1 grid grid-cols-2 gap-3">
              {currencies.map((c) => (
                <label key={c} className="block text-[12px] text-admin-muted">
                  {c}
                  <span className="mt-1 flex h-10 items-center rounded-admin-control border border-admin-line bg-white
                                   focus-within:border-admin-red focus-within:ring-2 focus-within:ring-admin-red/20">
                    <span aria-hidden="true" className="pl-3 text-[13.5px] text-admin-faint">{CURRENCY_SYMBOL[c] ?? c}</span>
                    <input inputMode="decimal" value={form.prices[c] ?? ''} aria-label={`Price in ${c}`}
                      onChange={(e) => setForm((f) => ({ ...f, prices: { ...f.prices, [c]: e.target.value } }))}
                      className="min-w-0 flex-1 bg-transparent px-2 text-[13.5px] text-admin-ink focus:outline-none" />
                  </span>
                </label>
              ))}
            </div>
            <span className={hint}>Leave a currency empty to not sell it in that currency.</span>
          </fieldset>

          {hourly && (
            <>
              <label className={`${label} mt-3`}>
                Most hours per order
                <input inputMode="numeric" value={form.maxHours} onChange={(e) => set('maxHours', e.target.value)}
                  className={field} aria-describedby="listing-hours-hint" />
              </label>
              <span id="listing-hours-hint" className={hint}>
                Customers choose whole hours from 1 up to this. It only stops typing mistakes; 50 unless you change it.
              </span>
            </>
          )}

          <label className={`${label} mt-4`}>
            Overview <span className="font-normal text-admin-faint">(Optional)</span>
            <textarea value={form.overview} onChange={(e) => set('overview', e.target.value)} rows={3} maxLength={1000}
              className={area} aria-describedby="listing-overview-hint" />
          </label>
          <span id="listing-overview-hint" className={hint}>
            Replaces the generated text in the checkout&rsquo;s details box. Empty: &ldquo;We&rsquo;ll complete the
            required objectives on your account.&rdquo; and the reward, if one is set.
          </span>

          <label className={`${label} mt-3`}>
            Listing Details (Requirements)
            <textarea value={form.requirements} onChange={(e) => set('requirements', e.target.value)} rows={5}
              maxLength={4000} className={area} aria-describedby="listing-details-hint" placeholder={'Win 7 Squad Battles matches on Semi-Pro or higher\n'
                + 'Score a finesse goal in 3 separate matches'} />
          </label>
          <span id="listing-details-hint" className={hint}>One requirement per line. Each line is a task in checkout.</span>

          <div className="mt-3 grid grid-cols-2 gap-3">
            <label className={label}>
              Required games
              <input inputMode="numeric" value={form.requiredGames} onChange={(e) => set('requiredGames', e.target.value)}
                className={field} />
            </label>
            <label className={label}>
              Estimated completion time
              <input value={form.estimatedTime} onChange={(e) => set('estimatedTime', e.target.value)} maxLength={60}
                placeholder="e.g. 2–3 hours" className={field} />
            </label>
          </div>
          <span className={hint}>The time is always shown as an estimate. Empty fields are hidden in checkout.</span>

          <label className={`${label} mt-3`}>
            Scheduling note / average duration
            <input value={form.schedulingNote} onChange={(e) => set('schedulingNote', e.target.value)} maxLength={300}
              placeholder="e.g. Usually completed within 2–3 hours after the scheduled slot begins." className={field} />
          </label>
          <label className={`${label} mt-3`}>
            Reward received <span className="font-normal text-admin-faint">(Optional)</span>
            <input value={form.reward} onChange={(e) => set('reward', e.target.value)} maxLength={120}
              placeholder="e.g. 84 CDM Special Card" className={field} />
          </label>
          <label className={`${label} mt-3`}>
            Customer instructions <span className="font-normal text-admin-faint">(Optional)</span>
            <textarea value={form.customerInstructions} onChange={(e) => set('customerInstructions', e.target.value)}
              rows={3} maxLength={1000} className={area}
              placeholder="Any special instructions for the customer, e.g. make sure the objective is available." />
          </label>

          <Toggle
            on={form.active}
            onChange={(v) => set('active', v)}
            name="Active"
            detail={form.active ? 'This listing will be visible on the website.'
              : 'Inactive: not on the website. Orders already placed are not affected.'}
          />
          <label className={`${label} mt-3`}>
            Display order
            <input inputMode="numeric" value={form.displayOrder} onChange={(e) => set('displayOrder', e.target.value)}
              placeholder={mode === 'add' ? 'Last' : undefined} className={field} aria-describedby="listing-order-hint" />
          </label>
          <span id="listing-order-hint" className={hint}>Lower numbers show first on the website.</span>

          <div className="mt-5">
            <button type="button" onClick={() => setShowPreview((v) => !v)} aria-expanded={showPreview}
              className={`${buttonClasses('soft', 'sm')} gap-1.5`}>
              <LuEye aria-hidden="true" className="h-3.5 w-3.5" />
              {showPreview ? 'Hide preview' : 'Preview card'}
            </button>
            {showPreview && (
              <div className="mt-3 rounded-admin-card bg-[#F5F5F7] p-4" data-testid="card-preview">
                <p className="mb-2 text-[11.5px] text-admin-faint">As it will look on the website, before saving.</p>
                {/* Shown, not used: it would open the checkout of a listing that may not exist yet. */}
                <div className="pointer-events-none max-w-[300px]" {...{ inert: '' }}>
                  <ListingCard
                    to="#"
                    title={form.title.trim() || 'Title'}
                    subtitle={form.subtitle.trim() || null}
                    price={previewPrice}
                    accent="bg-brand-500"
                    artwork={form.showImage && imageShown ? { src: imageShown } : null}
                    chooseLabel="Choose"
                  />
                </div>
              </div>
            )}
          </div>

          {error && <p role="alert" className="mt-4 rounded-admin-control bg-admin-red-tint px-3 py-2 text-[13px] text-admin-red-ink">{error}</p>}

          <div className={`mt-6 grid gap-3 ${mode === 'edit' ? 'grid-cols-3' : 'grid-cols-2'}`}>
            {mode === 'edit' && (
              <button type="button" onClick={() => void remove()} disabled={saving}
                className="flex flex-col items-center justify-center rounded-admin-control border border-[#F3B9BE]
                           bg-[#FDF1F2] px-2 py-1.5 text-admin-red-text hover:bg-admin-red-tint">
                <span className="flex items-center gap-1.5 text-[13px] font-semibold">
                  <LuTrash2 aria-hidden="true" className="h-3.5 w-3.5" />
                  Delete Listing
                </span>
                <span className="text-[10.5px]">Requires confirmation</span>
              </button>
            )}
            <button type="button" onClick={onClose} className={buttonClasses('outline')}>Cancel</button>
            <button type="submit" disabled={saving} className={buttonClasses('primary')}>
              {saving ? 'Saving…' : 'Save Listing'}
            </button>
          </div>

          {mode === 'edit' && full && (
            <History listing={full} />
          )}
        </form>
      </aside>
    </>
  )
}

function Toggle({ on, onChange, name, detail }: {
  on: boolean
  onChange: (next: boolean) => void
  name: string
  detail: ReactNode
}) {
  return (
    <div className="mt-4 flex items-start gap-3">
      <button
        type="button"
        role="switch"
        aria-checked={on}
        aria-label={name}
        onClick={() => onChange(!on)}
        className={`relative mt-0.5 h-6 w-11 shrink-0 rounded-full transition-colors focus-visible:outline-none
                    focus-visible:ring-2 focus-visible:ring-admin-red focus-visible:ring-offset-2 ${
                      on ? 'bg-admin-red' : 'bg-[#C9CDD4]'}`}
      >
        <span aria-hidden="true" className={`absolute top-0.5 h-5 w-5 rounded-full bg-white shadow transition-[left] ${
          on ? 'left-[22px]' : 'left-0.5'}`} />
      </button>
      <span>
        <span className="block text-[13.5px] font-medium text-admin-ink">{name}</span>
        <span className="block text-[12px] text-admin-faint">{detail}</span>
      </span>
    </div>
  )
}

const ACTION: Record<string, string> = {
  CREATED: 'Added', UPDATED: 'Saved', DUPLICATED: 'Copied', ACTIVATED: 'Switched on', DEACTIVATED: 'Switched off',
  DELETED: 'Deleted', IMAGE_UPLOADED: 'Image uploaded', IMAGE_REPLACED: 'Image replaced', IMAGE_REMOVED: 'Image removed',
}

/** Who changed this listing and when: every change is recorded. */
function History({ listing }: { listing: AdminServiceListing }) {
  return (
    <div className="mt-5 border-t border-admin-line pt-4 text-[12px] text-admin-muted" data-testid="listing-history">
      <p>
        Listing #{listing.id} · added by {listing.createdBy}, {dateTime(listing.createdAt)} · last changed by{' '}
        {listing.updatedBy}, {dateTime(listing.updatedAt)}
      </p>
      {listing.changes.length > 0 && (
        <details className="mt-2">
          <summary className="cursor-pointer font-medium text-admin-ink">Recent changes</summary>
          <ul className="mt-2 space-y-1">
            {listing.changes.map((c, index) => (
              <li key={index}>
                {dateTime(c.at)} · {c.by}: {ACTION[c.action] ?? c.action}{c.detail ? ` (${c.detail})` : ''}
              </li>
            ))}
          </ul>
        </details>
      )}
    </div>
  )
}
