import { useEffect, useState } from 'react'
import { api } from './api'

/**
 * What the sign-up and sign-in forms start with, from the server: whether the marketing box
 * starts ticked (an admin setting, off by default) and the Cloudflare Turnstile site key --
 * null while the human check is not set up, and then no widget is shown.
 */
export interface SignupConfig {
  prefillMarketingConsent: boolean
  turnstileSiteKey: string | null
}

const OFF: SignupConfig = { prefillMarketingConsent: false, turnstileSiteKey: null }

let cached: Promise<SignupConfig> | null = null

function load(): Promise<SignupConfig> {
  if (!cached) {
    cached = api.get<SignupConfig>('/api/v1/auth/signup-config')
      .then((c) => ({ prefillMarketingConsent: c.prefillMarketingConsent === true, turnstileSiteKey: c.turnstileSiteKey ?? null }))
      // Unreachable: the form works as it did before -- unticked, no widget. The server
      // still decides whether a check was needed.
      .catch(() => { cached = null; return OFF })
  }
  return cached
}

/** Null until the answer is in. */
export function useSignupConfig(): SignupConfig | null {
  const [config, setConfig] = useState<SignupConfig | null>(null)
  useEffect(() => {
    let live = true
    void load().then((c) => { if (live) setConfig(c) })
    return () => { live = false }
  }, [])
  return config
}

/** For tests: forget the cached answer. */
export function resetSignupConfigForTests(): void {
  cached = null
}
