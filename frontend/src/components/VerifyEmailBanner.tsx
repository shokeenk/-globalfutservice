import { useState } from 'react'
import { useI18n } from '../i18n'
import { api, ApiError } from '../lib/api'
import { useAuth } from '../state/AuthContext'
import { Button } from './ui'

/**
 * "Please verify your email", under the header, for a signed-in customer whose address is
 * not verified yet. The account works meanwhile; this only asks. Resend sends a new link,
 * limited on the server to a few an hour.
 */
export function VerifyEmailBanner() {
  const { t, lang } = useI18n()
  const v = t.verifyEmail
  const { account, refreshAccount } = useAuth()
  const [state, setState] = useState<'idle' | 'sending' | 'sent' | 'tooMany' | 'failed'>('idle')

  if (!account || account.role !== 'CUSTOMER' || account.emailVerified !== false) return null

  async function resend() {
    setState('sending')
    try {
      const r = await api.post<{ verified: boolean }>('/api/v1/auth/email/resend', { language: lang })
      if (r.verified) {
        // Verified in another tab meanwhile: the banner goes.
        await refreshAccount()
        return
      }
      setState('sent')
    } catch (e) {
      setState(e instanceof ApiError && e.status === 429 ? 'tooMany' : 'failed')
    }
  }

  return (
    <div role="status" data-testid="verify-email-banner"
         className="relative z-20 border-b border-gold-400/30 bg-gold-400/[0.08]">
      <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-3 px-4 py-2.5 sm:px-6">
        <p className="text-[13px] leading-snug text-chalk">
          <strong className="font-semibold">{v.bannerTitle}.</strong>{' '}
          <span className="text-chalk-muted">{v.bannerBody(account.email)}</span>
          {state === 'sent' && <span className="ml-1 text-ok">{v.resent}</span>}
          {state === 'tooMany' && <span className="ml-1 text-warn">{v.resendTooMany}</span>}
          {state === 'failed' && <span className="ml-1 text-warn">{v.resendFailed}</span>}
        </p>
        <Button type="button" variant="secondary" size="sm" loading={state === 'sending'}
                disabled={state === 'sent'} onClick={() => { void resend() }}>
          {v.resend}
        </Button>
      </div>
    </div>
  )
}
