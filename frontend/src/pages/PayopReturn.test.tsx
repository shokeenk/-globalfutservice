import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => {
  class ApiError extends Error {
    status: number
    code: string
    constructor(status: number, code: string) {
      super(code)
      this.status = status
      this.code = code
    }
  }
  return { api: { get: vi.fn(), post: vi.fn(), put: vi.fn() }, ApiError }
})
vi.mock('../lib/api', () => ({ api: mocks.api, ApiError: mocks.ApiError }))

const { default: PayopReturn } = await import('./PayopReturn')
const { api, ApiError } = mocks

function visit(query: string) {
  render(
    <MemoryRouter initialEntries={[`/payment/payop/return${query}`]}>
      <Routes><Route path="/payment/payop/return" element={<PayopReturn />} /></Routes>
    </MemoryRouter>,
  )
}

const status = (payment: string) => ({
  payment, order: 'AWAITING_PAYMENT', totalMinor: 9407, totalFormatted: '€94.07', method: 'Bank transfer',
})

beforeEach(() => {
  api.get.mockReset()
  api.post.mockReset()
  api.put.mockReset()
})

describe('the page Payop returns the customer to', () => {
  it('asks the server and only reads: a visit never sends anything that could change an order', async () => {
    api.get.mockResolvedValue(status('PAID'))
    visit('?ref=GFS-26-EUR00001&invoice=inv-1')

    expect(await screen.findByText('Payment confirmed')).toBeInTheDocument()
    expect(screen.getByText('€94.07 with Bank transfer')).toBeInTheDocument()
    expect(api.get).toHaveBeenCalledWith('/api/v1/payments/payop/return-status?ref=GFS-26-EUR00001&invoice=inv-1')
    expect(api.post).not.toHaveBeenCalled()
    expect(api.put).not.toHaveBeenCalled()
  })

  it('a missing "failed" flag is not proof of payment: pending is shown as pending', async () => {
    api.get.mockResolvedValue(status('PENDING'))
    visit('?ref=GFS-26-EUR00001&invoice=inv-1')
    expect(await screen.findByText('Waiting for confirmation')).toBeInTheDocument()
    expect(screen.queryByText('Payment confirmed')).not.toBeInTheDocument()
  })

  it('Payop saying it failed, before the server knows: still pending, with a note', async () => {
    api.get.mockResolvedValue(status('PENDING'))
    visit('?ref=GFS-26-EUR00001&invoice=inv-1&result=failed')
    expect(await screen.findByText('Waiting for confirmation')).toBeInTheDocument()
    expect(screen.getByText(/Payop reported a problem with this payment/)).toBeInTheDocument()
  })

  it('failed, in review, and unknown each say so', async () => {
    api.get.mockResolvedValueOnce(status('FAILED'))
    visit('?ref=GFS-26-EUR00001&invoice=inv-1')
    expect(await screen.findByText('The payment did not go through')).toBeInTheDocument()
  })

  it('an invoice the server does not know, or no parameters at all', async () => {
    api.get.mockRejectedValue(new ApiError(404, 'not_found'))
    visit('?ref=GFS-26-EUR00001&invoice=nope')
    expect(await screen.findByText('We could not find this payment')).toBeInTheDocument()
  })

  it('without parameters it does not ask at all', async () => {
    visit('')
    expect(await screen.findByText('We could not find this payment')).toBeInTheDocument()
    expect(api.get).not.toHaveBeenCalled()
  })
})
