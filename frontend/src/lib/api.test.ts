import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from './api'

/**
 * The Accept header each kind of call sends.
 *
 * <p>Worth a test because the failure is invisible from the calling code: the campaign
 * preview endpoint produces only text/html, and a request declaring it accepts only JSON
 * is refused by Spring before any controller runs. The page saw a generic "could not be
 * loaded" and nothing pointed at a header.
 */
describe('Accept headers', () => {
  const fetchMock = vi.fn()

  beforeEach(() => {
    fetchMock.mockReset()
    vi.stubGlobal('fetch', fetchMock)
    // jsdom has no object URLs.
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: () => 'blob:fixture' }))
  })
  afterEach(() => vi.unstubAllGlobals())

  const acceptOf = (call: number) =>
    (fetchMock.mock.calls[call]![1] as RequestInit).headers as Record<string, string>

  it('asks for anything when fetching a file', async () => {
    fetchMock.mockResolvedValue(new Response('<html></html>', { status: 200 }))

    await expect(api.blobUrl('/api/v1/admin/campaigns/camp_x/preview')).resolves.toBe('blob:fixture')

    expect(acceptOf(0).Accept).toBe('*/*')
  })

  it('still asks for JSON on an ordinary call', async () => {
    fetchMock.mockResolvedValue(new Response('[]', {
      status: 200, headers: { 'Content-Type': 'application/json' },
    }))

    await api.get('/api/v1/admin/orders')

    expect(acceptOf(0).Accept).toBe('application/json')
  })

  it('reports a refused file as an error rather than returning a broken URL', async () => {
    fetchMock.mockResolvedValue(new Response('', { status: 406 }))

    await expect(api.blobUrl('/api/v1/admin/campaigns/camp_x/preview')).rejects.toMatchObject({
      status: 406,
    })
  })
})
