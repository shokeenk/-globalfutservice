import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useI18n } from '../i18n'
import { api } from '../lib/api'
import { points as fmtPoints } from '../lib/format'
import type { PointsBreakdown, PointsHistoryPage, PointsLine } from '../lib/types'
import { Button, Skeleton } from './ui'

/*
 * Where a customer's points came from and went, so "I earned 49, why can I only use 3?"
 * answers itself: the breakdown always adds up to what is available, and the history shows
 * the balance after every line. Every number comes from the server's ledger.
 */

function day(iso: string, lang: string): string {
  return new Date(iso).toLocaleDateString(lang, { day: 'numeric', month: 'short', year: 'numeric' })
}

function signed(amount: number): string {
  return amount > 0 ? `+${fmtPoints(amount)}` : `−${fmtPoints(Math.abs(amount))}`
}

/** The breakdown, points on the way with the date each lands, and the sum that ties them together. */
export function PointsBreakdownCard({ breakdown: b, capBps, className = '' }: {
  breakdown: PointsBreakdown
  /** The redemption cap, for the line under the figures; left out when not known. */
  capBps?: number
  className?: string
}) {
  const { t, lang } = useI18n()
  const p = t.pointsBook
  const rows: Array<[string, string, string?]> = [
    [p.earned, fmtPoints(b.earned)],
    [p.spent, b.spent > 0 ? `−${fmtPoints(b.spent)}` : '0'],
    [p.returned, b.returned > 0 ? `+${fmtPoints(b.returned)}` : '0'],
  ]
  if (b.removed > 0) rows.push([p.removed, `−${fmtPoints(b.removed)}`])

  const sum = [
    `${fmtPoints(b.earned)} ${p.earnedWord}`,
    `− ${fmtPoints(b.spent)} ${p.spentWord}`,
    ...(b.returned > 0 ? [`+ ${fmtPoints(b.returned)} ${p.returnedWord}`] : []),
    ...(b.removed > 0 ? [`− ${fmtPoints(b.removed)} ${p.removedWord}`] : []),
    `= ${fmtPoints(b.available)} ${p.availableWord}`,
  ].join(' ')

  return (
    <div className={className} data-testid="points-breakdown">
      <p className="text-[10.5px] font-semibold uppercase tracking-[0.16em] text-gold-400">{p.title}</p>
      <dl className="mt-3 space-y-2 text-[13px]">
        <div className="flex items-baseline justify-between gap-4">
          <dt className="text-chalk">
            {p.available}
            <span className="block text-[11.5px] text-chalk-faint">{p.availableNote}</span>
          </dt>
          <dd className="tnum text-[18px] font-semibold text-gold-400" data-testid="points-available">
            {fmtPoints(b.available)}
          </dd>
        </div>
        {rows.map(([label, value]) => (
          <div key={label} className="flex items-baseline justify-between gap-4">
            <dt className="text-chalk-muted">{label}</dt>
            <dd className="tnum font-semibold text-chalk">{value}</dd>
          </div>
        ))}
      </dl>
      <p className="mt-3 border-t border-ink-400 pt-3 text-[12px] leading-relaxed text-chalk-faint" data-testid="points-sum">
        {p.addsUp} <span className="tnum text-chalk-muted">{sum}</span>
      </p>

      {b.onTheWay > 0 && (
        <div className="mt-4 border-t border-ink-400 pt-3">
          <div className="flex items-baseline justify-between gap-4 text-[13px]">
            <p className="text-chalk">{p.onTheWay}</p>
            <p className="tnum font-semibold text-chalk" data-testid="points-on-the-way">+{fmtPoints(b.onTheWay)}</p>
          </div>
          <ul className="mt-2 space-y-1.5 text-[12px] text-chalk-muted">
            {b.pending.map((item) => (
              <li key={item.orderRef} className="flex items-baseline justify-between gap-3">
                <Link to={`/track?ref=${item.orderRef}`} className="tnum text-chalk-muted underline-offset-2 hover:underline">
                  {p.order(item.orderRef)}
                </Link>
                <span className="tnum text-right">
                  +{fmtPoints(item.points)}{' · '}
                  {item.landsAt ? p.landsOn(day(item.landsAt, lang)) : p.landsAfterDelivery}
                </span>
              </li>
            ))}
          </ul>
          <p className="mt-2 text-[11.5px] text-chalk-faint">{p.onTheWayNote}</p>
        </div>
      )}
      {capBps !== undefined && <p className="mt-3 text-[11.5px] text-chalk-faint">{p.capNote(capBps / 100)}</p>}
    </div>
  )
}

/** The customer's own breakdown, loaded from their account. Nothing while it loads or if it fails. */
export function MyPointsBreakdown({ capBps, className }: { capBps?: number; className?: string }) {
  const [breakdown, setBreakdown] = useState<PointsBreakdown | null>(null)
  useEffect(() => {
    api.get<PointsBreakdown>('/api/v1/account/points').then(setBreakdown).catch(() => setBreakdown(null))
  }, [])
  return breakdown ? <PointsBreakdownCard breakdown={breakdown} capBps={capBps} className={className} /> : null
}

const PAGE_SIZE = 20

/** The points history, newest first, a page at a time, with the balance after each line. */
export function PointsHistory({ id }: { id?: string }) {
  const { t, lang } = useI18n()
  const p = t.pointsBook
  const [page, setPage] = useState(0)
  const [data, setData] = useState<PointsHistoryPage | null>(null)
  const [failed, setFailed] = useState(false)

  const load = useCallback((which: number) => {
    setFailed(false)
    api.get<PointsHistoryPage>(`/api/v1/account/points/history?page=${which}&size=${PAGE_SIZE}`)
      .then(setData)
      .catch(() => setFailed(true))
  }, [])

  useEffect(() => load(page), [load, page])

  // Arriving from "See your points history": bring the list into view once it is there.
  const arrived = data !== null
  useEffect(() => {
    if (arrived && id && window.location.hash === `#${id}`) document.getElementById(id)?.scrollIntoView?.()
  }, [arrived, id])

  const pages = data ? Math.max(1, Math.ceil(data.total / data.size)) : 1

  return (
    <section id={id} aria-labelledby="points-history-title" className="scroll-mt-28">
      <h2 id="points-history-title" className="stamp mb-5">{p.historyTitle}</h2>
      {failed ? (
        <div className="surface px-5 py-4 text-[13px] text-chalk-muted" role="alert">
          {p.loadFailed}{' '}
          <Button variant="ghost" size="sm" onClick={() => load(page)}>{p.retry}</Button>
        </div>
      ) : !data ? (
        <Skeleton className="h-32 w-full" />
      ) : data.total === 0 ? (
        <p className="surface px-5 py-4 text-[13px] text-chalk-muted">{p.noHistory}</p>
      ) : (
        <div className="surface overflow-hidden">
          <ul className="divide-y divide-ink-400" data-testid="points-history">
            {data.entries.map((line) => <HistoryLine key={line.id} line={line} lang={lang} />)}
          </ul>
          {pages > 1 && (
            <div className="flex items-center justify-between gap-3 border-t border-ink-400 px-5 py-3 text-[12.5px]">
              <Button variant="ghost" size="sm" disabled={page === 0} onClick={() => setPage(page - 1)}>
                {p.newer}
              </Button>
              <span className="tnum text-chalk-faint">{p.pageOf(page + 1, pages)}</span>
              <Button variant="ghost" size="sm" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>
                {p.older}
              </Button>
            </div>
          )}
        </div>
      )}
    </section>
  )
}

function HistoryLine({ line, lang }: { line: PointsLine; lang: string }) {
  const p = useI18n().t.pointsBook
  return (
    <li className="flex items-start justify-between gap-4 px-5 py-3.5" data-testid="points-line">
      <div className="min-w-0">
        <p className="text-[13px] text-chalk">
          {p.kinds[line.kind]}
          {line.orderRef && (
            <>
              {' · '}
              <Link to={`/track?ref=${line.orderRef}`} className="tnum text-chalk-muted underline-offset-2 hover:underline">
                {line.orderRef}
              </Link>
            </>
          )}
        </p>
        <p className="text-[11.5px] text-chalk-faint">{day(line.at, lang)}</p>
      </div>
      <div className="shrink-0 text-right">
        {/* A sign, not only a colour (WCAG 1.4.1). */}
        <p className={`tnum text-[14px] font-semibold ${line.amount >= 0 ? 'text-gold-400' : 'text-chalk-muted'}`}>
          {signed(line.amount)}
        </p>
        <p className="tnum text-[11.5px] text-chalk-faint">
          {p.colBalance} {fmtPoints(line.balanceAfter)}
        </p>
      </div>
    </li>
  )
}
