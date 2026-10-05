import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Alert, ButtonLink, Section, Spinner } from '../components/ui'
import { useT } from '../i18n'
import { ApiError, api } from '../lib/api'
import { useSeo } from '../lib/seo'

/**
 * Where Payop sends the customer back, with our order reference and Payop's invoice ID.
 *
 * Read-only, and it has to be: anybody can open this URL with any parameters, and
 * "result=failed" or its absence is Payop's browser redirect, not proof of anything. The
 * page asks the server what it knows and says that. The order moves only when Payop's
 * own confirmation reaches the server; until then this checks again every few seconds.
 */

interface ReturnStatus {
  /** PENDING, PAID, FAILED, REVIEW or CLOSED. */
  payment: string
  order: string
  totalMinor: number
  totalFormatted: string
  method: string
}

/** How often, and for how long, to look again while the payment is pending. */
const POLL_MS = 5000
const POLL_FOR_MS = 10 * 60 * 1000

export default function PayopReturn() {
  const t = useT()
  useSeo({ title: t.payopReturn.eyebrow, noindex: true })
  const [params] = useSearchParams()
  const ref = params.get('ref') ?? ''
  const invoice = params.get('invoice') ?? ''
  const reportedFailed = params.get('result') === 'failed'

  const [status, setStatus] = useState<ReturnStatus | null>(null)
  const [notFound, setNotFound] = useState(false)
  const [failedOnce, setFailedOnce] = useState(false)

  useEffect(() => {
    if (!ref || !invoice) {
      setNotFound(true)
      return
    }
    let live = true
    let timer: number | undefined
    const startedAt = Date.now()
    const check = () => {
      api.get<ReturnStatus>(`/api/v1/payments/payop/return-status?ref=${encodeURIComponent(ref)}`
        + `&invoice=${encodeURIComponent(invoice)}`)
        .then((found) => {
          if (!live) return
          setStatus(found)
          setFailedOnce(false)
          if (found.payment === 'PENDING' && Date.now() - startedAt < POLL_FOR_MS) {
            timer = window.setTimeout(check, POLL_MS)
          }
        })
        .catch((e) => {
          if (!live) return
          if (e instanceof ApiError && e.status === 404) {
            setNotFound(true)
            return
          }
          setFailedOnce(true)
          if (Date.now() - startedAt < POLL_FOR_MS) timer = window.setTimeout(check, POLL_MS)
        })
    }
    check()
    return () => {
      live = false
      if (timer) window.clearTimeout(timer)
    }
  }, [ref, invoice])

  const view = notFound
    ? { title: t.payopReturn.notFoundTitle, body: t.payopReturn.notFoundBody, tone: 'neutral' as const }
    : !status
      ? null
      : status.payment === 'PAID'
        ? { title: t.payopReturn.paidTitle, body: t.payopReturn.paidBody, tone: 'ok' as const }
        : status.payment === 'FAILED'
          ? { title: t.payopReturn.failedTitle, body: t.payopReturn.failedBody, tone: 'warn' as const }
          : status.payment === 'REVIEW'
            ? { title: t.payopReturn.reviewTitle, body: t.payopReturn.reviewBody, tone: 'neutral' as const }
            : status.payment === 'CLOSED'
              ? { title: t.payopReturn.closedTitle, body: t.payopReturn.closedBody, tone: 'neutral' as const }
              : { title: t.payopReturn.pendingTitle, body: t.payopReturn.pendingBody, tone: 'neutral' as const }

  return (
    <Section>
      <div className="mx-auto max-w-lg space-y-5">
        <p className="stamp">{t.payopReturn.eyebrow}</p>
        {!view && (
          <p className="flex items-center gap-3 text-[14px] text-chalk-muted" aria-live="polite">
            <Spinner size={18} /> {t.payopReturn.checking}
          </p>
        )}
        {view && (
          <div aria-live="polite" className="space-y-4">
            <h1 className="display text-display-md text-chalk">{view.title}</h1>
            <p className="text-[14px] leading-relaxed text-chalk-muted">{view.body}</p>
            {status && !notFound && (
              <p className="tnum text-[14px] font-semibold text-chalk">
                {t.payopReturn.summary(status.totalFormatted, status.method)}
              </p>
            )}
            {status?.payment === 'PENDING' && reportedFailed && (
              <Alert tone="warn">{t.payopReturn.pendingFailedHint}</Alert>
            )}
          </div>
        )}
        {failedOnce && <Alert tone="warn">{t.payopReturn.loadFailed}</Alert>}
        <ButtonLink to={ref ? `/track?ref=${encodeURIComponent(ref)}` : '/track'} variant="secondary" size="md">
          {t.payopReturn.viewOrder}
        </ButtonLink>
      </div>
    </Section>
  )
}
