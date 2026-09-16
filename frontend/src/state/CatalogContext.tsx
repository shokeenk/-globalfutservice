import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { api } from '../lib/api'
import type { Catalog, Policy } from '../lib/types'

type CatalogState = {
  catalog: Catalog | null
  policy: Policy | null
  currency: string
  setCurrency: (currency: string) => void
  loading: boolean
  error: string | null
}

const CatalogContext = createContext<CatalogState | null>(null)

/** Where the chosen currency is kept, alongside the language choice. */
const STORAGE_KEY = 'gfs.currency'

/**
 * The currency to fall back to: the one the business prices in, and the one the loyalty
 * scheme settles in.
 */
const FALLBACK_CURRENCY = 'INR'

/**
 * The currency a returning visitor left in, or the default.
 *
 * <p>Language has been remembered since it existed; currency was not, so somebody
 * browsing in pounds was shown rupees again by the next page they opened. Only the shape
 * is checked here -- whether the code is still one the server sells in cannot be known
 * until the catalogue answers, and that is handled below.
 */
function initialCurrency(): string {
  if (typeof window === 'undefined') return FALLBACK_CURRENCY
  try {
    const stored = window.localStorage.getItem(STORAGE_KEY)
    return stored && /^[A-Z]{3}$/.test(stored) ? stored : FALLBACK_CURRENCY
  } catch {
    // Private browsing denies storage. The default is a correct answer, not a failure.
    return FALLBACK_CURRENCY
  }
}

/**
 * Prices and policy, loaded once and shared.
 *
 * The policy half is what lets the marketing copy stay honest: the rewards page,
 * the guarantee badge and the fee note all read their numbers from here, so
 * changing the earn rate in configuration changes the sentence on the page. The
 * reference site this business is modelled on advertises "5% cashback on every
 * order" on its homepage while its own rewards page tops out at a 5% discount tier
 * — the copy drifted from the engine and nobody noticed. This is the fix.
 */
export function CatalogProvider({ children }: { children: ReactNode }) {
  const [catalog, setCatalog] = useState<Catalog | null>(null)
  const [policy, setPolicy] = useState<Policy | null>(null)
  const [currency, setCurrencyState] = useState<string>(initialCurrency)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    setLoading(true)
    setError(null)
    ;(async () => {
      try {
        const [nextCatalog, nextPolicy] = await Promise.all([
          api.get<Catalog>(`/api/v1/catalog?currency=${currency}`, controller.signal),
          api.get<Policy>('/api/v1/catalog/policy', controller.signal),
        ])
        /*
         * A remembered currency can outlive the offer behind it -- a price list is
         * withdrawn, or the allow-list changes -- and the switcher only lists what the
         * server says is available. Rather than leave the page priced in something that
         * is no longer sold, drop back to the default, which re-runs this effect.
         */
        if (!nextCatalog.availableCurrencies.includes(currency)) {
          setCurrency(FALLBACK_CURRENCY)
          return
        }
        setCatalog(nextCatalog)
        setPolicy(nextPolicy)
      } catch (e) {
        if (!controller.signal.aborted) {
          // Same again for the harder failure: a stored currency the server refuses
          // outright must not strand a returning visitor on an error page.
          if (currency !== FALLBACK_CURRENCY) {
            setCurrency(FALLBACK_CURRENCY)
            return
          }
          setError('We could not load prices. Please refresh, or contact support if it persists.')
        }
      } finally {
        if (!controller.signal.aborted) setLoading(false)
      }
    })()
    return () => controller.abort()
  }, [currency])

  const setCurrency = useCallback((next: string) => {
    setCurrencyState(next)
    try {
      window.localStorage.setItem(STORAGE_KEY, next)
    } catch {
      // The choice still applies to this session; losing it on reload beats a crash.
    }
  }, [])

  const value = useMemo(
    () => ({ catalog, policy, currency, setCurrency, loading, error }),
    [catalog, policy, currency, setCurrency, loading, error],
  )

  return <CatalogContext.Provider value={value}>{children}</CatalogContext.Provider>
}

export function useCatalog(): CatalogState {
  const context = useContext(CatalogContext)
  if (!context) throw new Error('useCatalog must be used inside CatalogProvider')
  return context
}
