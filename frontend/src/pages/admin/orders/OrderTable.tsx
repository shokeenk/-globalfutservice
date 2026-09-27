import { Link } from 'react-router-dom'
import { LuChevronRight, LuCopy, LuExternalLink, LuLock } from 'react-icons/lu'
import type { AdminOrderRow } from '../../../lib/types'
import { OrderStatusBadge, ServiceTag, TONE_CLASSES } from '../ui/Badge'
import { MenuItem, RowMenu } from '../ui/controls'
import { ago, shortDateTime } from '../ui/format'
import { PlatformMark } from '../ui/PlatformMark'
import { Th } from '../ui/Table'
import { nextAction, type NextAction } from './nextAction'

/**
 * The Orders table's rows.
 *
 * <p>Nine columns, the reference's list without its checkbox: no bulk action exists, and
 * a box that selects rows for nothing is a promise the page cannot keep.
 */
export const ORDER_COLUMNS = 9

export function OrderTableHead() {
  return (
    <thead className="bg-[#FAFBFC]">
      <tr className="border-b border-admin-line">
        <Th>Order</Th>
        <Th>Customer</Th>
        <Th>Service</Th>
        <Th>Platform</Th>
        <Th>Amount</Th>
        <Th>Status</Th>
        <Th>Next Action</Th>
        <Th>Created</Th>
        <Th className="text-right">Actions</Th>
      </tr>
    </thead>
  )
}

/** "Buy Coins — 500K (PlayStation)" to "500K (PlayStation)": the tag above says the rest. */
export function serviceDetail(label: string): string {
  const dash = label.indexOf(' — ')
  return dash < 0 ? label : label.slice(dash + 3)
}

export function OrderRows({
  rows, busy, onAction, now,
}: {
  rows: AdminOrderRow[]
  /** The reference of the row whose action is running, if any. */
  busy: string | null
  onAction: (row: AdminOrderRow, action: NextAction) => void
  now: number
}) {
  return (
    <tbody>
      {rows.map((row) => (
        <OrderRow key={row.publicRef} row={row} busy={busy === row.publicRef} onAction={onAction} now={now} />
      ))}
    </tbody>
  )
}

function OrderRow({
  row, busy, onAction, now,
}: {
  row: AdminOrderRow
  busy: boolean
  onAction: (row: AdminOrderRow, action: NextAction) => void
  now: number
}) {
  const action = nextAction(row)
  const orderPath = `/admin/orders/${row.publicRef}`

  return (
    <tr className="border-b border-admin-line align-middle last:border-b-0 hover:bg-[#FCFCFD]">
      <td className="whitespace-nowrap px-3 py-2.5">
        <Link to={orderPath} className="font-semibold text-admin-red-text hover:underline">
          #{row.publicRef}
        </Link>
        {row.credentialsHeld && (
          <span title="A sign-in is held for this order" className="ml-1.5 inline-flex align-[-2px] text-admin-faint">
            <LuLock aria-hidden="true" className="h-3.5 w-3.5" />
            <span className="sr-only">Sign-in held</span>
          </span>
        )}
      </td>
      <td className="max-w-[168px] px-3 py-2.5">
        {row.customerName && (
          <p className="truncate font-medium text-admin-ink" title={row.customerName}>{row.customerName}</p>
        )}
        <p
          title={row.customerEmail ?? undefined}
          className={`truncate ${row.customerName ? 'text-[12px] text-admin-faint' : 'text-admin-ink'}`}
        >
          {row.customerEmail ?? '—'}
        </p>
      </td>
      <td className="max-w-[150px] px-3 py-2.5">
        <ServiceTag sku={row.sku} />
        <p className="mt-1 truncate text-[12.5px] text-admin-muted" title={row.serviceLabel}>
          {serviceDetail(row.serviceLabel)}
        </p>
      </td>
      <td className="px-3 py-2.5">
        <PlatformMark platform={row.platform} />
      </td>
      <td className="whitespace-nowrap px-3 py-2.5 font-medium tabular-nums text-admin-ink">
        {row.totalFormatted}
      </td>
      <td className="px-3 py-2.5">
        <OrderStatusBadge status={row.status} />
      </td>
      <td className="px-3 py-2.5">
        <NextActionButton row={row} action={action} busy={busy} onAction={onAction} />
      </td>
      <td className="whitespace-nowrap px-3 py-2.5">
        <p className="text-admin-ink">{shortDateTime(row.createdAt)}</p>
        <p className="text-[12px] text-admin-faint">{ago(row.createdAt, now)}</p>
      </td>
      <td className="whitespace-nowrap px-3 py-2.5">
        <div className="flex items-center justify-end gap-1.5">
          <Link
            to={orderPath}
            aria-label={`View order ${row.publicRef}`}
            className="inline-flex h-8 items-center rounded-admin-control border border-[#F6D3D6] bg-[#FDF3F4] px-3.5
                       text-[12.5px] font-medium text-admin-red-text hover:bg-admin-red-tint focus-visible:outline-none
                       focus-visible:ring-2 focus-visible:ring-admin-red"
          >
            View
          </Link>
          <RowMenu label={`More for order ${row.publicRef}`}>
            {(close) => (
              <>
                <MenuItem onSelect={() => { close(); window.open(orderPath, '_blank', 'noopener') }}>
                  <LuExternalLink aria-hidden="true" className="h-4 w-4 text-admin-faint" />
                  Open in a new tab
                </MenuItem>
                <MenuItem onSelect={() => { close(); void navigator.clipboard?.writeText(row.publicRef) }}>
                  <LuCopy aria-hidden="true" className="h-4 w-4 text-admin-faint" />
                  Copy reference
                </MenuItem>
                {row.customerEmail && (
                  <MenuItem onSelect={() => { close(); void navigator.clipboard?.writeText(row.customerEmail!) }}>
                    <LuCopy aria-hidden="true" className="h-4 w-4 text-admin-faint" />
                    Copy customer email
                  </MenuItem>
                )}
              </>
            )}
          </RowMenu>
        </div>
      </td>
    </tr>
  )
}

const actionBase =
  'inline-flex h-8 w-[150px] items-center justify-between gap-1.5 rounded-admin-control border px-2.5 text-[12px] '
  + 'font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-admin-red '
  + 'disabled:cursor-wait disabled:opacity-60'

/**
 * The Next Action cell: a link where the action is "go and look", a button where it
 * does something here.
 */
function NextActionButton({
  row, action, busy, onAction,
}: {
  row: AdminOrderRow
  action: NextAction
  busy: boolean
  onAction: (row: AdminOrderRow, action: NextAction) => void
}) {
  const classes = `${actionBase} ${TONE_CLASSES[action.tone].soft}`
  const inner = (
    <>
      <span className="truncate">{busy ? 'Working…' : action.label}</span>
      <LuChevronRight aria-hidden="true" className="h-4 w-4 shrink-0" />
    </>
  )
  const describe = `${action.label}, order ${row.publicRef}`

  if (action.kind === 'view' || action.kind === 'track' || action.kind === 'dispute') {
    return <Link to={`/admin/orders/${row.publicRef}`} aria-label={describe} className={classes}>{inner}</Link>
  }
  if (action.kind === 'sessions') {
    return <Link to="/admin/services/coaching" aria-label={describe} className={classes}>{inner}</Link>
  }
  return (
    <button type="button" aria-label={describe} disabled={busy} onClick={() => onAction(row, action)} className={classes}>
      {inner}
    </button>
  )
}
