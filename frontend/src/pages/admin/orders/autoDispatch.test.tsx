import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { VendorActionEntry, VendorOrderDetail, VendorSection } from '../../../lib/types'

/*
 * The order page says how the order reached FUT Transfer: "Sent automatically", or "Sent by"
 * the admin who clicked Approve -- and, when the automatic queue left it, why.
 */

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('../../../lib/api', () => ({ api, ApiError: class ApiError extends Error {} }))

const { VendorPanel } = await import('./VendorPanel')
const { whoSent, notSentAutomatically } = await import('./vendor')

const REF = 'GFS-26-AUTO0001'

const sentOrder: VendorOrderDetail = {
  state: 'SUBMITTED', externalRef: REF, vendorOrderId: 'vid-1', amountOrderedK: 500, vendorAmountOrderedK: 500,
  deliveredK: null, vendorStatus: null, vendorAccountCheck: null, vendorEconomyState: null, aborted: null,
  coinsUsed: null, toPay: null, attempts: 1, lastErrorCode: null, reviewReason: null, customerAction: null,
  missingPolls: 0, submittedAt: '2026-10-07T10:00:00Z', lastPolledAt: null, lastProgressAt: null,
  resubmittedAt: null, updatedAt: '2026-10-07T10:00:00Z',
}

function entry(patch: Partial<VendorActionEntry>): VendorActionEntry {
  return { at: '2026-10-07T10:00:00Z', action: 'AUTO_DISPATCH', actorLabel: 'automatic', outcome: 'DONE',
    code: null, detail: 'Sent to the fulfilment partner as vid-1.', ...patch }
}

function section(vendorOrder: VendorOrderDetail | null, actions: VendorActionEntry[]): VendorSection {
  return { enabled: true, paused: false, vendorOrder, available: [], calls: [], actions }
}

function show(s: VendorSection) {
  render(<VendorPanel publicRef={REF} section={s} onChanged={vi.fn()} />)
}

beforeEach(() => {
  api.get.mockReset()
  api.get.mockResolvedValue({ available: false, currency: 'unconfirmed' })
})

describe('how the order reached the partner', () => {
  it('sent by the automatic queue: "Sent automatically", and the history names it', () => {
    show(section(sentOrder, [entry({})]))
    expect(screen.getByTestId('who-sent')).toHaveTextContent('Sent automatically')
    expect(screen.getByText('Automatic sending')).toBeInTheDocument()
    expect(screen.queryByTestId('not-sent-automatically')).toBeNull()
  })

  it('sent by an admin\'s Approve: "Sent by" them', () => {
    show(section(sentOrder, [entry({ action: 'APPROVE', actorLabel: 'admin@example.test' })]))
    expect(screen.getByTestId('who-sent')).toHaveTextContent('Sent by admin@example.test')
    expect(screen.getByText('Approve')).toBeInTheDocument()
  })

  it('left for Approve by the queue: the reason staff were alerted with, and "Not sent to the partner"', () => {
    const reason = '500K is more than the automatic limit of 400K (GFS_FUTTRANSFER_AUTO_DISPATCH_MAX_K), so it '
      + 'waits for an admin. Approve it to send it.'
    show(section(null, [entry({ outcome: 'REFUSED', code: 'OVER_LIMIT', detail: reason })]))
    expect(screen.getByTestId('not-sent-automatically')).toHaveTextContent('Not sent automatically')
    expect(screen.getByTestId('not-sent-automatically')).toHaveTextContent(reason)
    expect(screen.getByText('Not sent to the partner.')).toBeInTheDocument()
    expect(screen.queryByTestId('who-sent')).toBeNull()
  })

  it('left by the queue, then sent by an admin: "Sent by" them, and the old reason is not shown any more', () => {
    const s = section(sentOrder, [
      entry({ outcome: 'REFUSED', code: 'NO_SIGN_IN', detail: 'No sign-in yet.' }),
      entry({ at: '2026-10-07T11:00:00Z', action: 'APPROVE', actorLabel: 'admin@example.test' }),
    ])
    expect(whoSent(s)).toBe('Sent by admin@example.test')
    expect(notSentAutomatically(s)).toBeNull()
    show(s)
    expect(screen.queryByTestId('not-sent-automatically')).toBeNull()
  })
})
