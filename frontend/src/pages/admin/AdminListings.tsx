import { useCallback, useEffect, useMemo, useState } from 'react'
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { LuArrowRight, LuChevronRight, LuGamepad2, LuGraduationCap, LuPlus, LuShield, LuTarget, LuTrophy, LuX } from 'react-icons/lu'
import type { IconType } from 'react-icons'
import { RankBadge } from '../../components/RankBadge'
import { useT } from '../../i18n'
import { api } from '../../lib/api'
import { useSeo } from '../../lib/seo'
import type { AdminListing, AdminListingCategory, AdminListingsOverview, AdminServiceListing } from '../../lib/types'
import { isListingSku, listingImageSrc } from '../../lib/listings'
import { AdminPage } from './shell/AdminPage'
import { ListingEditor } from './listings/ListingEditor'
import { ServiceListingEditor } from './listings/ServiceListingEditor'
import { AdminButton } from './ui/controls'
import { TONE_CLASSES } from './ui/Badge'
import type { Tone } from './ui/status'

const CATEGORY: Record<string, { icon: IconType; tone: Tone; description: string }> = {
  BOOST_CHAMPS: { icon: LuTrophy, tone: 'red', description: 'FUT Champions win packages, each to a Champion or Elite rank.' },
  BOOST_RIVALS: { icon: LuShield, tone: 'violet', description: 'Division Rivals climbs, one division at a time, and extra wins.' },
  COACHING: { icon: LuGraduationCap, tone: 'blue', description: 'One-to-one sessions. Session lengths are set in the coaching diary.' },
  BOOST_OBJECTIVES: { icon: LuTarget, tone: 'red', description: 'Manage player objectives and rewards shown on the website.' },
  BOOST_TOURNAMENTS: { icon: LuGamepad2, tone: 'violet', description: 'Tournament services shown on the website.' },
}

/**
 * Service Listings: what boosting, coaching, Objectives and Tournaments cost, and how they
 * are shown.
 *
 * <p>Admin only, like the coin rate card: this changes what customers are charged. A price
 * change closes the old price and opens a new one, as the rate card always has, so orders
 * already placed keep theirs. Coins are not here; they are on Coin rates.
 *
 * <p>Champs, Rivals and Coaching are tiers named in the site's translations. Objectives
 * and Tournaments are written here in full -- title, artwork, tasks, price -- by their own
 * editor.
 *
 * <p>The selected service, the open listing and the Add form live in the address.
 */
export default function AdminListings() {
  useSeo({ title: 'Service Listings', noindex: true })
  const t = useT()
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const [params, setParams] = useSearchParams()
  const adding = pathname.endsWith('/new')

  const [data, setData] = useState<AdminListingsOverview | null>(null)
  const [failed, setFailed] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      setData(await api.get<AdminListingsOverview>('/api/v1/admin/listings'))
      setFailed(false)
    } catch {
      setFailed(true)
    }
  }, [])
  useEffect(() => { void load() }, [load])

  const skuParam = params.get('service') ?? 'BOOST_CHAMPS'
  const search = (params.get('search') ?? '').trim()
  const category = data?.categories.find((c) => c.sku === skuParam) ?? data?.categories[0] ?? null
  const selectedVariant = params.get('listing')
  const selected = category?.listings.find((l) => l.variant === selectedVariant) ?? null

  const titleOf = useCallback((l: AdminListing) => {
    if (l.detail) return l.detail.title
    const table = t.catalog.variants as Record<string, string | undefined>
    return table[l.variant] ?? l.label
  }, [t])

  const select = (patch: Record<string, string | null>) => {
    const next = new URLSearchParams(params)
    for (const [k, v] of Object.entries(patch)) {
      if (v === null) next.delete(k)
      else next.set(k, v)
    }
    if (adding) navigate({ pathname: '/admin/services/listings', search: next.toString() }, { replace: true })
    else setParams(next, { replace: true })
  }
  const closeEditor = useCallback(() => {
    if (adding) navigate({ pathname: '/admin/services/listings', search: params.toString() }, { replace: true })
    else {
      const next = new URLSearchParams(params)
      next.delete('listing')
      setParams(next, { replace: true })
    }
  }, [adding, navigate, params, setParams])

  const editorOpen = Boolean(category && (adding || selected))

  return (
    <AdminPage
      eyebrow="Listings"
      title="Service Listings"
      description="Manage boosting, objectives and tournament services. Add, edit or remove listings."
      action={(
        <AdminButton variant="primary" onClick={() => navigate({ pathname: '/admin/services/listings/new', search: params.toString() })}>
          <LuPlus aria-hidden="true" className="h-4 w-4" />
          Add Listing
        </AdminButton>
      )}
    >
      {notice && (
        <div role="status" className="mb-4 flex items-start justify-between gap-3 rounded-admin-control bg-admin-green-tint px-4 py-3 text-[13px] text-admin-green-ink">
          <span>{notice}</span>
          <button type="button" onClick={() => setNotice(null)} aria-label="Dismiss"><LuX aria-hidden="true" className="h-4 w-4" /></button>
        </div>
      )}

      {failed && !data ? (
        <div role="alert" className="rounded-admin-card border border-admin-line bg-white px-6 py-14 text-center shadow-admin-card">
          <p className="text-[14px] font-semibold text-admin-ink">Could not load the listings</p>
          <p className="mt-1 text-[13px] text-admin-muted">Nothing has changed; try again.</p>
          <AdminButton size="sm" className="mt-4" onClick={() => void load()}>Try again</AdminButton>
        </div>
      ) : !data || !category ? (
        <div aria-busy="true" className="grid gap-4 lg:grid-cols-[260px_1fr]">
          <span className="h-64 animate-pulse rounded-admin-card bg-admin-grey-tint" />
          <span className="h-64 animate-pulse rounded-admin-card bg-admin-grey-tint" />
        </div>
      ) : (
        <div className={`grid items-start gap-4 lg:grid-cols-[250px_minmax(0,1fr)] ${editorOpen ? 'xl:grid-cols-[250px_minmax(0,1fr)_360px]' : ''}`}>
          <CategoryList categories={data.categories} selected={category.sku}
            onSelect={(sku) => select({ service: sku, listing: null })} />

          <ListingGrid category={category} selected={selected?.variant ?? null} titleOf={titleOf} search={search}
            onClearSearch={() => select({ search: null })} onEdit={(l) => select({ listing: l.variant })} />

          {editorOpen && isListingSku(category.sku) && (
            <ServiceListingEditor
              mode={adding ? 'add' : 'edit'}
              sku={category.sku}
              listing={adding ? null : selected?.detail ?? null}
              currencies={data.currencies}
              onClose={closeEditor}
              onCategory={(sku) => navigate({ pathname: '/admin/services/listings/new',
                search: new URLSearchParams({ service: sku }).toString() }, { replace: true })}
              onSaved={(saved: AdminServiceListing, message) => {
                setNotice(message)
                void load()
                navigate({ pathname: '/admin/services/listings', search: new URLSearchParams({
                  service: saved.sku, listing: saved.variant }).toString() }, { replace: true })
              }}
              onDeleted={(message) => {
                setNotice(message)
                void load()
                closeEditor()
              }}
            />
          )}

          {editorOpen && !isListingSku(category.sku) && (
            <ListingEditor
              mode={adding ? 'add' : 'edit'}
              category={category}
              listing={adding ? null : selected}
              title={selected ? titleOf(selected) : ''}
              currencies={data.currencies}
              onClose={closeEditor}
              onCategory={(sku) => navigate({ pathname: '/admin/services/listings/new',
                search: new URLSearchParams({ service: sku }).toString() }, { replace: true })}
              onSaved={(overview, message) => { setData(overview); setNotice(`${message} New prices apply to orders placed from now on.`) }}
              onAdded={(sku, variant) => {
                setNotice('Listing added. It is on sale on the boosting page now.')
                void load()
                navigate({ pathname: '/admin/services/listings', search: new URLSearchParams({ service: sku, listing: variant }).toString() }, { replace: true })
              }}
            />
          )}
        </div>
      )}
    </AdminPage>
  )
}

function CategoryList({
  categories, selected, onSelect,
}: {
  categories: AdminListingCategory[]
  selected: string
  onSelect: (sku: string) => void
}) {
  return (
    <nav aria-label="Services" className="overflow-hidden rounded-admin-card border border-admin-line bg-white shadow-admin-card">
      <ul>
        {categories.map((c) => {
          const meta = CATEGORY[c.sku] ?? { icon: LuTrophy, tone: 'grey' as Tone, description: '' }
          const Icon = meta.icon
          const on = c.sku === selected
          return (
            <li key={c.sku} className="border-b border-admin-line last:border-b-0">
              <button
                type="button"
                aria-current={on ? 'true' : undefined}
                onClick={() => onSelect(c.sku)}
                className={`flex w-full items-center gap-3 px-4 py-4 text-left transition-colors focus-visible:outline-none
                            focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-admin-red ${
                              on ? 'bg-[#FEF1F2]' : 'hover:bg-admin-page'}`}
              >
                <span aria-hidden="true" className={`grid h-10 w-10 shrink-0 place-items-center rounded-[10px] ${TONE_CLASSES[meta.tone].tile}`}>
                  <Icon className="h-5 w-5" />
                </span>
                <span className="min-w-0 flex-1">
                  <span className={`block text-[14px] font-semibold ${on ? 'text-admin-red-text' : 'text-admin-ink'}`}>{c.name}</span>
                  <span className="block text-[12px] text-admin-faint">
                    {c.listings.length} listing{c.listings.length === 1 ? '' : 's'}
                  </span>
                </span>
                <LuChevronRight aria-hidden="true" className="h-4 w-4 shrink-0 text-admin-faint" />
              </button>
            </li>
          )
        })}
      </ul>
    </nav>
  )
}

function ListingGrid({
  category, selected, titleOf, search, onClearSearch, onEdit,
}: {
  category: AdminListingCategory
  selected: string | null
  titleOf: (l: AdminListing) => string
  /** From the top bar: listings whose title or code contains it. */
  search: string
  onClearSearch: () => void
  onEdit: (l: AdminListing) => void
}) {
  const active = category.listings.filter((l) => l.active).length
  const meta = CATEGORY[category.sku]
  const sorted = useMemo(() => {
    const q = search.toLowerCase()
    return [...category.listings]
      .filter((l) => !q || titleOf(l).toLowerCase().includes(q) || l.variant.toLowerCase().includes(q))
      .sort((a, b) => Number(b.active) - Number(a.active) || a.sortOrder - b.sortOrder)
  }, [category.listings, search, titleOf])
  return (
    <section aria-labelledby="listing-grid" className="min-w-0 rounded-admin-card border border-admin-line bg-white p-5 shadow-admin-card">
      <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 id="listing-grid" className="text-[19px] font-bold text-admin-ink">{category.name}</h2>
          {meta && <p className="mt-0.5 text-[13px] text-admin-muted">{meta.description}</p>}
        </div>
        <span className="inline-flex items-center gap-1.5 rounded-full bg-admin-green-tint px-3 py-1 text-[12.5px] font-medium text-admin-green-ink">
          <span aria-hidden="true" className="h-1.5 w-1.5 rounded-full bg-current" />
          {active} Active
        </span>
      </div>
      {search && (
        <p className="mb-3 flex flex-wrap items-center gap-2 text-[13px] text-admin-muted">
          {sorted.length} matching &ldquo;{search}&rdquo;
          <button type="button" onClick={onClearSearch} className="font-medium text-admin-red-text underline">Clear</button>
        </p>
      )}
      {sorted.length === 0 && !search && (
        <p className="rounded-admin-card border border-dashed border-admin-line px-4 py-10 text-center text-[13px] text-admin-muted">
          No listings yet. Use Add Listing to create the first one.
        </p>
      )}
      {/*
        Listings carry artwork beside their words, so each card keeps at least 230px and the
        grid drops to fewer columns rather than squeezing a title into a sliver.
      */}
      <ul className={isListingSku(category.sku)
        ? 'grid grid-cols-[repeat(auto-fill,minmax(230px,1fr))] gap-3.5'
        : 'grid grid-cols-1 gap-3.5 sm:grid-cols-2 2xl:grid-cols-3'}>
        {sorted.map((l) => {
          if (l.detail) {
            return (
              <li key={l.variant}>
                <ServiceListingCard listing={l} detail={l.detail} on={l.variant === selected} onEdit={() => onEdit(l)} />
              </li>
            )
          }
          const on = l.variant === selected
          const inr = l.prices.INR
          return (
            <li key={l.variant}>
              <button
                type="button"
                onClick={() => onEdit(l)}
                aria-pressed={on}
                aria-label={`Edit ${titleOf(l)}${l.active ? '' : ' (hidden)'}`}
                className={`relative flex h-full min-h-[150px] w-full flex-col overflow-hidden rounded-admin-card border p-4 text-left
                            transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-admin-red ${
                              on ? 'border-admin-red ring-1 ring-admin-red' : l.bestValue ? 'border-[#F3B9BE]' : 'border-admin-line'
                            } ${l.bestValue ? 'bg-[#FFF6F7]' : 'bg-white'} ${l.active ? '' : 'opacity-60'} hover:border-[#E9A7AE]`}
              >
                <span aria-hidden="true" className={`absolute inset-x-0 top-0 h-[3px] ${category.sku === 'BOOST_CHAMPS' ? 'bg-admin-red' : category.sku === 'BOOST_RIVALS' ? 'bg-admin-violet-icon' : 'bg-admin-blue-icon'}`} />
                <span className="pointer-events-none absolute -right-1 -top-1"><RankBadge variant={l.variant} size={58} /></span>
                <span className="flex min-h-[20px] flex-wrap gap-1.5 pr-14">
                  {l.bestValue && (
                    <span className="rounded-[5px] bg-[#FDE68A] px-2 py-0.5 text-[10.5px] font-bold uppercase tracking-[0.04em] text-[#7C4A03]">
                      Best Value
                    </span>
                  )}
                  {!l.active && (
                    <span className="rounded-[5px] bg-admin-grey-tint px-2 py-0.5 text-[10.5px] font-semibold uppercase text-admin-grey-ink">
                      Hidden
                    </span>
                  )}
                </span>
                <span className="mt-1 block pr-14 text-[14px] font-semibold leading-snug text-admin-ink">{titleOf(l)}</span>
                {l.successRateBps != null && (
                  <span className="mt-2 block text-[12.5px] font-medium text-admin-green-ink">{l.successRateBps / 100}% success rate</span>
                )}
                <span className="mt-auto block pt-3 text-[20px] font-bold tabular-nums text-admin-red-text">
                  {inr?.formatted ?? Object.values(l.prices)[0]?.formatted ?? 'No price'}
                </span>
                <span className="mt-2 flex items-center gap-1.5 border-t border-admin-line pt-2.5 text-[13px] font-semibold text-admin-ink">
                  Edit
                  <LuArrowRight aria-hidden="true" className="h-4 w-4 text-admin-red" />
                </span>
              </button>
            </li>
          )
        })}
      </ul>
    </section>
  )
}

/** An Objectives or Tournaments listing in the grid: its artwork, words and price, as the website shows them. */
function ServiceListingCard({ listing, detail, on, onEdit }: {
  listing: AdminListing
  detail: AdminServiceListing
  on: boolean
  onEdit: () => void
}) {
  const price = listing.prices.INR?.formatted ?? Object.values(listing.prices)[0]?.formatted ?? 'No price'
  const image = listingImageSrc(detail.image?.url)
  return (
    <button
      type="button"
      onClick={onEdit}
      aria-pressed={on}
      aria-label={`Edit ${detail.title}${listing.active ? '' : ' (inactive)'}`}
      className={`relative flex h-full min-h-[170px] w-full flex-col overflow-hidden rounded-admin-card border bg-white p-4 text-left
                  transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-admin-red ${
                    on ? 'border-admin-red ring-1 ring-admin-red' : 'border-admin-line'} ${listing.active ? '' : 'opacity-60'}
                  hover:border-[#E9A7AE]`}
    >
      <span aria-hidden="true" className={`absolute inset-x-0 top-0 h-[3px] ${
        detail.sku === 'BOOST_TOURNAMENTS' ? 'bg-admin-violet-icon' : 'bg-admin-red'}`} />
      <span className="flex gap-3">
        <span className="min-w-0 flex-1">
          <span className="flex min-h-[20px] flex-wrap gap-1.5">
            {!listing.active && (
              <span className="rounded-[5px] bg-admin-grey-tint px-2 py-0.5 text-[10.5px] font-semibold uppercase text-admin-grey-ink">
                Inactive
              </span>
            )}
            {detail.pricingType === 'PER_HOUR' && (
              <span className="rounded-[5px] bg-[#FEF1F2] px-2 py-0.5 text-[10.5px] font-semibold uppercase text-admin-red-text">
                Per hour
              </span>
            )}
          </span>
          <span className="mt-1 block break-words text-[14px] font-semibold leading-snug text-admin-ink">{detail.title}</span>
          {detail.subtitle && <span className="mt-1 block break-words text-[12.5px] text-admin-muted">{detail.subtitle}</span>}
        </span>
        {image && (
          <span className="grid h-[80px] w-[64px] shrink-0 place-items-center">
            <img src={image} alt="" aria-hidden="true" className="h-full w-full object-contain" />
          </span>
        )}
      </span>
      <span className="mt-auto block pt-3 text-[20px] font-bold tabular-nums text-admin-red-text">
        {price}{detail.pricingType === 'PER_HOUR' && listing.prices.INR ? ' / hour' : ''}
      </span>
      <span className="mt-2 flex items-center gap-1.5 border-t border-admin-line pt-2.5 text-[13px] font-semibold text-admin-ink">
        Edit
        <LuArrowRight aria-hidden="true" className="h-4 w-4 text-admin-red" />
      </span>
    </button>
  )
}
