import { useEffect, useState } from 'react'
import { Link, Navigate, useLocation, useParams } from 'react-router-dom'
import { PageHeader } from '../components/PageHeader'
import { SupportChat } from '../components/support/SupportChat'
import { supportPath, useSupportCopy } from '../components/support/SupportCard'
import { Alert, Section, Spinner } from '../components/ui'
import { useT } from '../i18n'
import { ApiError, api } from '../lib/api'
import { useSeo } from '../lib/seo'
import type { SupportContext } from '../lib/types'
import NotFound from './NotFound'

/**
 * An order's support page: the order at the top, the live chat inside the page below it.
 *
 * <p>One page for all three kinds of order -- boosting, coins and coaching -- and only the
 * words differ. Which kind it is comes from the server's answer about the order, never
 * from the address: a coaching order opened at /orders/... is moved to /coaching/..., and
 * the other way round.
 *
 * <p>Signed-in owners only. The route asks for sign-in first and comes back here; the
 * server answers "not found" for anyone else's order and for one that does not exist,
 * and this page shows the ordinary not-found page for both, so it says nothing either way.
 */
export default function OrderSupport() {
  const t = useT()
  const s = t.orderSupport
  const { ref = '' } = useParams()
  const location = useLocation()
  const [loaded, setLoaded] = useState<{ ref: string; context: SupportContext } | null>(null)
  const [state, setState] = useState<'loading' | 'ready' | 'not-found' | 'failed'>('loading')
  // Only ever the order in the address. Going straight from one order's support page to
  // another's keeps this page mounted, and the first order's details -- still loaded for a
  // moment -- would otherwise redirect straight back to the first order.
  const context = loaded?.ref === ref ? loaded.context : null
  const copy = useSupportCopy(context?.mode ?? 'BOOSTING')
  useSeo({ title: s.eyebrow, noindex: true })

  useEffect(() => {
    let live = true
    setState('loading')
    api.get<SupportContext>(`/api/v1/orders/${encodeURIComponent(ref)}/support-context`)
      .then((found) => {
        if (!live) return
        setLoaded({ ref, context: found })
        setState('ready')
      })
      .catch((e) => {
        if (!live) return
        setState(e instanceof ApiError && e.status === 404 ? 'not-found' : 'failed')
      })
    return () => { live = false }
  }, [ref])

  if (state === 'not-found') return <NotFound />
  if (state === 'loading' || !context) {
    return state === 'failed'
      ? <Section className="rhythm-section"><Alert tone="warn">{s.pageFailed}</Alert></Section>
      : <div className="grid min-h-[40vh] place-items-center"><Spinner size={32} /></div>
  }

  const canonical = supportPath(context.mode, context.summary.reference)
  if (location.pathname !== canonical) return <Navigate to={canonical} replace />

  const summary = context.summary
  return (
    <>
      <PageHeader eyebrow={s.eyebrow} title={copy.title} lead={copy.body} />
      <Section className="rhythm-section">
        <div className="mx-auto max-w-4xl space-y-6">
          <dl className="grid gap-px overflow-hidden rounded-panel bg-ink-400 sm:grid-cols-4" data-testid="support-summary">
            <Cell label={s.summaryReference} value={`#${summary.reference}`} />
            <Cell label={s.summaryService} value={summary.service} />
            <Cell label={s.summaryPlatform} value={summary.platform ?? '—'} />
            <Cell label={s.summaryStatus} value={summary.status} />
            {context.mode === 'COINS' && summary.coins && <Cell label={s.summaryCoins} value={summary.coins} />}
            {context.mode === 'COACHING' && summary.session && (
              <Cell label={s.summarySession}
                    value={sessionText(summary.session, summary.sessionStartsAt, summary.sessionTimezone)} />
            )}
            {context.mode === 'COACHING' && summary.coach && <Cell label={s.summaryCoach} value={summary.coach} />}
          </dl>

          <SupportChat reference={summary.reference} chat={context.chat} />

          <Link to={`/track?ref=${encodeURIComponent(summary.reference)}`}
                className="inline-flex text-[12.5px] font-semibold text-brand-400 hover:underline">
            <span aria-hidden="true" className="mr-1.5">&larr;</span>{s.backToOrder}
          </Link>
        </div>
      </Section>
    </>
  )
}

/** The session's reference and when it is, in the zone it was booked in, named. */
function sessionText(session: string, startsAt?: string | null, zone?: string | null): string {
  if (!startsAt) return session
  try {
    const when = new Intl.DateTimeFormat(undefined, {
      dateStyle: 'medium', timeStyle: 'short', timeZone: zone ?? undefined, timeZoneName: 'short',
    }).format(new Date(startsAt))
    return `${session} · ${when}`
  } catch {
    return session
  }
}

function Cell({ label, value }: { label: string; value: string }) {
  return (
    <div className="bg-paper px-4 py-3">
      <dt className="stamp text-[10.5px] text-chalk-faint">{label}</dt>
      <dd className="mt-1 break-words text-[13px] font-semibold text-chalk">{value}</dd>
    </div>
  )
}
