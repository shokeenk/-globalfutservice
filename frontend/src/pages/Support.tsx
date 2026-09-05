import { useState } from 'react'
import { PageHeader } from '../components/PageHeader'
import { Reveal } from '../motion/Reveal'
import { Alert, Button, Checkbox, Field, Input, Section, Textarea } from '../components/ui'
import { useT } from '../i18n'
import { ApiError, api } from '../lib/api'
import { BUSINESS, EMAIL_HREF, PHONE_HREF } from '../content/business'
import { useSeo } from '../lib/seo'
import { useAuth } from '../state/AuthContext'

export default function Support() {
  const t = useT()
  /*
   * Indexable, deliberately.
   *
   * This was `noindex` when it was only a support form — a page with nothing on it a
   * search engine should rank. It is now also the contact page: it carries the operating
   * entity, the registered address and a phone number, and it is linked from the footer
   * of every page as "Contact us". A contact page that tells crawlers to ignore it is
   * one a payment gateway's reviewer may not find, and one that cannot corroborate the
   * business behind the site.
   */
  useSeo({ title: t.support.seoTitle, description: t.support.seoDescription })

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
          {/*
            Who you are actually contacting, above the tips rather than below them.

            A support page offering a form and a chat widget but never saying which legal
            entity is on the other end is the shape of every scam in this market. Naming
            the operator, the registered address and a phone number a person answers is
            the cheapest trust signal available — and it is what a payment gateway's
            reviewer opens this page to find.
          */}
          {/*
            The Discord CTA is a button, not another line in a list.

            It is the channel the terms of service route safety-policy claims, coaching
            scheduling and disputes through, so on the page someone opens when something
            has gone wrong it should be the thing their eye lands on — not the fourth row
            of a definition list. Labelled as official because the one scam this market
            reliably runs is an impostor server.
          */}
          <Reveal delay={20}>
            <a
              href={BUSINESS.discordDm}
              target="_blank"
              rel="noreferrer"
              className="group/btn relative flex min-h-[52px] w-full items-center justify-center
                         gap-2.5 overflow-hidden rounded-edge bg-[#5865F2] px-4 text-body-sm
                         font-semibold text-white shadow-e2 transition-[transform,box-shadow]
                         duration-200 hover:-translate-y-0.5 hover:shadow-e3"
            >
              <svg width="19" height="19" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"
                   className="shrink-0">
                <path d="M20.3 4.4A19.8 19.8 0 0 0 15.4 3l-.24.5a18.3 18.3 0 0 1 4.3 1.4c-2-1.1-4.1-1.6-6.4-1.6-2.3 0-4.4.5-6.4 1.6A18.3 18.3 0 0 1 11 3.5L10.7 3a19.8 19.8 0 0 0-4.9 1.4C2.6 9.1 1.7 13.7 2.1 18.2a19.9 19.9 0 0 0 6 3c.5-.65.9-1.35 1.25-2.1-.7-.25-1.35-.55-1.95-.9.16-.12.32-.25.47-.38a14.2 14.2 0 0 0 12.2 0c.16.14.31.26.47.38-.62.36-1.27.66-1.96.9.36.75.78 1.45 1.25 2.1a19.8 19.8 0 0 0 6-3c.5-5.2-.85-9.75-3.5-13.8ZM8.7 15.4c-1.18 0-2.15-1.07-2.15-2.4S7.5 10.6 8.7 10.6s2.17 1.08 2.15 2.4c0 1.33-.96 2.4-2.15 2.4Zm6.6 0c-1.18 0-2.15-1.07-2.15-2.4s.95-2.4 2.15-2.4 2.17 1.08 2.15 2.4c0 1.33-.95 2.4-2.15 2.4Z" />
              </svg>
              Join our Discord
            </a>
            <p className="mt-2 text-center text-[12px] leading-relaxed text-chalk-faint">
              Our official support channel. Ask for {BUSINESS.discordName} — we will never
              contact you from any other server.
            </p>
          </Reveal>

          <Reveal delay={40} className="surface p-5">
            <h2 className="stamp mb-4">Contact details</h2>
            <p className="text-[13px] leading-relaxed text-chalk-muted">
              This website is operated by{' '}
              <strong className="text-chalk">{BUSINESS.legalName}</strong>, trading as{' '}
              {BUSINESS.tradingName}.
            </p>
            <dl className="mt-4 space-y-2.5 text-[13px]">
              <div>
                <dt className="text-chalk-faint">Registered address</dt>
                <dd className="mt-0.5 leading-relaxed text-chalk-muted">
                  {BUSINESS.registeredAddress}
                </dd>
              </div>
              <div>
                <dt className="text-chalk-faint">Mobile</dt>
                <dd className="mt-0.5">
                  <a className="text-chalk underline" href={PHONE_HREF}>{BUSINESS.phone}</a>
                </dd>
              </div>
              <div>
                <dt className="text-chalk-faint">Email</dt>
                <dd className="mt-0.5">
                  <a className="break-all text-chalk underline" href={EMAIL_HREF}>
                    {BUSINESS.email}
                  </a>
                </dd>
              </div>
              {/*
                Listed with the address and the phone number, not with the social icons.
                The terms of service route coaching scheduling, safety-policy claims and
                disputes through it, so for a customer with a problem it is a support
                channel first and a community second.
              */}
              <div>
                <dt className="text-chalk-faint">Official Discord</dt>
                <dd className="mt-0.5">
                  <a
                    className="text-chalk underline"
                    href={BUSINESS.discordDm}
                    target="_blank"
                    rel="noreferrer"
                  >
                    {BUSINESS.discordName}
                  </a>
                </dd>
              </div>
            </dl>
          </Reveal>

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
