import type { PopupButton, PopupCopy, PopupFrequency, PopupPage, PopupTrigger, PopupType } from '../../../lib/popups'

/** A popup as the Popup Manager sees it: everything the storefront gets, plus status and stats. */
export interface AdminPopup {
  id: string
  name: string
  type: PopupType
  status: 'DRAFT' | 'PUBLISHED'
  enabled: boolean
  version: number
  desktopImage?: AdminPopupImage | null
  mobileImage?: AdminPopupImage | null
  copy: { en: PopupCopy | null; es?: PopupCopy | null; fr?: PopupCopy | null }
  buttons: PopupButton[]
  targetPages: PopupPage[]
  targetPaths: string[]
  triggerType: PopupTrigger
  triggerValue?: number | null
  triggerEvent?: string | null
  startsAt?: string | null
  endsAt?: string | null
  frequency: PopupFrequency
  frequencyDays?: number | null
  priority: number
  /** Published, switched on and inside its schedule right now. */
  live: boolean
  createdAt: string
  updatedAt: string
  stats: { views: number; closes: number; clicks: number; views30d: number; closes30d: number; clicks30d: number }
}

export interface AdminPopupImage {
  id: number
  url: string
  contentType: string
  width: number
  height: number
  sizeBytes: number
}

export interface PopupChange {
  action: string
  detail?: string | null
  adminEmail?: string | null
  at: string
}

export const PAGE_LABELS: Record<PopupPage, string> = {
  HOME: 'Home',
  COINS: 'Coins',
  COINS_CHECKOUT: 'Coins checkout',
  CHAMPS_LISTING: 'Champs listing',
  CHAMPS_CHECKOUT: 'Champs checkout',
  REWARDS: 'Rewards',
}

/** "Live", "Scheduled", "Ended", "Published, off", "Draft": what the list says about each. */
export function stateLabel(p: AdminPopup, now = Date.now()): { label: string; tone: 'green' | 'blue' | 'grey' | 'amber' } {
  if (p.status === 'DRAFT') return { label: 'Draft', tone: 'grey' }
  if (!p.enabled) return { label: 'Published, off', tone: 'amber' }
  if (p.startsAt && Date.parse(p.startsAt) > now) return { label: 'Scheduled', tone: 'blue' }
  if (p.endsAt && Date.parse(p.endsAt) <= now) return { label: 'Ended', tone: 'grey' }
  return { label: 'Live', tone: 'green' }
}

export function scheduleText(p: Pick<AdminPopup, 'startsAt' | 'endsAt'>): string {
  const f = (iso: string) => new Date(iso).toLocaleString('en-GB', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })
  if (!p.startsAt && !p.endsAt) return 'Always'
  if (p.startsAt && p.endsAt) return `${f(p.startsAt)} – ${f(p.endsAt)}`
  return p.startsAt ? `From ${f(p.startsAt)}` : `Until ${f(p.endsAt!)}`
}
