import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/*
 * tawk.to's ids come from the API when the site starts (2026-10-23: as build variables they
 * never reached the live bundle). The chat button shows when the API has a floating widget
 * id; the FUT Assistant shows only when there is none.
 */

const api = vi.hoisted(() => ({ get: vi.fn() }))
vi.mock('./api', async (original) => ({ ...(await original<typeof import('./api')>()), api }))
vi.mock('../components/AskWidget', () => ({ AskWidget: () => <div data-testid="ask-widget" /> }))

const { buildTimeIds, loadChatIds, mergeIds, resetChatIdsForTests } = await import('./chatConfig')
const { CornerWidget } = await import('../App')

function serve(ids: unknown) {
  api.get.mockImplementation(async (path: string) => {
    if (path === '/api/v1/chat/config') return ids
    throw new Error(path)
  })
}

function corner(pathname: string) {
  return render(<MemoryRouter><CornerWidget pathname={pathname} /></MemoryRouter>)
}

beforeEach(() => resetChatIdsForTests())
afterEach(() => {
  vi.unstubAllEnvs()
  vi.clearAllMocks()
})

describe('the ids, from the API', () => {
  it('asks once per page load, however many components want them', async () => {
    serve({ propertyId: 'prop1', embedWidgetId: 'emb1', floatingWidgetId: 'flo1' })
    const [a, b] = await Promise.all([loadChatIds(), loadChatIds()])
    expect(a).toEqual({ propertyId: 'prop1', embedWidgetId: 'emb1', floatingWidgetId: 'flo1' })
    expect(b).toBe(a)
    expect(api.get).toHaveBeenCalledTimes(1)
  })

  it('the API wins; the build variables only fill what it leaves empty (local development)', () => {
    const build = buildTimeIds({ VITE_TAWK_PROPERTY_ID: 'devprop', VITE_TAWK_FLOATING_WIDGET_ID: 'devflo' })
    expect(mergeIds({ propertyId: 'prop1', embedWidgetId: null, floatingWidgetId: '' }, build))
      .toEqual({ propertyId: 'prop1', embedWidgetId: null, floatingWidgetId: 'devflo' })
    expect(mergeIds(null, build)).toEqual(build)
  })

  it('an API that cannot be reached falls back to the build variables, never an error', async () => {
    vi.stubEnv('VITE_TAWK_PROPERTY_ID', 'devprop')
    vi.stubEnv('VITE_TAWK_FLOATING_WIDGET_ID', 'devflo')
    api.get.mockRejectedValue(new Error('offline'))
    expect(await loadChatIds()).toEqual({ propertyId: 'devprop', embedWidgetId: null, floatingWidgetId: 'devflo' })
  })
})

describe('the corner of /support', () => {
  it('with a floating widget id from the API: the red Live chat button, not the FUT Assistant', async () => {
    serve({ propertyId: 'prop1', embedWidgetId: 'emb1', floatingWidgetId: '1k5test' })
    corner('/support')
    expect(await screen.findByRole('button', { name: 'Live chat' })).toBeInTheDocument()
    expect(screen.queryByTestId('ask-widget')).toBeNull()
  })

  it('without one: the FUT Assistant, and no chat button', async () => {
    serve({ propertyId: 'prop1', embedWidgetId: 'emb1', floatingWidgetId: null })
    corner('/track')
    expect(await screen.findByTestId('ask-widget')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Live chat' })).toBeNull()
  })

  it('while the ids are on their way: neither, so the assistant never flashes up first', async () => {
    let answer: (v: unknown) => void = () => {}
    api.get.mockImplementation(() => new Promise((resolve) => { answer = resolve }))
    corner('/support')
    await waitFor(() => expect(api.get).toHaveBeenCalled())
    expect(screen.queryByTestId('ask-widget')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Live chat' })).toBeNull()
    answer({ propertyId: 'prop1', embedWidgetId: null, floatingWidgetId: 'flo1' })
    await waitFor(() => expect(screen.getByRole('button', { name: 'Live chat' })).toBeInTheDocument())
  })

  it('pages without the chat keep the FUT Assistant whatever the API says', async () => {
    serve({ propertyId: 'prop1', embedWidgetId: 'emb1', floatingWidgetId: 'flo1' })
    corner('/rewards')
    expect(screen.getByTestId('ask-widget')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Live chat' })).toBeNull()
  })
})
