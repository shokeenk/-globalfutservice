import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { PageHeader } from '../components/PageHeader'
import { Badge, ButtonLink, EmptyState, Section, Skeleton } from '../components/ui'
import { useT } from '../i18n'
import { api } from '../lib/api'
import { dateTime, points as fmtPoints } from '../lib/format'
import { useSeo } from '../lib/seo'
import type { MyCoaching, OrderSummary, Wallet } from '../lib/types'
import { Reveal } from '../motion/Reveal'
import { useAuth } from '../state/AuthContext'
import { statusTone } from './Track'

export default function Account() {
  const t = useT()
  useSeo({ title: t.account.seoTitle, noindex: true })

  const { account } = useAuth()
  const [wallet, setWallet] = useState<Wallet | null>(null)
  const [orders, setOrders] = useState<OrderSummary[] | null>(null)
  const [coaching, setCoaching] = useState<MyCoaching | null>(null)

  useEffect(() => {
    api.get<Wallet>('/api/v1/account/wallet').then(setWallet).catch(() => setWallet(null))
    api.get<OrderSummary[]>('/api/v1/orders').then(setOrders).catch(() => setOrders([]))
    // Absent rather than empty when it fails: the card below renders nothing at all
    // for someone who has never bought coaching, which is most people.
    api.get<MyCoaching>('/api/v1/coaching/me').then(setCoaching).catch(() => setCoaching(null))
  }, [])

  return (
    <>
      <PageHeader
        eyebrow={t.account.seoTitle}
        title={
          account?.displayName
            ? t.account.greeting(account.displayName)
            : t.account.fallbackTitle
        }
        intensity={0.4}
        aside={
          /*
           * The points balance is promoted into the masthead.
           *
           * It is the single number a returning customer opens this page to see, and
           * it was previously the third card down the right-hand rail — below the
           * fold on a phone. A balance you have to scroll to find is a balance people
           * forget they have, and unspent points are the whole mechanism this
           * programme runs on.
           */
          wallet ? (
            <div className="plate ticks px-6 py-5">
              <p className="text-[10.5px] font-semibold uppercase tracking-[0.16em] text-gold-400">
                {t.account.rewardPoints}
              </p>
              <p className="tnum display mt-2 text-[clamp(2.2rem,5vw,2.8rem)] leading-none text-chalk">
                {fmtPoints(wallet.balance)}
              </p>
              <p className="mt-2 text-[12.5px] text-chalk-muted">
                {t.account.worth}{' '}
                <span className="tnum font-semibold text-gold-400">{wallet.valueFormatted}</span>{' '}
                {t.account.atCheckout}
              </p>
            </div>
          ) : undefined
        }
      />

      <Section className="rhythm-section">
        <div className="grid gap-8 lg:grid-cols-[1fr_340px] lg:items-start lg:gap-10">
          <div className="space-y-10">
            {/* ---------------------------------------------------- orders --- */}
            <section>
              <h2 className="stamp mb-5">{t.account.yourOrders}</h2>

              {!orders && <Skeleton className="h-32 w-full" />}
              {orders?.length === 0 && (
                <EmptyState title={t.account.noOrders}>{t.account.noOrdersBody}</EmptyState>
              )}

              {/*
                One ledger, not a stack of cards.

                Every order was previously its own bordered card, which is the shape
                a template reaches for and the wrong one for a list: nine cards means
                nine borders and nine shadows competing, and the eye cannot scan down
                a column of references because each row is boxed off from the next.
                Hairline-divided rows inside a single surface scan in one pass, and
                the reference column lines up.
              */}
              {orders && orders.length > 0 && (
                <div className="surface overflow-hidden">
                  <ul className="divide-y divide-ink-400">
                    {orders.map((order) => (
                      <li key={order.publicRef}>
                        <Link
                          to={`/track?ref=${order.publicRef}`}
                          className="group flex flex-wrap items-center gap-x-4 gap-y-2 px-5 py-4
                                     transition-colors duration-200 hover:bg-ink-500/40"
                        >
                          <div className="min-w-0 flex-1">
                            <p className="tnum text-[14px] font-semibold text-chalk">
                              {order.publicRef}
                            </p>
                            <p className="mt-0.5 truncate text-[12.5px] text-chalk-muted">
                              {order.serviceLabel}
                            </p>
                          </div>

                          <Badge tone={statusTone(order.status)}>
                            {order.status.replace(/_/g, ' ')}
                          </Badge>

                          <span className="tnum shrink-0 text-[14px] font-semibold text-chalk">
                            {order.totalFormatted}
                          </span>

                          <span className="w-full shrink-0 text-[11.5px] text-chalk-faint sm:w-auto sm:text-right">
                            {dateTime(order.createdAt)}
                          </span>

                          <svg
                            width="15" height="15" viewBox="0 0 24 24" fill="none"
                            stroke="currentColor" strokeWidth="2" strokeLinecap="round"
                            strokeLinejoin="round" aria-hidden="true"
                            className="hidden shrink-0 text-ink-300 transition-[transform,color]
                                       duration-300 ease-out-expo group-hover:translate-x-1
                                       group-hover:text-brand-400 sm:block"
                          >
                            <path d="M9 6l6 6-6 6" />
                          </svg>
                        </Link>
                      </li>
                    ))}
                  </ul>
                </div>
              )}
            </section>

            {/* ------------------------------------------------- statement --- */}
            {wallet && wallet.statement.length > 0 && (
              <section>
                <h2 className="stamp mb-5">{t.account.statement}</h2>
                <div className="surface overflow-hidden">
                  <ul className="divide-y divide-ink-400">
                    {wallet.statement.map((entry, index) => (
                      <li
                        key={index}
                        className="flex items-center justify-between gap-4 px-5 py-3.5"
                      >
                        <div className="min-w-0">
                          <p className="truncate text-[13px] text-chalk">
                            {entry.description ?? entry.type}
                          </p>
                          <p className="text-[11.5px] text-chalk-faint">{dateTime(entry.at)}</p>
                        </div>
                        {/*
                          A sign, not only a colour. Someone who cannot separate gold
                          from grey still reads the leading "+" — WCAG 1.4.1, and the
                          reason this ledger does not rely on hue to say "credit".
                        */}
                        <span
                          className={`tnum shrink-0 text-[14px] font-semibold ${
                            entry.amount >= 0 ? 'text-gold-400' : 'text-chalk-muted'
                          }`}
                        >
                          {entry.amount >= 0 ? '+' : ''}
                          {fmtPoints(entry.amount)}
                        </span>
                      </li>
                    ))}
                  </ul>
                </div>
              </section>
            )}
          </div>

          {/* -------------------------------------------------------- rail --- */}
          <div className="space-y-4">
            {wallet && (
              <Reveal className="plate p-5">
                <p className="text-[10.5px] font-semibold uppercase tracking-[0.16em] text-gold-400">
                  {t.account.rewardPoints}
                </p>
                <p className="mt-3 text-[12px] leading-relaxed text-chalk-faint">
                  {t.account.earnedTotal(
                    fmtPoints(wallet.lifetimeEarned),
                    wallet.maxRedemptionBps / 100,
                  )}
                </p>
              </Reveal>
            )}

            {/* Only for people who have actually bought coaching — an empty sessions
                card on every account page is clutter for the majority who never will. */}
            {coaching && (coaching.creditBalance > 0 || coaching.upcoming.length > 0) && (
              <Reveal delay={70} className="plate p-5">
                <p className="text-[10.5px] font-semibold uppercase tracking-[0.16em] text-brand-400">
                  {t.account.coaching}
                </p>
                <p className="tnum display mt-2 text-[34px] leading-none text-chalk">
                  {coaching.creditBalance}
                </p>
                <p className="mt-1.5 text-[13px] text-chalk-muted">
                  {coaching.creditBalance === 1
                    ? t.account.sessionsLeftOne
                    : t.account.sessionsLeftMany}
                </p>

                {coaching.upcoming.length > 0 && (
                  <ul className="mt-4 space-y-2 border-t border-ink-400 pt-4">
                    {coaching.upcoming.slice(0, 3).map((session) => (
                      <li key={session.ref} className="flex items-start gap-2.5 text-[12.5px] leading-relaxed">
                        <span aria-hidden="true" className="mt-[7px] h-1 w-2.5 shrink-0 bg-brand-500" />
                        <span>
                          <span className="text-chalk">
                            {new Date(session.startsAt).toLocaleString(undefined, {
                              weekday: 'short',
                              day: 'numeric',
                              month: 'short',
                              hour: '2-digit',
                              minute: '2-digit',
                            })}
                          </span>
                          <span className="text-chalk-faint"> · {session.coachName}</span>
                        </span>
                      </li>
                    ))}
                  </ul>
                )}

                {coaching.creditsExpireAt && coaching.creditBalance > 0 && (
                  <p className="mt-4 text-[12px] text-chalk-faint">
                    {t.account.useThemBy(
                      new Date(coaching.creditsExpireAt).toLocaleDateString(undefined, {
                        day: 'numeric',
                        month: 'long',
                        year: 'numeric',
                      }),
                    )}
                  </p>
                )}

                <ButtonLink to="/coaching" variant="secondary" full size="md" className="mt-5">
                  {coaching.creditBalance > 0 ? t.account.bookSession : t.account.manageSessions}
                </ButtonLink>
              </Reveal>
            )}

            <Reveal delay={140} className="plate p-5">
              <h2 className="text-[10.5px] font-semibold uppercase tracking-[0.16em] text-chalk-faint">
                {t.account.quickActions}
              </h2>
              <ButtonLink to="/order" full size="md" className="mt-4">
                {t.account.startOrder}
              </ButtonLink>
              <p className="mt-5 border-t border-ink-400 pt-4 text-[12px] leading-relaxed text-chalk-faint">
                {t.account.needChange}{' '}
                <Link to="/support" className="text-brand-400 hover:underline">
                  {t.account.contactSupport}
                </Link>{' '}
                {t.account.withReference}
              </p>
            </Reveal>
          </div>
        </div>
      </Section>
    </>
  )
}
