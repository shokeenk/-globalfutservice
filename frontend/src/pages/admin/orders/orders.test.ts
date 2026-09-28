import { describe, expect, it } from 'vitest'
import type { AdminOrderOverview, AdminOrderRow } from '../../../lib/types'
import {
  apiQuery, attentionBreakdown, countFor, EMPTY, fromSaved, readFilters, rangeLabel, statusTabFor, toSaved, writeFilters,
} from './filters'
import { nextAction } from './nextAction'
import { serviceDetail } from './OrderTable'

export function row(patch: Partial<AdminOrderRow> = {}): AdminOrderRow {
  return {
    publicRef: 'GFS-26-CN43SP05', status: 'AWAITING_PAYMENT', sku: 'TRADING_SERVICE',
    serviceLabel: 'Buy Coins — 500K (PlayStation)', variant: null, quantity: 0.5, platform: 'PLAYSTATION',
    deliveryMethod: 'COMFORT_TRADE', credentialsHeld: false, withPartner: false, customerName: 'Rahul_07',
    customerEmail: 'rahul07@example.test', paymentState: null, paymentMethod: null, paymentReference: null,
    eaHandle: null, totalMinor: 825000, totalFormatted: '₹8,250.00', currency: 'INR',
    createdAt: '2026-09-26T10:04:00Z', deliveredAt: null, availableTransitions: [],
    ...patch,
  }
}

describe('nextAction', () => {
  it('asks for the payment to be verified when the customer says they paid', () => {
    expect(nextAction(row({ paymentState: 'SUBMITTED' }))).toMatchObject({ kind: 'verify', label: 'Verify Payment' })
    // Unpaid with nothing claimed, or a claim already rejected: nothing to verify.
    expect(nextAction(row()).kind).toBe('view')
    expect(nextAction(row({ paymentState: 'REJECTED' })).kind).toBe('view')
  })

  it('still sends an unchecked claim to the panel after its order has moved on, worded to be read first', () => {
    expect(nextAction(row({ status: 'ABANDONED', paymentState: 'SUBMITTED' })))
      .toMatchObject({ kind: 'verify', label: 'Check Payment' })
  })

  it('offers the sign-in reminder only while none is on file', () => {
    expect(nextAction(row({ status: 'CREDENTIALS_PENDING' }))).toMatchObject({ kind: 'remind', label: 'Request Sign-in' })
    expect(nextAction(row({ status: 'CREDENTIALS_PENDING', credentialsHeld: true })).kind).toBe('view')
  })

  it('starts a coin order by releasing it, only with a sign-in and not already sent', () => {
    const ready = row({ status: 'READY_FOR_DELIVERY', credentialsHeld: true })
    expect(nextAction(ready)).toMatchObject({ kind: 'release', label: 'Start Order' })
    expect(nextAction({ ...ready, credentialsHeld: false }).kind).toBe('view')
    expect(nextAction({ ...ready, withPartner: true }).kind).toBe('view')
  })

  it('starts a boost with the In progress transition, when the server offers it', () => {
    const boost = row({ status: 'READY_FOR_DELIVERY', sku: 'BOOST_CHAMPS', availableTransitions: ['IN_PROGRESS', 'ON_HOLD'] })
    expect(nextAction(boost)).toMatchObject({ kind: 'start', label: 'Start Order' })
    expect(nextAction({ ...boost, availableTransitions: [] }).kind).toBe('view')
  })

  it('sends coaching to the diary, partner orders to tracking, disputes to review', () => {
    expect(nextAction(row({ sku: 'COACHING', status: 'READY_FOR_DELIVERY' })).kind).toBe('sessions')
    expect(nextAction(row({ status: 'IN_PROGRESS', withPartner: true })).kind).toBe('track')
    expect(nextAction(row({ status: 'DISPUTED' })).kind).toBe('dispute')
  })

  it('never offers delivery, refunds or anything irreversible from the table', () => {
    const everything = ['DRAFT', 'AWAITING_PAYMENT', 'PAID', 'CREDENTIALS_PENDING', 'READY_FOR_DELIVERY',
      'IN_PROGRESS', 'ON_HOLD', 'DELIVERED', 'COMPLETED', 'DISPUTED', 'REFUNDED', 'CREDITED', 'ABANDONED']
    for (const status of everything) {
      for (const sku of ['TRADING_SERVICE', 'BOOST_RIVALS', 'COACHING']) {
        const kind = nextAction(row({
          status, sku, credentialsHeld: true, paymentState: 'SUBMITTED',
          availableTransitions: ['DELIVERED', 'REFUNDED', 'CREDITED', 'IN_PROGRESS'],
        })).kind
        expect(['verify', 'release', 'start', 'remind', 'track', 'dispute', 'sessions', 'view']).toContain(kind)
      }
    }
    expect(nextAction(row({ status: 'DELIVERED' })).kind).toBe('view')
  })
})

describe('filters in the address', () => {
  it('round-trips, leaving defaults out of the address', () => {
    const f = { ...EMPTY, service: 'BOOSTING', status: 'DELIVERED,COMPLETED', search: 'rahul', attention: true, page: 2 }
    const params = writeFilters(f)
    expect(params.toString()).toBe('service=BOOSTING&status=DELIVERED%2CCOMPLETED&search=rahul&attention=1&page=3')
    expect(readFilters(params)).toEqual(f)
    expect(writeFilters(EMPTY).toString()).toBe('')
  })

  it('asks the API for a zero-based page of 25, and the export for everything', () => {
    const f = { ...EMPTY, status: 'ON_HOLD', page: 1 }
    expect(apiQuery(f)).toBe('status=ON_HOLD&page=1&size=25')
    expect(apiQuery(f, false)).toBe('status=ON_HOLD')
    expect(apiQuery({ ...EMPTY, attention: true }, false)).toBe('attention=true')
  })

  it('finds the tab a status list is, whatever its order, and none for statuses no tab has', () => {
    expect(statusTabFor('COMPLETED,DELIVERED')).toBe('completed')
    expect(statusTabFor('')).toBe('all')
    expect(statusTabFor('REFUNDED')).toBeNull()
  })

  it('keeps a saved view to the filters, not the page number', () => {
    const f = { ...EMPTY, platform: 'XBOX', from: '2026-09-01', page: 4 }
    expect(toSaved(f)).toEqual({ platform: 'XBOX', from: '2026-09-01' })
    expect(fromSaved(toSaved(f))).toEqual({ ...f, page: 0 })
  })

  it('labels the date button with the range chosen', () => {
    expect(rangeLabel('', '')).toBe('Select Date')
    // "Sep" or "Sept", depending on the ICU data the runtime ships.
    expect(rangeLabel('2026-09-26', '2026-09-27')).toMatch(/^26 Sept? – 27 Sept?$/)
    expect(rangeLabel('2026-09-26', '2026-09-26')).not.toContain('–')
  })
})

describe('tab counts', () => {
  const overview: AdminOrderOverview = {
    counts: [
      { sku: 'TRADING_SERVICE', status: 'AWAITING_PAYMENT', count: 12 },
      { sku: 'BOOST_CHAMPS', status: 'IN_PROGRESS', count: 2 },
      { sku: 'BOOST_RIVALS', status: 'IN_PROGRESS', count: 1 },
      { sku: 'COACHING', status: 'DELIVERED', count: 4 },
      { sku: 'COACHING', status: 'COMPLETED', count: 3 },
    ],
    paymentsToCheck: 0, signInsToWork: 0, disputed: 0, awaitingSignIn: 0,
    deliveredToday: 0, deliveredYesterdaySoFar: 0, credentialsHeld: 0,
  }

  it('breaks Needs Attention down, leaving out the zeros', () => {
    expect(attentionBreakdown({ ...overview, paymentsToCheck: 2, signInsToWork: 3, disputed: 1 }))
      .toBe('2 Payments • 3 Sign-ins • 1 Disputed')
    expect(attentionBreakdown({ ...overview, paymentsToCheck: 1 })).toBe('1 Payment')
    expect(attentionBreakdown(overview)).toBe('Nothing waiting')
  })

  it('sums across statuses for a service, and within a service for a status', () => {
    expect(countFor(overview, [], [])).toBe(22)
    expect(countFor(overview, ['BOOST_CHAMPS', 'BOOST_RIVALS'], [])).toBe(3)
    expect(countFor(overview, ['COACHING'], ['DELIVERED', 'COMPLETED'])).toBe(7)
    expect(countFor(overview, [], ['IN_PROGRESS'])).toBe(3)
  })
})

describe('service column', () => {
  it('shows the detail after the product name, which the tag already says', () => {
    expect(serviceDetail('FUT Classes — Single session · 1 hour')).toBe('Single session · 1 hour')
    expect(serviceDetail('Something odd')).toBe('Something odd')
  })
})
