import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AdminSession } from '../../lib/types'

// ---- the API, mocked: these tests are about the diary, not the server ---------------
const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), del: vi.fn() }))
vi.mock('../../lib/api', () => ({
  api,
  ApiError: class ApiError extends Error {},
}))

// Imported after the mock is declared, so the page picks up the mocked module.
const { default: AdminCoaching } = await import('./AdminCoaching')

/** 19:00 IST on Monday 5 Oct 2026, which is 09:30 in New York. */
const START = '2026-10-05T13:30:00Z'

function session(ref: string, status: string, extra: Partial<AdminSession> = {}): AdminSession {
  return {
    ref, coachName: 'Vinay', customerTimezone: 'America/New_York', startsAt: START,
    endsAt: '2026-10-05T14:10:00Z', status, creditReturned: false, rescheduleCount: 0,
    customerNote: null, meetingUrl: null,
    allowedTransitions: status === 'SCHEDULED'
      ? ['CANCELLED_BY_COACH', 'CANCELLED_BY_CUSTOMER', 'COMPLETED', 'NO_SHOW']
      : status === 'PENDING' ? ['RELEASED', 'SCHEDULED'] : [],
    customerEmail: `${ref}@example.test`, orderRef: 'GFS-26-C1', sessionLabel: '1 of 6',
    paymentStatus: status === 'PENDING' ? 'AWAITING_PAYMENT' : 'PAID',
    inGameId: 'VinayFC10', platform: 'PLAYSTATION', rank: 'Division 3',
    improvementFocus: 'Defending', holdExpiresAt: status === 'PENDING' ? '2026-10-03T08:00:00Z' : null,
    ...extra,
  }
}

const DIARY = [
  session('held', 'PENDING'),
  session('booked', 'SCHEDULED', { startsAt: '2026-10-05T15:30:00Z', endsAt: '2026-10-05T16:10:00Z' }),
  session('released', 'RELEASED', { startsAt: '2026-10-06T13:30:00Z', endsAt: '2026-10-06T14:10:00Z' }),
]

function serve(diary: AdminSession[] = DIARY) {
  api.get.mockImplementation(async (path: string) => {
    if (path === '/api/v1/admin/coaching/coaches') {
      return [{ id: 'vinay', displayName: 'Vinay', headline: null, timezone: 'Asia/Kolkata',
        active: true, sortOrder: 1, availability: [] }]
    }
    if (path.startsWith('/api/v1/admin/coaching/sessions?')) return diary
    if (path === '/api/v1/admin/coaching/settings') {
      return { minNoticeMinutes: 720, bufferMinutes: 0, holdMinutes: 120,
        singleSessionMinutes: 60, blockSessionMinutes: 40 }
    }
    if (path.endsWith('/events')) {
      return [{ type: 'HELD', fromStatus: null, toStatus: 'PENDING', fromTime: null, toTime: START,
        actor: 'CUSTOMER', actorEmail: 'held@example.test', detail: null, at: '2026-10-03T06:00:00Z' }]
    }
    return []
  })
}

async function openList() {
  render(<MemoryRouter><AdminCoaching /></MemoryRouter>)
  await userEvent.click(await screen.findByRole('button', { name: 'List' }))
}

describe('Coaching diary', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-10-05T04:00:00Z'))
    for (const fn of Object.values(api)) fn.mockReset()
    api.post.mockResolvedValue({})
    serve()
  })
  afterEach(() => { vi.useRealTimers() })

  it('shows each booking with everything the coach needs, in IST and the customer\'s time', async () => {
    await openList()

    const held = await screen.findByTestId('session-held')
    expect(held).toHaveTextContent('held@example.test')
    expect(held).toHaveTextContent('GFS-26-C1')
    expect(held).toHaveTextContent('VinayFC10')
    expect(held).toHaveTextContent('PLAYSTATION')
    expect(held).toHaveTextContent('Division 3')
    expect(held).toHaveTextContent('Defending')
    expect(held).toHaveTextContent('Session 1 of 6')
    expect(held).toHaveTextContent('Payment: awaiting payment')
    expect(held).toHaveTextContent('19:00 IST')
    expect(held).toHaveTextContent('09:30 America/New_York')
    expect(held).toHaveTextContent('Pending payment')
    expect(held).toHaveTextContent(/Held until .* IST unless paid/)
  })

  it('filters by status: pending, and cancelled including released holds', async () => {
    await openList()
    await screen.findByTestId('session-held')

    await userEvent.selectOptions(screen.getByLabelText('Status'), 'PENDING')
    expect(screen.getByTestId('session-held')).toBeInTheDocument()
    expect(screen.queryByTestId('session-booked')).toBeNull()

    await userEvent.selectOptions(screen.getByLabelText('Status'), 'CANCELLED')
    expect(screen.getByTestId('session-released')).toBeInTheDocument()
    expect(screen.queryByTestId('session-held')).toBeNull()
  })

  it('confirms and releases a pending hold', async () => {
    await openList()
    const held = await screen.findByTestId('session-held')

    await userEvent.click(within(held).getByRole('button', { name: 'Confirm' }))
    expect(api.post).toHaveBeenCalledWith('/api/v1/admin/coaching/sessions/held/confirm')

    await userEvent.click(within(held).getByRole('button', { name: 'Release' }))
    await userEvent.click(within(held).getByRole('button', { name: 'Release the slot' }))
    expect(api.post).toHaveBeenCalledWith('/api/v1/admin/coaching/sessions/held/cancel', { note: null })
  })

  it('reschedules to a time typed in IST, sent as the right instant', async () => {
    await openList()
    const booked = await screen.findByTestId('session-booked')

    await userEvent.click(within(booked).getByRole('button', { name: 'Reschedule' }))
    const input = within(booked).getByLabelText('New start (IST)')
    await userEvent.type(input, '2026-10-06T19:00')
    await userEvent.click(within(booked).getByRole('button', { name: 'Move it' }))

    expect(api.post).toHaveBeenCalledWith('/api/v1/admin/coaching/sessions/booked/reschedule',
      { startsAt: '2026-10-06T13:30:00.000Z' })
  })

  it('marks a confirmed session completed or a no-show', async () => {
    await openList()
    const booked = await screen.findByTestId('session-booked')

    await userEvent.click(within(booked).getByRole('button', { name: 'Mark no-show' }))

    expect(api.post).toHaveBeenCalledWith('/api/v1/admin/coaching/sessions/booked/outcome',
      { status: 'NO_SHOW', note: null })
    expect(within(booked).getByRole('button', { name: 'Mark completed' })).toBeInTheDocument()
  })

  it('shows who did what and when', async () => {
    await openList()
    const held = await screen.findByTestId('session-held')

    await userEvent.click(within(held).getByRole('button', { name: 'History' }))

    expect(await within(held).findByText('Held at checkout')).toBeInTheDocument()
    expect(within(held).getByText('by held@example.test')).toBeInTheDocument()
  })

  it('has a day view for one day at a time', async () => {
    render(<MemoryRouter><AdminCoaching /></MemoryRouter>)
    await userEvent.click(await screen.findByRole('button', { name: 'Day' }))

    expect(await screen.findByTestId('session-held')).toBeInTheDocument()
    expect(screen.getByTestId('session-booked')).toBeInTheDocument()
    // Tuesday's released hold is not on Monday.
    expect(screen.queryByTestId('session-released')).toBeNull()
  })

  it('saves the booking settings', async () => {
    api.put.mockImplementation(async (_: string, body: unknown) => body)
    render(<MemoryRouter><AdminCoaching /></MemoryRouter>)

    const buffer = await screen.findByLabelText(/Buffer between sessions/)
    await userEvent.clear(buffer)
    await userEvent.type(buffer, '15')
    await userEvent.click(screen.getByRole('button', { name: 'Save settings' }))

    expect(api.put).toHaveBeenCalledWith('/api/v1/admin/coaching/settings',
      expect.objectContaining({ minNoticeMinutes: 720, bufferMinutes: 15 }))
  })
})
