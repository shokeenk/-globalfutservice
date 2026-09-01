import { useState } from 'react'
import { PageHeader } from '../components/PageHeader'
import { Reveal } from '../motion/Reveal'
import { Alert, Button, Checkbox, Field, Input, Section, Textarea } from '../components/ui'
import { useT } from '../i18n'
import { ApiError, api } from '../lib/api'
import { useSeo } from '../lib/seo'
import { useAuth } from '../state/AuthContext'

export default function Support() {
  const t = useT()
  useSeo({ title: t.support.seoTitle, description: t.support.seoDescription, noindex: true })

  const { account } = useAuth()
  const [email, setEmail] = useState(account?.email ?? '')
  const [orderRef, setOrderRef] = useState('')
  const [subject, setSubject] = useState('')
  const [message, setMessage] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const [sending, setSending] = useState(false)
  const [result, setResult] = useState<{ tone: 'ok' | 'warn'; text: string } | null>(null)

  async function submit() {
    setSending(true)
    setResult(null)
    try {
      const response = await api.post<{ ref: string; message: string }>('/api/v1/support/tickets', {
        email: email.trim(),
        orderRef: orderRef.trim() || null,
        subject: subject.trim(),
        message: message.trim(),
        confirmedNoCredentials: confirmed,
      })
      setResult({ tone: 'ok', text: response.message })
      setSubject('')
      setMessage('')
      setConfirmed(false)
    } catch (e) {
      setResult({ tone: 'warn', text: e instanceof ApiError ? e.message : t.support.sendFailed })
    } finally {
      setSending(false)
    }
  }

  return (
    <>
      <PageHeader
        eyebrow={t.support.eyebrow}
        title={t.support.title}
        lead={t.support.lead}
      />
      <Section className="rhythm-section">
      <div className="grid gap-5 lg:grid-cols-[1fr_340px] lg:items-start">
        {/*
          A real <form>, not a div with a button in it.

          Submitting with Enter, browser autofill, and the "Go" key on a phone
          keyboard all depend on the element actually being a form — none of which a
          div gives you, however correct the click handler is.
        */}
        <Reveal as="form" className="surface p-6 sm:p-7" onSubmit={(event: React.FormEvent) => {
          event.preventDefault()
          void submit()
        }}>
          <div className="space-y-4">
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label={t.support.yourEmail} required>
                {(props) => (
                  <Input {...props} type="email" value={email} autoComplete="email"
                         onChange={(e) => setEmail(e.target.value)} />
                )}
              </Field>
              <Field label={t.support.orderRef} hint={t.support.orderRefHint}>
                {(props) => (
                  <Input {...props} value={orderRef} placeholder="GFS-26-XXXXXXXX"
                         onChange={(e) => setOrderRef(e.target.value.toUpperCase())} />
                )}
              </Field>
            </div>

            <Field label={t.support.subject} required>
              {(props) => (
                <Input {...props} value={subject} maxLength={120}
                       onChange={(e) => setSubject(e.target.value)} />
              )}
            </Field>

            <Field label={t.support.message} required>
              {(props) => (
                <Textarea {...props} rows={7} value={message} maxLength={4000}
                          onChange={(e) => setMessage(e.target.value)} />
              )}
            </Field>

            {/*
              This is the only hard requirement on the form. People will paste an EA
              password into a free-text box if nothing asks them not to, and a
              support inbox is exactly where one should never end up.
            */}
            <Checkbox checked={confirmed} onChange={setConfirmed}>
              {t.support.noPassword}
              <span className="mt-1 block text-chalk-faint">{t.support.noPasswordNote}</span>
            </Checkbox>

            {result && <Alert tone={result.tone}>{result.text}</Alert>}

            <Button
              type="submit"
              full
              size="lg"
              loading={sending}
              disabled={!email || !subject || !message || !confirmed}
            >
              {t.support.send}
            </Button>
          </div>
        </Reveal>

        <div className="space-y-4">
          <Reveal delay={80} className="plate p-5">
            <h2 className="stamp mb-4">{t.support.fasterTitle}</h2>
            <ul className="space-y-3 text-[13px] leading-relaxed text-chalk-muted">
              {[t.support.faster1, t.support.faster2, t.support.faster3].map((line) => (
                <li key={line} className="flex items-start gap-2.5">
                  <span aria-hidden="true" className="mt-[7px] h-1 w-2.5 shrink-0 bg-brand-500" />
                  {line}
                </li>
              ))}
            </ul>
          </Reveal>

          {/*
            The anti-phishing notice is the one panel on this page allowed to look
            like a warning. It is the difference between a customer recognising a
            scam and handing over an account, so it gets the brand tint and a real
            border rather than sitting in the same grey box as the tips above it.
          */}
          <Reveal delay={150} className="rounded-panel border border-brand-500/30 bg-brand-500/[0.08] p-5">
            <h2 className="stamp mb-4 text-brand-400">{t.support.neverTitle}</h2>
            <p className="text-[13px] leading-relaxed text-chalk-muted">
              {t.support.neverBody}
            </p>
          </Reveal>
        </div>
      </div>
      </Section>
    </>
  )
}
