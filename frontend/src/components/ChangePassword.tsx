import { useState } from 'react'
import { useI18n } from '../i18n'
import { api, ApiError } from '../lib/api'
import { PasswordInput } from './PasswordInput'
import { Alert, Button, Field, revealFirstError } from './ui'

/**
 * Account settings: the password.
 *
 * <p>With one: current, new and the same again. The server signs out every other device
 * and emails a "your password was changed" notice; this device stays signed in.
 *
 * <p>Without one -- an account made with Google or Discord -- there is no current password
 * to ask for, so "Set a password" emails the same reset link "Forgot password?" sends.
 * Choosing it from that link proves the mailbox is theirs.
 */
export function ChangePassword({ email, hasPassword }: { email: string; hasPassword: boolean }) {
  const { t, lang } = useI18n()
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [confirm, setConfirm] = useState('')
  const [fieldErrors, setFieldErrors] = useState<{ current?: string; next?: string; confirm?: string }>({})
  const [state, setState] = useState<'idle' | 'busy' | 'done' | 'sent'>('idle')
  const [error, setError] = useState<string | null>(null)

  async function change(event: React.FormEvent) {
    event.preventDefault()
    setError(null)
    const errors: typeof fieldErrors = {}
    if (!current) errors.current = t.auth.passwordRequired
    if (!next) errors.next = t.auth.passwordRequired
    else if (next.length < 12) errors.next = t.auth.passwordTooShort
    if (!errors.next && confirm !== next) errors.confirm = t.auth.passwordsDontMatch
    setFieldErrors(errors)
    if (Object.keys(errors).length > 0) {
      revealFirstError()
      return
    }
    setState('busy')
    try {
      await api.post('/api/v1/auth/password/change',
        { currentPassword: current, newPassword: next, language: lang })
      setCurrent('')
      setNext('')
      setConfirm('')
      setState('done')
    } catch (e) {
      setState('idle')
      if (e instanceof ApiError && e.code === 'current_password_incorrect') {
        setFieldErrors({ current: t.account.currentPasswordWrong })
      } else {
        setError(e instanceof ApiError && e.status === 429 ? t.auth.tooManyAttempts : t.account.passwordFailed)
      }
    }
  }

  async function sendLink() {
    setError(null)
    setState('busy')
    try {
      await api.post('/api/v1/auth/password/set-link', { language: lang })
      setState('sent')
    } catch (e) {
      setState('idle')
      setError(e instanceof ApiError && e.status === 429 ? t.auth.tooManyAttempts : t.account.passwordFailed)
    }
  }

  return (
    <>
      <h2 className="text-[10.5px] font-semibold uppercase tracking-[0.16em] text-chalk-faint">
        {t.account.passwordTitle}
      </h2>
      {!hasPassword ? (
        <div className="mt-4 space-y-4">
          <p className="text-[12.5px] leading-relaxed text-chalk-muted">{t.account.federatedNote}</p>
          {state === 'sent' ? (
            <Alert tone="ok">{t.account.setPasswordSent(email)}</Alert>
          ) : (
            <Button type="button" variant="secondary" full size="md" loading={state === 'busy'}
                    onClick={() => { void sendLink() }}>
              {t.account.setPasswordButton}
            </Button>
          )}
          {error && <Alert tone="warn">{error}</Alert>}
        </div>
      ) : (
        <form onSubmit={change} className="mt-4 space-y-4" noValidate>
          <Field label={t.account.currentPassword} required error={fieldErrors.current}>
            {(props) => (
              <PasswordInput {...props} value={current} autoComplete="current-password"
                             onChange={(e) => setCurrent(e.target.value)} />
            )}
          </Field>
          <Field label={t.auth.newPassword} required error={fieldErrors.next} hint={t.auth.passwordHint}>
            {(props) => (
              <PasswordInput {...props} value={next} minLength={12} autoComplete="new-password"
                             onChange={(e) => setNext(e.target.value)} />
            )}
          </Field>
          <Field label={t.auth.confirmPassword} required error={fieldErrors.confirm}>
            {(props) => (
              <PasswordInput {...props} value={confirm} autoComplete="new-password"
                             onChange={(e) => setConfirm(e.target.value)} />
            )}
          </Field>
          {state === 'done' && <Alert tone="ok">{t.account.passwordChanged}</Alert>}
          {error && <Alert tone="warn">{error}</Alert>}
          <Button type="submit" variant="secondary" full size="md" loading={state === 'busy'}>
            {t.account.changePasswordButton}
          </Button>
        </form>
      )}
    </>
  )
}
