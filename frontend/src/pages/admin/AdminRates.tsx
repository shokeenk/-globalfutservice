import { useCallback, useEffect, useState } from 'react'
import { ApiError, api } from '../../lib/api'
import { useSeo } from '../../lib/seo'
import type { CoinMarketCode, CoinPricingOverview, CoinStructure } from '../../lib/types'
import { AdminPage } from './shell/AdminPage'
import { AdminButton } from './ui/controls'
import { StructureCard } from './rates/StructureCard'

const MARKETS: CoinMarketCode[] = ['PC', 'CONSOLE']

/**
 * Coin rates: what customers are charged for coins.
 *
 * <p>Two structures, side by side. PC has its own market and prices; PlayStation and Xbox
 * share one, as FUT Transfer's does. Each has its slider -- minimum, maximum, step, quick
 * picks -- and, per currency, a base price per 100,000 coins and optional volume brackets.
 * The four currencies are priced independently: nothing here converts one into another.
 *
 * <p>A save takes effect on the next quote. There is no deploy and no cache to wait out.
 *
 * <p>Admin only: the console's nav hides it from operators and its route refuses them, and
 * the API it calls refuses anyone but an admin.
 */
export default function AdminRates() {
  useSeo({ title: 'Coin rates', noindex: true })
  const [overview, setOverview] = useState<CoinPricingOverview | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoadError(null)
    try {
      setOverview(await api.get<CoinPricingOverview>('/api/v1/admin/coin-pricing'))
    } catch (e) {
      setLoadError(e instanceof ApiError ? e.message : 'Could not load the coin prices.')
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  const saved = (structure: CoinStructure) => setOverview((o) => o && ({
    ...o,
    structures: [...o.structures.filter((s) => s.market !== structure.market), structure],
  }))

  return (
    <AdminPage eyebrow="Services" title="Coin rates"
               description="What customers are charged for coins, for each platform structure.">
      <div role="note" className="mb-5 rounded-admin-control border border-[#C9D9F5] bg-admin-blue-tint px-4 py-3 text-[13px] text-admin-blue-ink">
        <p className="font-semibold">PlayStation and Xbox always use the same prices.</p>
        <p className="mt-0.5">
          They share one market, as at FUT Transfer. Customers still pick their own console: it decides where the
          coins go, not what they cost. Prices are typed per 100,000 coins; the per-1M figure is shown beside each.
        </p>
      </div>

      {loadError && (
        <div role="alert" className="rounded-admin-card border border-admin-line bg-white px-6 py-10 text-center shadow-admin-card">
          <p className="text-[14px] font-semibold text-admin-ink">The coin prices did not load</p>
          <p className="mt-1 text-[13px] text-admin-muted">{loadError} Nothing has changed.</p>
          <AdminButton size="sm" className="mt-4" onClick={() => void load()}>Try again</AdminButton>
        </div>
      )}

      {!overview && !loadError && (
        <div aria-busy="true" className="grid gap-5 lg:grid-cols-2">
          <span className="h-96 animate-pulse rounded-admin-card bg-admin-grey-tint" />
          <span className="h-96 animate-pulse rounded-admin-card bg-admin-grey-tint" />
        </div>
      )}

      {overview && (
        <div className="grid items-start gap-5 lg:grid-cols-2">
          {MARKETS.map((market) => (
            <StructureCard key={market} market={market}
                           live={overview.structures.find((s) => s.market === market) ?? null}
                           limits={overview.limits} currencies={overview.currencies} onSaved={saved} />
          ))}
        </div>
      )}
    </AdminPage>
  )
}
