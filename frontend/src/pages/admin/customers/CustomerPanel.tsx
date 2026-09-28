import { useCallback, useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import {
  LuArrowRight, LuClipboardList, LuCoins, LuGraduationCap, LuMail, LuPencil, LuTrophy, LuX,
} from 'react-icons/lu'
import { SiDiscord } from 'react-icons/si'
import type { IconType } from 'react-icons'
import { ApiError, api } from '../../../lib/api'
import type { AdminCustomer, AdminCustomerDetail } from '../../../lib/types'
import { OrderStatusBadge, StatusBadge, TONE_CLASSES } from '../ui/Badge'
import { buttonClasses } from '../ui/controls'
import { dateAndTime, shortDate } from '../ui/format'
import { PlatformMark } from '../ui/PlatformMark'
import { PLATFORM_LABEL, type Tone } from '../ui/status'
import { SendEmail } from './SendEmail'
import { Avatar, CUSTOMER_STATUS, spentText } from './shared'

const SKU_ICON: Record<string, [IconType, Tone]> = {
  TRADING_SERVICE: [LuCoins, 'amber'],
  BOOST_CHAMPS: [LuTrophy, 'red'],
  BOOST_RIVALS: [LuTrophy, 'violet'],
  COACHING: [LuGraduationCap, 'blue'],
}

/**
 * One customer, beside the table on a wide screen and over it on a narrow one.
 *
 * <p>Everything here is read from the server when it opens, so it is never a stale copy
 * of the row that was clicked. What a customer spent appears only when the server sends
 * it, which it does for admins. The name is the one thing that can be changed, by an
 * admin, on an account: a guest has no account to rename.
 */
export function CustomerPanel({
  customerKey, isAdmin, onClose, onRenamed,
}: {
  customerKey: string
  isAdmin: boolean
  onClose: () => void
  onRenamed: () => void
}) {
  const [detail, setDetail] = useState<AdminCustomerDetail | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [writing, setWriting] = useState(false)
  const navigate = useNavigate()
  const panel = useRef<HTMLElement>(null)
  const closeButton = useRef<HTMLButtonElement>(null)

  const load = useCallback(async () => {
    setError(null)
    try {
      setDetail(await api.get<AdminCustomerDetail>(`/api/v1/admin/customers/${encodeURIComponent(customerKey)}`))
    } catch (e) {
      setDetail(null)
      setError(e instanceof ApiError && e.status === 404 ? 'That customer could not be found.' : 'Could not load this customer.')
    }
  }, [customerKey])

  useEffect(() => { void load() }, [load])

  // Focus moves in when a different customer opens; Escape closes. Below 1280px the panel
  // covers the page, so Tab is kept inside it there, as in any dialog.
  useEffect(() => { closeButton.current?.focus() }, [customerKey])
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      // A dialog opened from here (Send Email) handles its own keys.
      if (document.querySelector('[role="dialog"][aria-modal="true"]')) return
      if (event.key === 'Escape') {
        event.preventDefault()
        onClose()
        return
      }
      if (event.key !== 'Tab' || !window.matchMedia?.('(max-width: 1279px)').matches) return
      const items = Array.from(panel.current?.querySelectorAll<HTMLElement>(
        'a[href], button:not([disabled]), input') ?? [])
      if (items.length === 0) return
      const first = items[0]!
      const last = items[items.length - 1]!
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose])

  const c = detail?.customer

  return (
    <>
      <div aria-hidden="true" onClick={onClose} className="fixed inset-0 z-40 bg-black/40 xl:hidden" />
      <aside
        ref={panel}
        aria-label="Customer details"
        className="fixed inset-y-0 right-0 z-50 w-full max-w-[400px] overflow-y-auto bg-white shadow-admin-pop
                   xl:sticky xl:top-[70px] xl:z-auto xl:max-h-[calc(100vh-84px)] xl:max-w-none xl:rounded-admin-card
                   xl:border xl:border-admin-line xl:shadow-admin-card"
      >
        <div className="flex items-start gap-3.5 border-b border-admin-line p-5">
          {c ? <Avatar name={c.name} seed={c.key} size="lg" /> : <span className="h-14 w-14 rounded-full bg-admin-grey-tint" />}
          <div className="min-w-0 flex-1 pt-1">
            <h2 className="truncate text-[17px] font-semibold text-admin-ink">{c?.name ?? 'Customer'}</h2>
            <p className="truncate text-[13px] text-admin-muted">{c?.email ?? ' '}</p>
          </div>
          {c && <StatusBadge label={CUSTOMER_STATUS[c.status].label} tone={CUSTOMER_STATUS[c.status].tone} className="mt-1.5" />}
          <button
            ref={closeButton}
            type="button"
            onClick={onClose}
            aria-label="Close customer details"
            className="grid h-8 w-8 shrink-0 place-items-center rounded-admin-control text-admin-ink hover:bg-admin-page
                       focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-admin-red"
          >
            <LuX aria-hidden="true" className="h-5 w-5" />
          </button>
        </div>

        {error ? (
          <div className="p-6 text-center">
            <p role="alert" className="text-[14px] font-semibold text-admin-ink">{error}</p>
            <button type="button" onClick={() => void load()} className={`${buttonClasses('outline', 'sm')} mt-3`}>
              Try again
            </button>
          </div>
        ) : !c || !detail ? (
          <div className="space-y-3 p-5" aria-busy="true">
            {[0, 1, 2, 3].map((i) => <span key={i} className="block h-10 animate-pulse rounded bg-admin-grey-tint" />)}
          </div>
        ) : (
          <div className="p-5">
            {/* Money is an admin's: shown only for an admin, and only if the server sent it. */}
            <dl className={`grid divide-x divide-admin-line text-center ${isAdmin && c.spent ? 'grid-cols-3' : 'grid-cols-2'}`}>
              <Figure label="Total Orders" value={c.orders.toLocaleString('en-IN')} />
              {isAdmin && c.spent && <Figure label="Total Spent" value={spentText(c.spent)} />}
              <Figure label="Joined" value={shortDate(c.joinedAt)} />
            </dl>

            <Link
              to={`/admin/orders?search=${encodeURIComponent(c.email)}`}
              className={`${buttonClasses('primary')} mt-5 w-full`}
            >
              View All Orders
              <LuArrowRight aria-hidden="true" className="h-4 w-4" />
            </Link>

            <Details customer={c} isAdmin={isAdmin} onRenamed={() => { void load(); onRenamed() }} />

            <section aria-labelledby="customer-recent" className="mt-6">
              <div className="mb-2 flex items-center justify-between">
                <h3 id="customer-recent" className="text-[15px] font-semibold text-admin-ink">Recent Orders</h3>
                {c.orders > 0 && (
                  <Link to={`/admin/orders?search=${encodeURIComponent(c.email)}`}
                    className="text-[13px] font-medium text-admin-red-text hover:underline">
                    View All
                  </Link>
                )}
              </div>
              {detail.recentOrders.length === 0 ? (
                <p className="rounded-admin-control bg-admin-page px-3.5 py-3 text-[13px] text-admin-muted">
                  No orders yet.
                </p>
              ) : (
                <ul className="divide-y divide-admin-line">
                  {detail.recentOrders.map((o) => {
                    const [Icon, tone] = SKU_ICON[o.sku] ?? [LuClipboardList, 'grey' as Tone]
                    return (
                      <li key={o.publicRef} className="flex items-start gap-3 py-3">
                        <span aria-hidden="true" className={`grid h-10 w-10 shrink-0 place-items-center rounded-[10px] ${TONE_CLASSES[tone].tile}`}>
                          <Icon className="h-5 w-5" />
                        </span>
                        <div className="min-w-0 flex-1">
                          <Link to={`/admin/orders/${o.publicRef}`} className="text-[13px] font-semibold text-admin-ink hover:underline">
                            #{o.publicRef}
                          </Link>
                          <p className="truncate text-[12.5px] text-admin-ink">{o.serviceLabel}</p>
                          <p className="text-[12px] text-admin-faint">{shortDate(o.createdAt)}</p>
                        </div>
                        <OrderStatusBadge status={o.status} />
                      </li>
                    )
                  })}
                </ul>
              )}
            </section>

            <section aria-labelledby="customer-actions" className="mt-5">
              <h3 id="customer-actions" className="mb-2 text-[15px] font-semibold text-admin-ink">Quick Actions</h3>
              <div className="grid grid-cols-2 gap-2">
                <Link to={`/admin/orders?search=${encodeURIComponent(c.email)}`} className={buttonClasses('outline')}>
                  View Orders
                </Link>
                <button type="button" onClick={() => setWriting(true)} className={buttonClasses('outline')}>
                  <LuMail aria-hidden="true" className="h-4 w-4" />
                  Send Email
                </button>
              </div>
            </section>
          </div>
        )}
      </aside>
      {writing && detail && (
        <SendEmail
          detail={detail}
          onClose={() => setWriting(false)}
          onSent={(ticket) => navigate(`/admin/support?ticket=${encodeURIComponent(ticket.ref)}`, {
            state: { notice: `Emailed ${ticket.email}. Their answer will come back to ticket ${ticket.ref}.` },
          })}
        />
      )}
    </>
  )
}

function Figure({ label, value }: { label: string; value: string }) {
  return (
    <div className="px-2">
      <dd className="whitespace-nowrap text-[15px] font-bold tabular-nums text-admin-ink">{value}</dd>
      <dt className="mt-0.5 text-[12px] text-admin-faint">{label}</dt>
    </div>
  )
}

/** Customer Information, with the admin's rename. */
function Details({ customer: c, isAdmin, onRenamed }: { customer: AdminCustomer; isAdmin: boolean; onRenamed: () => void }) {
  const [editing, setEditing] = useState(false)
  const [name, setName] = useState(c.name)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const canEdit = isAdmin && c.kind === 'ACCOUNT'

  useEffect(() => { setName(c.name); setEditing(false); setError(null) }, [c.key, c.name])

  const save = async (event: FormEvent) => {
    event.preventDefault()
    if (!name.trim() || saving) return
    setSaving(true)
    setError(null)
    try {
      await api.patch(`/api/v1/admin/customers/${encodeURIComponent(c.key)}`, { name: name.trim() })
      setEditing(false)
      onRenamed()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'The name could not be saved.')
    } finally {
      setSaving(false)
    }
  }

  const rows: Array<[string, ReactNode]> = [
    ['Name', editing ? null : c.name],
    ['Email', c.email],
    ['EA ID', c.eaHandle ?? '—'],
    ['Platform', c.platform
      ? <span className="inline-flex items-center gap-1.5"><PlatformMark platform={c.platform} decorative />{PLATFORM_LABEL[c.platform] ?? c.platform}</span>
      : '—'],
    ['Discord', c.discordConnected
      ? <span className="inline-flex items-center gap-1.5"><SiDiscord aria-hidden="true" className="h-4 w-4 text-[#5865F2]" />Connected</span>
      : 'Not connected'],
    ['Joined', dateAndTime(c.joinedAt)],
    ['Last Order', dateAndTime(c.lastOrderAt)],
  ]

  return (
    <section aria-labelledby="customer-info" className="mt-6">
      <div className="mb-2 flex items-center justify-between">
        <h3 id="customer-info" className="text-[15px] font-semibold text-admin-ink">Customer Information</h3>
        {canEdit && !editing && (
          <button type="button" onClick={() => setEditing(true)} className={buttonClasses('outline', 'sm')}>
            <LuPencil aria-hidden="true" className="h-3.5 w-3.5" />
            Edit
          </button>
        )}
      </div>
      {editing && (
        <form onSubmit={(e) => void save(e)} className="mb-3 rounded-admin-control bg-admin-page p-3">
          <label htmlFor="customer-name" className="text-[12px] font-medium text-admin-ink">Name</label>
          <input
            id="customer-name"
            value={name}
            maxLength={80}
            onChange={(e) => setName(e.target.value)}
            className="mt-1 h-9 w-full rounded-admin-control border border-admin-line bg-white px-2.5 text-[13px]
                       focus:border-admin-red focus:outline-none focus:ring-2 focus:ring-admin-red/20"
          />
          <p className="mt-1 text-[11.5px] text-admin-faint">
            Only the name can be changed. The email is how they sign in; EA ID and platform belong to each order.
          </p>
          {error && <p role="alert" className="mt-1.5 text-[12.5px] text-admin-red-ink">{error}</p>}
          <div className="mt-2.5 flex justify-end gap-2">
            <button type="button" onClick={() => { setEditing(false); setName(c.name) }} className={buttonClasses('outline', 'sm')}>
              Cancel
            </button>
            <button type="submit" disabled={!name.trim() || saving} className={buttonClasses('primary', 'sm')}>
              {saving ? 'Saving…' : 'Save'}
            </button>
          </div>
        </form>
      )}
      <dl className="divide-y divide-admin-line">
        {rows.filter(([, value]) => value !== null).map(([label, value]) => (
          <div key={label} className="flex items-baseline gap-3 py-2.5 text-[13px]">
            <dt className="w-24 shrink-0 text-admin-faint">{label}</dt>
            <dd className="min-w-0 flex-1 break-words text-admin-ink">{value}</dd>
          </div>
        ))}
      </dl>
    </section>
  )
}
