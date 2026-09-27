import { describe, expect, it } from 'vitest'
import { ADMIN_NAV, isGroup, matches, navFor, searchFor } from './nav'

const labels = (items: ReturnType<typeof navFor>) =>
  items.flatMap((i) => (isGroup(i) ? [i.label, ...i.children.map((c) => c.label)] : [i.label]))

describe('navFor', () => {
  it('offers an admin every entry in the reference order', () => {
    expect(navFor('ADMIN').map((i) => i.label)).toEqual([
      'Dashboard', 'Orders', 'Customers', 'Services', 'Payments', 'Discord Integration',
      'Email Marketing', 'Promotions', 'Analytics', 'Support', 'Website Settings',
    ])
  })

  it('hides the admin-only areas from an operator', () => {
    const operator = labels(navFor('OPERATOR'))
    expect(operator).not.toContain('Email Marketing')
    expect(operator).not.toContain('Send Campaign')
    expect(operator).not.toContain('Coin rates')
    expect(operator).not.toContain('Listings')
    expect(operator).not.toContain('Add Listing')
    expect(operator).not.toContain('Analytics')
    // ...but not the operator's own work, which shares a group with an admin-only screen.
    expect(operator).toContain('Coaching diary')
  })

  it('treats an unknown role as the least privileged', () => {
    expect(labels(navFor(undefined))).toEqual(labels(navFor('OPERATOR')))
  })

  it('never returns an empty group', () => {
    for (const role of ['ADMIN', 'OPERATOR', undefined]) {
      for (const item of navFor(role)) {
        if (isGroup(item)) expect(item.children.length).toBeGreaterThan(0)
      }
    }
  })

  it('does not mutate the shared definition', () => {
    const before = JSON.stringify(ADMIN_NAV.map((i) => i.label))
    navFor('OPERATOR')
    expect(JSON.stringify(ADMIN_NAV.map((i) => i.label))).toBe(before)
  })
})

describe('matches', () => {
  it('lights a section for the pages beneath it', () => {
    expect(matches('/admin/orders/GFS-26-ABC', '/admin/orders')).toBe(true)
    expect(matches('/admin/orders', '/admin/orders')).toBe(true)
  })

  it('does not confuse a prefix of a word with a parent path', () => {
    expect(matches('/admin/ordersarchive', '/admin/orders')).toBe(false)
  })
})

describe('Services', () => {
  it('lists Listings, Add Listing, Coin rates and the Coaching diary, in that order', () => {
    const services = navFor('ADMIN').find((i) => i.label === 'Services')
    expect(services && isGroup(services) ? services.children.map((c) => c.label) : []).toEqual([
      'Listings', 'Add Listing', 'Coin rates', 'Coaching diary',
    ])
  })
})

describe('searchFor', () => {
  it("names what each page's search covers", () => {
    expect(searchFor('/admin/orders').placeholder).toBe('Search order, customer, email, EA ID…')
    expect(searchFor('/admin/orders/GFS-26-CN43SP05').path).toBe('/admin/orders')
    expect(searchFor('/admin/dashboard').placeholder).toBe('Search orders, customers, or order ID…')
  })

  it('falls back to the order search on pages with no list of their own yet', () => {
    expect(searchFor('/admin/support').path).toBe('/admin/orders')
  })
})
