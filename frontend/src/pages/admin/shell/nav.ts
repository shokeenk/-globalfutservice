import type { IconType } from 'react-icons'
import {
  LuChartColumn, LuCreditCard, LuFileText, LuHeadset, LuLayers, LuLayoutDashboard,
  LuMail, LuSettings, LuTag, LuUser,
} from 'react-icons/lu'
import { SiDiscord } from 'react-icons/si'

/**
 * The console's navigation, in the order the reference design gives it.
 *
 * <p>One list drives the sidebar, the drawer and the rail, so the three cannot disagree
 * about what exists or who may see it. Routes live in App.tsx; the paths here have to
 * match them, and a mismatch shows up as a highlighted item that never lights.
 *
 * <p><b>{@code adminOnly} hides; it does not protect.</b> The routes are wrapped in
 * RequireAdmin and the endpoints behind them are {@code hasRole('ADMIN')}, which is the
 * actual control. Hiding the item is about not offering an operator a door that bounces
 * them back to the queue.
 */
export interface NavLeaf {
  label: string
  to: string
  icon?: IconType
  adminOnly?: boolean
  /**
   * Light only on this exact path. For an entry whose sibling lives beneath it, e.g.
   * Listings and Add Listing: without it, both are highlighted on the add page.
   */
  exact?: boolean
}

export interface NavGroup {
  label: string
  icon: IconType
  children: NavLeaf[]
  adminOnly?: boolean
}

export type NavItem = NavLeaf | NavGroup

export function isGroup(item: NavItem): item is NavGroup {
  return 'children' in item
}

export const ADMIN_NAV: NavItem[] = [
  { label: 'Dashboard', to: '/admin/dashboard', icon: LuLayoutDashboard },
  { label: 'Orders', to: '/admin/orders', icon: LuFileText },
  { label: 'Customers', to: '/admin/customers', icon: LuUser },
  /*
   * Listings and Add Listing as the Service Listings reference draws them, then the two
   * live screens the reference has no entry for: the coin rate card and the coaching
   * diary. Listings sets what customers are charged, as the rate card does, so both are
   * an admin's; an operator sees only the diary here.
   */
  {
    label: 'Services',
    icon: LuLayers,
    children: [
      { label: 'Listings', to: '/admin/services/listings', adminOnly: true, exact: true },
      { label: 'Add Listing', to: '/admin/services/listings/new', adminOnly: true },
      { label: 'Coin rates', to: '/admin/services/rates', adminOnly: true },
      { label: 'Coaching diary', to: '/admin/services/coaching' },
    ],
  },
  { label: 'Payments', to: '/admin/payments', icon: LuCreditCard },
  { label: 'Discord Integration', to: '/admin/discord', icon: SiDiscord },
  {
    label: 'Email Marketing',
    icon: LuMail,
    adminOnly: true,
    children: [
      { label: 'Send Campaign', to: '/admin/email/send' },
      { label: 'Campaign History', to: '/admin/email/history' },
      { label: 'Email Templates', to: '/admin/email/templates' },
      { label: 'Subscriber List', to: '/admin/email/subscribers' },
      { label: 'Settings', to: '/admin/email/settings' },
    ],
  },
  { label: 'Promotions', to: '/admin/promotions', icon: LuTag },
  { label: 'Analytics', to: '/admin/analytics', icon: LuChartColumn, adminOnly: true },
  { label: 'Support', to: '/admin/support', icon: LuHeadset },
  { label: 'Website Settings', to: '/admin/settings', icon: LuSettings },
]

/** The navigation this account is offered: admin-only entries removed for operators. */
export function navFor(role: string | undefined): NavItem[] {
  const admin = role === 'ADMIN'
  return ADMIN_NAV
    .filter((item) => admin || !item.adminOnly)
    .map((item) => (isGroup(item)
      ? { ...item, children: item.children.filter((c) => admin || !c.adminOnly) }
      : item))
    .filter((item) => !isGroup(item) || item.children.length > 0)
}

/** Whether a path is this entry or somewhere beneath it, e.g. an order under Orders. */
export function matches(pathname: string, to: string): boolean {
  return pathname === to || pathname.startsWith(`${to}/`)
}

/**
 * What the top bar's search box says and where it sends you, for the page you are on.
 *
 * <p>Every entry says what it actually searches. Orders is the only searchable list so
 * far, and its search covers the reference, email, name, EA ID, Discord name and the
 * payment reference, which is why the dashboard's "customers" wording is honest. A page
 * gets its own entry when it has a list of its own to search.
 */
export interface SearchTarget {
  placeholder: string
  /** For screen readers, who get no placeholder: what is searched, in full. */
  label: string
  /** The page that shows the results. */
  path: string
}

const ORDER_SEARCH: SearchTarget = {
  placeholder: 'Search order, customer, email, EA ID…',
  label: 'Search orders by reference, customer name, email, EA ID or payment reference',
  path: '/admin/orders',
}

const SEARCHES: Array<[prefix: string, target: SearchTarget]> = [
  ['/admin/dashboard', { ...ORDER_SEARCH, placeholder: 'Search orders, customers, or order ID…' }],
  ['/admin/orders', ORDER_SEARCH],
  ['/admin/payments', {
    placeholder: 'Search order, customer, txn ID…',
    label: 'Search payments by order, customer name or email, or payment or refund reference',
    path: '/admin/payments',
  }],
  ['/admin/customers', {
    placeholder: 'Search name, email, EA ID, Discord…',
    label: 'Search customers by name, email, EA ID or Discord name or ID',
    path: '/admin/customers',
  }],
]

export function searchFor(pathname: string): SearchTarget {
  return SEARCHES.find(([prefix]) => matches(pathname, prefix))?.[1] ?? ORDER_SEARCH
}
