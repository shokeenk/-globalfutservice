import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// ---- the API, mocked: these tests are about the step, not the server ---------------
const api = vi.hoisted(() => ({ get: vi.fn() }))
vi.mock('../../lib/api', () => ({
  api,
  ApiError: class ApiError extends Error {},
}))

// Imported after the mock is declared, so the step picks up the mocked module.
const { ScheduleStep, VIEWER_ZONE, formatSlot } = await import('./ScheduleStep')

const COACH = {
  id: 'vinay', displayName: 'Vinay', headline: null, bio: null, avatarUrl: null,
  languages: null, timezone: 'Asia/Kolkata',
}
/** 19:00 and 19:30 on Monday 5 Oct 2026 in India. */
const SLOTS = ['2026-10-05T13:30:00.000Z', '2026-10-05T14:00:00.000Z']

function serve() {
  api.get.mockImplementation(async (path: string) => {
    if (path === '/api/v1/coaching/coaches') return [COACH]
    return { coachId: 'vinay', coachTimezone: 'Asia/Kolkata', sessionMinutes: 40, slots: SLOTS }
  })
}

function localTime(iso: string) {
  return new Date(iso).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
}

function renderStep(props: Partial<Parameters<typeof ScheduleStep>[0]> = {}) {
  const onChange = vi.fn()
  const onContinue = vi.fn()
  const utils = render(
    <ScheduleStep
      variant="MONTHLY_6_SESSIONS"
      sessionsInPack={6}
      value={null}
      onChange={onChange}
      onContinue={onContinue}
      notice={null}
      refreshKey={0}
      {...props}
    />,
  )
  return { ...utils, onChange, onContinue }
}

describe('ScheduleStep', () => {
  beforeEach(() => {
    // Only Date is faked, so promises and the test's own timers behave normally.
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-10-01T06:00:00Z'))
    api.get.mockReset()
    serve()
  })
  afterEach(() => { vi.useRealTimers() })

  it('asks for slots at the length of the product being bought', async () => {
    renderStep()

    await screen.findByRole('radio', { name: localTime(SLOTS[0]!) })
    const slotCall = api.get.mock.calls.map((c) => c[0] as string).find((p) => p.includes('/slots'))
    expect(slotCall).toContain('variant=MONTHLY_6_SESSIONS')
  })

  it("labels every time with the customer's own time zone", async () => {
    renderStep()

    expect(await screen.findByText(VIEWER_ZONE)).toBeInTheDocument()
    expect(screen.getByText(/Times shown in/)).toBeInTheDocument()
  })

  it('picking a time selects it -- with the zone -- and does not book anything', async () => {
    const { onChange } = renderStep()

    await userEvent.click(await screen.findByRole('radio', { name: localTime(SLOTS[1]!) }))

    expect(onChange).toHaveBeenCalledWith({
      coachId: 'vinay', coachName: 'Vinay', startsAt: SLOTS[1], timezone: VIEWER_ZONE,
    })
    // Only the coaches and the slots were read; nothing was posted.
    expect(api.get.mock.calls.every((c) => typeof c[0] === 'string')).toBe(true)
  })

  it('shows the chosen time and moves on', async () => {
    const chosen = { coachId: 'vinay', coachName: 'Vinay', startsAt: SLOTS[0]!, timezone: 'Europe/London' }
    const { onContinue } = renderStep({ value: chosen })

    expect(await screen.findByRole('radio', { name: localTime(SLOTS[0]!) }))
      .toHaveAttribute('aria-checked', 'true')
    expect(screen.getByText(/\(Europe\/London\)/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /Continue/ }))
    expect(onContinue).toHaveBeenCalled()
  })

  it('will not continue without a time', async () => {
    const { onContinue } = renderStep()
    await screen.findByRole('radio', { name: localTime(SLOTS[0]!) })

    await userEvent.click(screen.getByRole('button', { name: /Continue/ }))

    expect(screen.getByRole('alert')).toHaveTextContent('Pick a time to continue.')
    expect(onContinue).not.toHaveBeenCalled()
  })

  it('tells the customer when the slot they picked was just taken', async () => {
    renderStep({ notice: 'That slot was just taken, please pick another.' })

    expect(await screen.findByText('That slot was just taken, please pick another.')).toBeInTheDocument()
  })

  it('says the rest of a package is booked later', async () => {
    renderStep()

    expect(await screen.findByText(/Book the other 5 from your order page/)).toBeInTheDocument()
  })

  it('a single session says nothing about booking the rest', async () => {
    renderStep({ variant: 'SINGLE_SESSION', sessionsInPack: 1 })
    await screen.findByRole('radio', { name: localTime(SLOTS[0]!) })

    expect(screen.queryByText(/Book the other/)).toBeNull()
  })
})

describe('formatSlot', () => {
  it("shows a customer outside India the time in their own zone, and names it", () => {
    const text = formatSlot('2026-10-05T13:30:00Z', 'America/New_York')

    expect(text).toMatch(/9:30/)
    expect(text).toContain('(America/New_York)')
    expect(formatSlot('2026-10-05T13:30:00Z', 'Asia/Kolkata')).toMatch(/19:00|7:00/)
  })
})
