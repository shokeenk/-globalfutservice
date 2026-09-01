import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { api, setAccessToken } from '../lib/api'
import type { Account } from '../lib/types'

type AuthState = {
  account: Account | null
  loading: boolean
  login: (email: string, password: string) => Promise<void>
  register: (input: RegisterInput) => Promise<void>
  logout: () => Promise<void>
  refreshAccount: () => Promise<void>
}

export type RegisterInput = {
  email: string
  password: string
  displayName?: string
  phone?: string
  acceptedTerms: boolean
}

type TokenResponse = { accessToken: string; expiresInSeconds: number; account: Account }

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [account, setAccount] = useState<Account | null>(null)
  const [loading, setLoading] = useState(true)

  const adopt = useCallback((response: TokenResponse) => {
    setAccessToken(response.accessToken)
    setAccount(response.account)
  }, [])

  /**
   * On first paint, try to turn the HttpOnly refresh cookie into a session.
   *
   * A failure here is the normal case for a visitor who has never signed in, so it
   * is swallowed rather than surfaced — an anonymous browser should see the
   * storefront, not an error.
   */
  useEffect(() => {
    let cancelled = false
    ;(async () => {
      try {
        const response = await api.post<TokenResponse>('/api/v1/auth/refresh')
        if (!cancelled) adopt(response)
      } catch {
        if (!cancelled) {
          setAccessToken(null)
          setAccount(null)
        }
      } finally {
        if (!cancelled) setLoading(false)
      }
    })()
    return () => {
      cancelled = true
    }
  }, [adopt])

  const login = useCallback(
    async (email: string, password: string) => {
      adopt(await api.post<TokenResponse>('/api/v1/auth/login', { email, password }))
    },
    [adopt],
  )

  const register = useCallback(
    async (input: RegisterInput) => {
      adopt(await api.post<TokenResponse>('/api/v1/auth/register', input))
    },
    [adopt],
  )

  const logout = useCallback(async () => {
    try {
      await api.post('/api/v1/auth/logout')
    } finally {
      // Clear locally whatever the server said. A logout that appears to fail and
      // leaves the user apparently signed in is worse than a stale server session.
      setAccessToken(null)
      setAccount(null)
    }
  }, [])

  const refreshAccount = useCallback(async () => {
    try {
      setAccount(await api.get<Account>('/api/v1/auth/me'))
    } catch {
      /* not signed in */
    }
  }, [])

  const value = useMemo(
    () => ({ account, loading, login, register, logout, refreshAccount }),
    [account, loading, login, register, logout, refreshAccount],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthState {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth must be used inside AuthProvider')
  return context
}
