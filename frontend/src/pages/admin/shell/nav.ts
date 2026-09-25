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
   * A group, where the reference draws a single item.
   *
   * The reference has no entry for the coaching diary or the coin rate card, and both
   * are live screens people use daily. Services is the item they belong under; drawing
   * it as a group is the one departure from the image, and it is the difference between
   * the diary being reachable and not.
   */
  {
    label: 'Services',
    icon: LuLayers,
    children: [
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
  { label: 'Analytics', to: '/admin/analytics', icon: LuChartColumn },
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
