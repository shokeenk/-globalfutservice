import { fireEvent, render, screen } from '@testing-library/react'
import { LuTriangleAlert } from 'react-icons/lu'
import { describe, expect, it, vi } from 'vitest'
import { OrderStatusBadge, PaymentBadge } from './Badge'
import { ago, shortDateTime, todayInIndia, trend } from './format'
import { StatCard, TrendLine } from './StatCard'
import {
  ORDER_STATUS, ORDER_STATUS_ORDER, orderStatus, paymentState, SERVICE_TABS, STATUS_TABS,
} from './status'
import { pageList, Pagination } from './Table'

describe('status names', () => {
  it('names every real order status, so no badge falls back to a raw enum', () => {
    for (const status of ORDER_STATUS_ORDER) {
      expect(ORDER_STATUS[status], status).toBeDefined()
    }
  })

  it('builds the tabs only from real statuses and services', () => {
    for (const tab of STATUS_TABS) {
      for (const status of tab.statuses) expect(ORDER_STATUS[status], status).toBeDefined()
    }
    expect(SERVICE_TABS.flatMap((t) => t.skus).sort())
      .toEqual(['BOOST_CHAMPS', 'BOOST_RIVALS', 'COACHING', 'TRADING_SERVICE'])
  })

  it('maps the reference’s sample words onto what they really mean', () => {
    expect(orderStatus('CREDENTIALS_PENDING').label).toBe('Awaiting sign-in')
    expect(orderStatus('ABANDONED').label).toBe('Abandoned')
    expect(paymentState(null).label).toBe('Unpaid')
    expect(paymentState('SUBMITTED').label).toBe('To check')
    expect(paymentState('REJECTED').label).toBe('Rejected')
  })

  it('shows an unknown status in words rather than failing', () => {
    expect(orderStatus('SOMETHING_NEW')).toEqual({ label: 'Something new', tone: 'grey' })
  })

  it('draws the same badge for the same status wherever it appears', () => {
    const { container: a } = render(<OrderStatusBadge status="DISPUTED" />)
    const { container: b } = render(<OrderStatusBadge status="DISPUTED" />)
    expect(a.innerHTML).toBe(b.innerHTML)
    render(<PaymentBadge state="VERIFIED" />)
    expect(screen.getByText('Verified').className).toContain('uppercase')
  })
})

describe('times', () => {
  const now = Date.parse('2026-09-27T06:00:00Z')

  it('says how long ago, rounded down', () => {
    expect(ago('2026-09-27T05:59:30Z', now)).toBe('just now')
    expect(ago('2026-09-27T05:59:00Z', now)).toBe('1 min ago')
    expect(ago('2026-09-27T05:58:00Z', now)).toBe('2 mins ago')
    expect(ago('2026-09-27T04:30:00Z', now)).toBe('1 hour ago')
    expect(ago('2026-09-24T06:00:00Z', now)).toBe('3 days ago')
    // A clock slightly ahead of the browser's is not "in the future".
    expect(ago('2026-09-27T06:00:30Z', now)).toBe('just now')
  })

  it('shows dates in India time', () => {
    expect(shortDateTime('2026-09-26T10:04:00Z')).toBe('Sep 26, 03:34 PM')
    expect(shortDateTime(null)).toBe('—')
    // 00:30 IST on the 27th is still the 26th in UTC.
    expect(todayInIndia(new Date('2026-09-26T19:00:00Z'))).toBe('2026-09-27')
  })
})

describe('trends', () => {
  it('gives a percentage only when there is something to compare against', () => {
    expect(trend(24, 20)).toEqual({ direction: 'up', percent: 20 })
    expect(trend(15, 20)).toEqual({ direction: 'down', percent: 25 })
    expect(trend(5, 5)).toEqual({ direction: 'flat', percent: 0 })
    expect(trend(3, 0)).toEqual({ direction: 'up', percent: null })
  })

  it('words a rise from nothing as a count, not an invented percentage', () => {
    render(<TrendLine current={3} previous={0} against="yesterday" />)
    expect(screen.getByText(/3 more/)).toBeInTheDocument()
    expect(screen.queryByText(/%/)).toBeNull()
  })
})

describe('StatCard', () => {
  it('shows a dash and says why when the figure could not be read', () => {
    render(<StatCard icon={LuTriangleAlert} tone="red" label="Disputed" value={null} failed />)
    expect(screen.getByText('Could not load')).toBeInTheDocument()
    expect(screen.queryByText('0')).toBeNull()
  })

  it('is a pressed-or-not button when it filters the table', () => {
    const onClick = vi.fn()
    render(<StatCard icon={LuTriangleAlert} tone="red" label="Disputed" value={2} onClick={onClick} active />)
    const card = screen.getByRole('button', { name: /Disputed/ })
    expect(card).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(card)
    expect(onClick).toHaveBeenCalled()
  })
})

describe('pagination', () => {
  it('lists every page when there are few, and gaps when there are many', () => {
    expect(pageList(0, 5)).toEqual([0, 1, 2, 3, 4])
    expect(pageList(0, 20)).toEqual([0, 1, 2, 3, 'gap', 19])
    expect(pageList(10, 20)).toEqual([0, 'gap', 9, 10, 11, 'gap', 19])
    expect(pageList(19, 20)).toEqual([0, 'gap', 16, 17, 18, 19])
  })

  it('says which rows are showing and marks the current page', () => {
    const onPage = vi.fn()
    render(<Pagination page={1} size={25} total={128} onPage={onPage} noun="orders" />)
    expect(screen.getByText('Showing 26–50 of 128 orders')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Page 2' })).toHaveAttribute('aria-current', 'page')
    fireEvent.click(screen.getByRole('button', { name: 'Next page' }))
    expect(onPage).toHaveBeenCalledWith(2)
  })
})
