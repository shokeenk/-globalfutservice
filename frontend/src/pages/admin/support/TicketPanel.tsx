import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { LuCopy, LuEllipsisVertical, LuExternalLink, LuLock, LuSendHorizontal, LuX } from 'react-icons/lu'
import { ApiError, api } from '../../../lib/api'
import type { AdminSupportDetail, AdminSupportMessage, SupportCategory } from '../../../lib/types'
import { Avatar } from '../customers/shared'
import { StatusBadge } from '../ui/Badge'
import { MenuItem, Popover, buttonClasses } from '../ui/controls'
import { dateAndTime, shortDateTime } from '../ui/format'
import { SUPPORT_CATEGORIES, SUPPORT_CATEGORY, TICKET_STATUS } from './shared'

type Mode = 'reply' | 'note'

/**
 * One ticket, beside the table on a wide screen and over it on a narrow one: who wrote in,
 * the whole conversation, and the box to answer in.
 *
 * <p><b>Reply</b> goes to the customer, by email and on their account if they have one,
 * and the ticket then waits for them. <b>Internal Note</b> stays with staff: the server
 * never sends notes to the customer's pages, and a note changes nothing and tells nobody.
 * The two look different in the thread so one is never mistaken for the other.
 *
 * <p>Half-written text is kept per ticket and per box while the page is open, so clicking
 * another ticket to check something does not throw a reply away.
 */
export function TicketPanel({
  ticketRef, onClose, onChanged,
}: {
  ticketRef: string
  onClose: () => void
  /** Something about the ticket changed: the list and counts need reading again. */
  onChanged: () => void
}) {
  const [detail, setDetail] = useState<AdminSupportDetail | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [busy, setBusy] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [mode, setMode] = useState<Mode>('reply')
  const drafts = useRef(new Map<string, string>())
  const [text, setText] = useState('')
  const panel = useRef<HTMLElement>(null)
  const closeButton = useRef<HTMLButtonElement>(null)
  const thread = useRef<HTMLDivElement>(null)

  const load = useCallback(async () => {
    setLoadError(null)
    try {
      setDetail(await api.get<AdminSupportDetail>(`/api/v1/admin/support/tickets/${encodeURIComponent(ticketRef)}`))
    } catch (e) {
      setDetail(null)
      setLoadError(e instanceof ApiError && e.status === 404 ? 'That ticket could not be found.' : 'Could not load this ticket.')
    }
  }, [ticketRef])

  useEffect(() => {
    setDetail(null)
    setError(null)
    setNotice(null)
    setMode('reply')
    setText(drafts.current.get(`${ticketRef}:reply`) ?? '')
    void load()
  }, [ticketRef, load])

  useEffect(() => { closeButton.current?.focus() }, [ticketRef])
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null
      if (event.key === 'Escape') {
        // Not while a menu inside is open (Escape closes that), nor from the reply box,
        // where it would throw the panel away mid-sentence.
        if (panel.current?.querySelector('[aria-expanded="true"]')) return
        if (target?.tagName === 'TEXTAREA' || target?.tagName === 'SELECT') return
        event.preventDefault()
        onClose()
        return
      }
      if (event.key !== 'Tab' || !window.matchMedia?.('(max-width: 1279px)').matches) return
      const items = Array.from(panel.current?.querySelectorAll<HTMLElement>(
        'a[href], button:not([disabled]), textarea, select') ?? [])
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

  const switchMode = (next: Mode) => {
    drafts.current.set(`${ticketRef}:${mode}`, text)
    setMode(next)
    setText(drafts.current.get(`${ticketRef}:${next}`) ?? '')
    setError(null)
  }

  const act = async (label: string, call: () => Promise<AdminSupportDetail>, done: string) => {
    setBusy(label)
    setError(null)
    setNotice(null)
    try {
      setDetail(await call())
      setNotice(done)
      onChanged()
      return true
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'That did not go through. Reload the ticket and check before trying again.')
      return false
    } finally {
      setBusy(null)
    }
  }

  const path = `/api/v1/admin/support/tickets/${encodeURIComponent(ticketRef)}`

  const send = async (event?: FormEvent) => {
    event?.preventDefault()
    if (!text.trim() || busy) return
    const note = mode === 'note'
    const ok = await act('send', () => api.post<AdminSupportDetail>(`${path}/messages`, { message: text.trim(), note }),
      note ? 'Note added. Only staff can see it.' : `Reply sent to ${detail?.email ?? 'the customer'}.`)
    if (ok) {
      drafts.current.delete(`${ticketRef}:${mode}`)
      setText('')
      requestAnimationFrame(() => thread.current?.scrollTo?.({ top: thread.current.scrollHeight }))
    }
  }

  const copyLink = async () => {
    if (!detail) return
    try {
      await navigator.clipboard.writeText(detail.customerLink)
      setNotice('Customer link copied. Anyone with it can read and answer this ticket as the customer.')
    } catch {
      setError('Could not copy the link. Your browser blocked the clipboard.')
    }
  }

  const status = detail ? TICKET_STATUS[detail.status] : null
  const resolved = detail?.status === 'CLOSED'

  return (
    <>
      <div aria-hidden="true" onClick={onClose} className="fixed inset-0 z-40 bg-black/40 xl:hidden" />
      <aside
        ref={panel}
        aria-label={`Ticket ${ticketRef}`}
        className="fixed inset-y-0 right-0 z-50 flex w-full max-w-[480px] flex-col bg-white shadow-admin-pop
                   xl:sticky xl:top-[70px] xl:z-auto xl:h-[calc(100vh-84px)] xl:max-w-none xl:rounded-admin-card
                   xl:border xl:border-admin-line xl:shadow-admin-card"
      >
        {/* Two rows, because a ticket reference is longer than the reference's "#T-1024": the
            reference, its status and the menus on top; when it was opened and Close beneath. */}
        <div className="shrink-0 border-b border-admin-line px-5 pb-4 pt-5">
          <div className="flex items-center gap-2">
            <div className="flex min-w-0 flex-1 flex-wrap items-center gap-x-2.5 gap-y-1">
              <h2 className="truncate text-[21px] font-bold leading-tight text-admin-ink">#{ticketRef}</h2>
              {status && <StatusBadge dot label={status.label} tone={status.tone} />}
            </div>
            {detail && (
              <Popover
                align="right"
                label={`More for ticket ${ticketRef}`}
                buttonClassName="grid h-10 w-10 place-items-center rounded-admin-control border border-admin-line text-admin-ink
                                 hover:bg-admin-page focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-admin-red"
                trigger={() => <LuEllipsisVertical aria-hidden="true" className="h-5 w-5" />}
                panelClassName="w-[240px] py-1"
              >
                {(close) => (
                  <>
                    <MenuItem onSelect={() => { close(); void copyLink() }}>
                      <LuCopy aria-hidden="true" className="h-4 w-4 text-admin-faint" />
                      Copy customer&rsquo;s link
                    </MenuItem>
                    {detail.orderRef && (
                      <MenuItem onSelect={() => { close(); window.open(`/admin/orders/${detail.orderRef}`, '_blank', 'noopener') }}>
                        <LuExternalLink aria-hidden="true" className="h-4 w-4 text-admin-faint" />
                        Open order in a new tab
                      </MenuItem>
                    )}
                  </>
                )}
              </Popover>
            )}
            <button
              ref={closeButton}
              type="button"
              onClick={onClose}
              aria-label="Close ticket panel"
              className="grid h-10 w-8 shrink-0 place-items-center rounded-admin-control text-admin-ink hover:bg-admin-page
                         focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-admin-red"
            >
              <LuX aria-hidden="true" className="h-5 w-5" />
            </button>
          </div>
          <div className="mt-2 flex items-center gap-3">
            <p className="min-w-0 flex-1 text-[13px] text-admin-muted">
              {detail ? (
                <>
                  Created on {dateAndTime(detail.createdAt)}
                  {detail.openedBy === 'STAFF' && ' · written to them by staff'}
                </>
              ) : ' '}
            </p>
            {detail && (
              resolved ? (
                <button type="button" disabled={Boolean(busy)} className={buttonClasses('outline')}
                  onClick={() => void act('reopen', () => api.post<AdminSupportDetail>(`${path}/reopen`), 'Ticket reopened.')}>
                  {busy === 'reopen' ? 'Reopening…' : 'Reopen Ticket'}
                </button>
              ) : (
                <button type="button" disabled={Boolean(busy)} className={buttonClasses('soft')}
                  onClick={() => void act('close', () => api.post<AdminSupportDetail>(`${path}/close`), 'Ticket resolved.')}>
                  {busy === 'close' ? 'Closing…' : 'Close Ticket'}
                </button>
              )
            )}
          </div>
        </div>

        {loadError ? (
          <div className="p-6 text-center">
            <p role="alert" className="text-[14px] font-semibold text-admin-ink">{loadError}</p>
            <button type="button" onClick={() => void load()} className={`${buttonClasses('outline', 'sm')} mt-3`}>
              Try again
            </button>
          </div>
        ) : !detail ? (
          <div className="space-y-3 p-5" aria-busy="true">
            {[0, 1, 2, 3].map((i) => <span key={i} className="block h-12 animate-pulse rounded bg-admin-grey-tint" />)}
          </div>
        ) : (
          <>
            <div ref={thread} className="min-h-0 flex-1 overflow-y-auto px-5 py-4">
              <CustomerCard detail={detail} busy={Boolean(busy)}
                onCategory={(category) => void act('category',
                  () => api.patch<AdminSupportDetail>(path, { category }), 'Category saved.')} />

              {(notice || error) && (
                <p role={error ? 'alert' : 'status'}
                  className={`mt-4 rounded-admin-control px-3 py-2 text-[13px] ${error
                    ? 'bg-admin-red-tint text-admin-red-ink' : 'bg-admin-green-tint text-admin-green-ink'}`}>
                  {error ?? notice}
                </p>
              )}

              <h3 className="mb-3 mt-5 text-[16px] font-semibold text-admin-ink">Previous Messages</h3>
              {detail.messages.length === 0 ? (
                <p className="rounded-admin-control bg-admin-page px-3.5 py-3 text-[13px] text-admin-muted">
                  This ticket has no messages.
                </p>
              ) : (
                <ol className="space-y-5">
                  {detail.messages.map((m) => (
                    <MessageItem key={m.id} message={m} customer={detail.customerName ?? detail.email} />
                  ))}
                </ol>
              )}
            </div>

            <form onSubmit={(e) => void send(e)} className="shrink-0 border-t border-admin-line px-5 pb-5 pt-2">
              <div role="group" aria-label="Write" className="flex gap-1 border-b border-admin-line">
                {([['reply', 'Reply to Customer'], ['note', 'Internal Note']] as const).map(([key, label]) => (
                  <button key={key} type="button" aria-pressed={mode === key} onClick={() => switchMode(key)}
                    className={`relative px-3 py-2.5 text-[13.5px] font-medium focus-visible:outline-none focus-visible:ring-2
                                focus-visible:ring-inset focus-visible:ring-admin-red ${mode === key
                                  ? 'text-admin-red-text' : 'text-admin-ink hover:text-admin-red-text'}`}>
                    {label}
                    {mode === key && <span aria-hidden="true" className="absolute inset-x-2 bottom-0 h-[2px] rounded bg-admin-red" />}
                  </button>
                ))}
              </div>
              <label htmlFor="ticket-text" className="sr-only">
                {mode === 'note' ? 'Internal note, only staff will see it' : `Reply to ${detail.customerName ?? detail.email}`}
              </label>
              <textarea
                id="ticket-text"
                value={text}
                maxLength={4000}
                rows={4}
                onChange={(e) => { setText(e.target.value); drafts.current.set(`${ticketRef}:${mode}`, e.target.value) }}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
                    e.preventDefault()
                    void send()
                  }
                }}
                placeholder={mode === 'note' ? 'Write a note only staff will see…' : 'Type your reply…'}
                className={`mt-3 w-full resize-y rounded-admin-control border px-3 py-2.5 text-[13.5px] text-admin-ink
                            placeholder:text-admin-faint focus:outline-none focus:ring-2 ${mode === 'note'
                              ? 'border-[#F2DDBE] bg-[#FFFBF3] focus:border-admin-amber-icon focus:ring-admin-amber-icon/20'
                              : 'border-admin-line bg-white focus:border-admin-red focus:ring-admin-red/20'}`}
              />
              <div className="mt-2.5 flex items-center justify-between gap-3">
                <p className="min-w-0 text-[12px] leading-snug text-admin-faint">
                  {mode === 'note'
                    ? 'Only staff see notes. Nobody is told.'
                    : `Emails ${detail.email}${detail.hasAccount ? ' and shows on their account' : ''}.${resolved
                      ? ' Replying reopens the ticket.' : ''}`}
                </p>
                <button type="submit" disabled={!text.trim() || Boolean(busy)} className={`${buttonClasses('primary')} shrink-0`}>
                  {mode === 'note'
                    ? <LuLock aria-hidden="true" className="h-4 w-4" />
                    : <LuSendHorizontal aria-hidden="true" className="h-4 w-4" />}
                  {busy === 'send' ? 'Sending…' : mode === 'note' ? 'Add Note' : 'Send Reply'}
                </button>
              </div>
            </form>
          </>
        )}
      </aside>
    </>
  )
}

function CustomerCard({
  detail: d, busy, onCategory,
}: {
  detail: AdminSupportDetail
  busy: boolean
  onCategory: (category: SupportCategory) => void
}) {
  const name = d.customerName ?? d.email
  return (
    <section aria-label="Customer" className="rounded-admin-card border border-admin-line p-4">
      <div className="flex items-center gap-3">
        <Avatar name={name} seed={d.email} size="lg" />
        <div className="min-w-0">
          {d.customerName && <p className="truncate text-[16px] font-semibold text-admin-ink">{d.customerName}</p>}
          <p className={`truncate ${d.customerName ? 'text-[13.5px] text-admin-muted' : 'text-[15px] font-semibold text-admin-ink'}`}>
            {d.email}
          </p>
          <p className="text-[12px] text-admin-faint">
            {d.hasAccount ? 'Has an account' : 'Guest: answers through the emailed link'}
          </p>
        </div>
      </div>
      {/* Order and category side by side, and the issue beneath them at full width: a
          ticket's subject is often longer than the reference's "Coins not received". */}
      <dl className="mt-4 grid grid-cols-2 gap-x-5 gap-y-3 text-[13px]">
        <div className="min-w-0">
          <dt className="text-admin-faint">Order ID</dt>
          <dd className="mt-1 whitespace-nowrap font-semibold">
            {d.orderRef
              ? <Link to={`/admin/orders/${d.orderRef}`} className="text-admin-red-text hover:underline">#{d.orderRef}</Link>
              : <span className="font-normal text-admin-faint">—</span>}
          </dd>
        </div>
        <div className="min-w-0">
          <dt><label htmlFor="ticket-category" className="text-admin-faint">Category</label></dt>
          <dd className="mt-0.5">
            <select
              id="ticket-category"
              value={d.category ?? ''}
              disabled={busy}
              onChange={(e) => { if (e.target.value) onCategory(e.target.value as SupportCategory) }}
              className="-ml-1 max-w-full cursor-pointer rounded-admin-control border border-transparent bg-transparent
                         py-0.5 pl-1 pr-1 text-[13px] font-semibold text-admin-ink hover:border-admin-line
                         focus:border-admin-red focus:outline-none focus:ring-2 focus:ring-admin-red/20"
            >
              {!d.category && <option value="">Not set</option>}
              {SUPPORT_CATEGORIES.map((c) => <option key={c} value={c}>{SUPPORT_CATEGORY[c].label}</option>)}
            </select>
          </dd>
        </div>
        <div className="col-span-2 min-w-0">
          <dt className="text-admin-faint">Issue</dt>
          <dd className="mt-1 break-words text-admin-ink">{d.subject}</dd>
        </div>
      </dl>
    </section>
  )
}

function MessageItem({ message: m, customer }: { message: AdminSupportMessage; customer: string }) {
  const note = m.kind === 'NOTE'
  const staff = m.author === 'STAFF'
  return (
    <li className="flex gap-3">
      {note ? (
        <span aria-hidden="true" className="grid h-9 w-9 shrink-0 place-items-center rounded-full bg-admin-amber-tint text-admin-amber-icon">
          <LuLock className="h-4 w-4" />
        </span>
      ) : staff ? (
        <span aria-hidden="true" className="grid h-9 w-9 shrink-0 place-items-center rounded-full bg-admin-red-tint text-[11.5px] font-bold text-admin-red-text">
          GFS
        </span>
      ) : (
        <Avatar name={customer} seed={customer} />
      )}
      <div className="min-w-0 flex-1">
        <p className="flex flex-wrap items-baseline gap-x-2 text-[13.5px]">
          <strong className="font-semibold text-admin-ink">{note ? 'Internal note' : staff ? 'GFS Support' : customer}</strong>
          <time dateTime={m.at} className="text-[12px] text-admin-faint">{shortDateTime(m.at)}</time>
        </p>
        <div className={`mt-1.5 whitespace-pre-wrap break-words rounded-[10px] px-3.5 py-2.5 text-[13.5px] leading-relaxed
                         text-admin-ink ${note
                           ? 'border border-dashed border-[#E9C98F] bg-[#FFFBF3]'
                           : staff ? 'bg-[#FDEDEF]' : 'bg-[#F3F4F6]'}`}>
          {m.body}
        </div>
        {staff && m.authorLabel && (
          <p className="mt-1 text-[11.5px] text-admin-faint">
            {note ? 'Only staff see this · ' : 'Sent as GFS Support · '}by {m.authorLabel}
          </p>
        )}
      </div>
    </li>
  )
}
