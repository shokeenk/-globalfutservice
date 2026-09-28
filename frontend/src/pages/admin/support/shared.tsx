import type { SupportCategory, SupportStatus } from '../../../lib/types'
import { TONE_CLASSES } from '../ui/Badge'
import type { StatusStyle } from '../ui/status'

/** The seven topics, in the contact form's order, coloured as the Support reference colours them. */
export const SUPPORT_CATEGORY: Record<SupportCategory, StatusStyle> = {
  COINS: { label: 'Coins', tone: 'blue' },
  BOOSTING: { label: 'Boosting', tone: 'red' },
  COACHING: { label: 'Coaching', tone: 'violet' },
  PAYMENT: { label: 'Payment', tone: 'amber' },
  ACCOUNT: { label: 'Account', tone: 'grey' },
  TECHNICAL: { label: 'Technical', tone: 'violet' },
  OTHER: { label: 'Other', tone: 'grey' },
}

export const SUPPORT_CATEGORIES = Object.keys(SUPPORT_CATEGORY) as SupportCategory[]

/**
 * Where a ticket stands, in the reference's words. "Waiting" is waiting for the customer:
 * staff have answered and the next move is theirs.
 */
export const TICKET_STATUS: Record<SupportStatus, StatusStyle> = {
  OPEN: { label: 'Open', tone: 'red' },
  ANSWERED: { label: 'Waiting', tone: 'amber' },
  CLOSED: { label: 'Resolved', tone: 'green' },
}

/** The tabs, and the server's name for each. */
export const TICKET_TABS: Array<{ key: '' | 'open' | 'waiting' | 'resolved'; label: string }> = [
  { key: '', label: 'All' },
  { key: 'open', label: 'Open' },
  { key: 'waiting', label: 'Waiting' },
  { key: 'resolved', label: 'Resolved' },
]

/** A ticket's topic as the small tinted tag in the Issue column; a dash when it has none. */
export function CategoryTag({ category }: { category?: SupportCategory | null }) {
  if (!category) return <span className="text-admin-faint">—</span>
  const style = SUPPORT_CATEGORY[category] ?? { label: category, tone: 'grey' as const }
  return (
    <span className={`inline-flex whitespace-nowrap rounded-[6px] px-2 py-[3px] text-[12px] font-medium ${TONE_CLASSES[style.tone].badge}`}>
      {style.label}
    </span>
  )
}
