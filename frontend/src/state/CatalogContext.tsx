import { createContext, useContext, useEffect, useMemo, useState } from 'react'
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
  const [currency, setCurrency] = useState('INR')
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
        setCatalog(nextCatalog)
        setPolicy(nextPolicy)
      } catch (e) {
        if (!controller.signal.aborted) {
          setError('We could not load prices. Please refresh, or contact support if it persists.')
        }
      } finally {
        if (!controller.signal.aborted) setLoading(false)
      }
    })()
    return () => controller.abort()
  }, [currency])

  const value = useMemo(
    () => ({ catalog, policy, currency, setCurrency, loading, error }),
    [catalog, policy, currency, loading, error],
  )

  return <CatalogContext.Provider value={value}>{children}</CatalogContext.Provider>
}

export function useCatalog(): CatalogState {
  const context = useContext(CatalogContext)
  if (!context) throw new Error('useCatalog must be used inside CatalogProvider')
  return context
}
