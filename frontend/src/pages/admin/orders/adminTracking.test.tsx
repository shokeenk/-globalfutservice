import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AdminOrderRow, VendorOrderDetail, VendorSection } from '../../../lib/types'

/*
 * Staff follow a coin order the way its customer does. Once FUT Transfer has the order, the
 * Orders table's Tracking column and the order page's FUT Transfer section link the
 * customer's own tracking page -- the address the customer's email carries -- and say how far
 * it has got. Before that, a dash.
 */

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('../../../lib/api', () => ({ api, ApiError: class ApiError extends Error {} }))

const { ORDER_COLUMNS, OrderRows, OrderTableHead } = await import('./OrderTable')
const { VendorPanel } = await import('./VendorPanel')

const REF = 'GFS-26-MOVING001'
const URL = `https://globalfutservices.com/track?ref=${REF}`

function row(publicRef: string, tracking: AdminOrderRow['tracking']): AdminOrderRow {
  return {
    publicRef, status: tracking ? 'IN_PROGRESS' : 'READY_FOR_DELIVERY', sku: 'TRADING_SERVICE',
    serviceLabel: 'Buy Coins — 500K (PlayStation)', variant: null, quantity: '0.5', platform: 'PLAYSTATION',
    deliveryMethod: 'PLAYER_AUCTION', credentialsHeld: true, withPartner: tracking != null, customerName: 'Rahul',
    customerEmail: 'rahul@example.test', paymentState: null, paymentMethod: null, paymentReference: null,
    eaHandle: null, totalMinor: 825000, totalFormatted: '₹8,250.00', currency: 'INR',
    createdAt: '2026-10-07T10:00:00Z', deliveredAt: null, availableTransitions: [], tracking,
  }
}

function table(rows: AdminOrderRow[]) {
  render(
    <MemoryRouter>
      <table>
        <OrderTableHead />
        <OrderRows rows={rows} busy={null} onAction={() => {}} now={Date.parse('2026-10-07T12:00:00Z')} />
      </table>
    </MemoryRouter>,
  )
}

function trackingCell(ref: string) {
  const order = screen.getByRole('link', { name: `#${ref}` })
  return within(order.closest('tr')!).getByTestId('tracking-cell')
}

describe("the Orders table's Tracking column", () => {
  it("a dash until FUT Transfer has the order; then the customer's own page, in a new tab, and delivered of ordered", () => {
    table([row('GFS-26-QUEUED001', null), row(REF, { url: URL, orderedK: 500, deliveredK: 200 })])

    expect(screen.getByRole('columnheader', { name: 'Tracking' })).toBeInTheDocument()
    expect(screen.getAllByRole('columnheader')).toHaveLength(ORDER_COLUMNS)

    const queued = trackingCell('GFS-26-QUEUED001')
    expect(queued).toHaveTextContent('—')
    expect(within(queued).queryByRole('link')).toBeNull()

    const moving = trackingCell(REF)
    const link = within(moving).getByRole('link', { name: `Customer tracking page for order ${REF} (opens in a new tab)` })
    expect(link).toHaveAttribute('href', URL)
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
    expect(moving).toHaveTextContent('200 of 500 K')
  })

  it('just started, nothing reported yet: nothing delivered', () => {
    table([row(REF, { url: URL, orderedK: 500, deliveredK: null })])
    expect(trackingCell(REF)).toHaveTextContent('0 of 500 K')
  })
})

function detail(patch: Partial<VendorOrderDetail> = {}): VendorOrderDetail {
  return {
    state: 'IN_DELIVERY', externalRef: REF, vendorOrderId: 'vid-1', amountOrderedK: 500, vendorAmountOrderedK: 500,
    deliveredK: 420, vendorStatus: 'partlyDelivered', vendorAccountCheck: 'finished',
    vendorEconomyState: 'transfersInProgress', aborted: false, coinsUsed: 135040, toPay: 12.5, attempts: 1,
    lastErrorCode: null, reviewReason: null, customerAction: null, missingPolls: 0,
    submittedAt: '2026-10-07T10:00:00Z', lastPolledAt: '2026-10-07T10:05:00Z', lastProgressAt: '2026-10-07T10:05:00Z',
    resubmittedAt: null, updatedAt: '2026-10-07T10:05:00Z',
    ...patch,
  }
}

function section(patch: Partial<VendorSection> = {}): VendorSection {
  return {
    enabled: true, paused: false, vendorOrder: detail(), available: [], calls: [], actions: [],
    tracking: { transferStartedAt: '2026-10-07T10:00:00Z', customerUrl: URL },
    ...patch,
  }
}

function panel(s: VendorSection) {
  render(<VendorPanel publicRef={REF} section={s} onChanged={vi.fn()} />)
  return screen.getByTestId('vendor-tracking')
}

/** What a line of the summary says. */
function line(summary: HTMLElement, label: string): HTMLElement {
  return within(summary).getByText(label).nextSibling as HTMLElement
}

describe("the FUT Transfer section's Tracking summary", () => {
  beforeEach(() => {
    api.get.mockReset()
    api.get.mockResolvedValue(null)
  })

  it('before FUT Transfer has it: not onboarded, not started, and no link to give', () => {
    const summary = panel(section({ vendorOrder: null, tracking: { transferStartedAt: null, customerUrl: null } }))
    expect(line(summary, 'Onboarded')).toHaveTextContent('No')
    expect(line(summary, 'Transfer started')).toHaveTextContent('Not yet')
    expect(line(summary, 'Customer tracking page')).toHaveTextContent('Not yet')
    expect(line(summary, 'Delivered of ordered')).toHaveTextContent('—')
    expect(within(summary).queryByRole('link')).toBeNull()
    expect(within(summary).queryByRole('button')).toBeNull()
  })

  it("with FUT Transfer: the customer's page in a new tab and a copy button, when, how far, and when it last reported", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    const summary = panel(section())

    const link = within(summary).getByRole('link', { name: URL })
    expect(link).toHaveAttribute('href', URL)
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
    fireEvent.click(within(summary).getByRole('button', { name: 'Copy link' }))
    expect(writeText).toHaveBeenCalledWith(URL)
    expect(await within(summary).findByRole('button', { name: 'Copied' })).toBeInTheDocument()

    expect(line(summary, 'Onboarded')).toHaveTextContent(/^Yes, /)
    expect(line(summary, 'Transfer started')).toHaveTextContent(/^Yes, /)
    expect(line(summary, 'Delivered of ordered')).toHaveTextContent('420 of 500 K')
    expect(line(summary, 'Last reported')).not.toHaveTextContent('None yet')
    expect(line(summary, 'Held up by')).toHaveTextContent('Nothing')
    // The customer's page is the only link: FUT Transfer's own progress page is keyed by a code
    // its API never returns, so it is not linked, and no address is made up for it.
    expect(within(summary).getAllByRole('link')).toHaveLength(1)
    expect(within(summary).queryByText(/progress page/i)).toBeNull()
  })

  it.each<[string, Partial<VendorOrderDetail>, string]>([
    ['needs review', { state: 'NEEDS_REVIEW', reviewReason: 'Left out of the last 3 status checks', lastErrorCode: 'MISSING_FROM_POLL' },
      'Needs review: Left out of the last 3 status checks'],
    ['waiting for the customer', { state: 'AWAITING_CUSTOMER', customerAction: 'BACKUP_CODES' },
      'Waiting for the customer: BACKUP_CODES'],
    ['aborted', { aborted: true }, 'FUT Transfer reports this order was aborted.'],
  ])('says what holds it up: %s', (_name, patch, text) => {
    const summary = panel(section({ vendorOrder: detail(patch) }))
    expect(line(summary, 'Held up by')).toHaveTextContent(text)
  })

  it('calls to FUT Transfer paused: holds up an order in flight, not one already delivered', () => {
    const summary = panel(section({ paused: true }))
    expect(line(summary, 'Held up by')).toHaveTextContent('Calls to FUT Transfer are paused')
  })
})
