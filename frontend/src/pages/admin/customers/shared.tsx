import type { AdminCustomer } from '../../../lib/types'
import { TONE_CLASSES } from '../ui/Badge'
import type { StatusStyle, Tone } from '../ui/status'

/** An account's state, or "Guest" for someone who checked out without one. */
export const CUSTOMER_STATUS: Record<AdminCustomer['status'], StatusStyle> = {
  ACTIVE: { label: 'Active', tone: 'green' },
  GUEST: { label: 'Guest', tone: 'grey' },
  LOCKED: { label: 'Locked', tone: 'amber' },
  DISABLED: { label: 'Disabled', tone: 'red' },
}

/** "RS" from "Rahul Sharma", "GU" from "Guest", "RA" from "rahul07". */
export function initials(name: string): string {
  const words = name.trim().split(/[\s._-]+/).filter(Boolean)
  const letters = words.length >= 2 ? `${words[0]![0]}${words[1]![0]}` : name.trim().slice(0, 2)
  return letters.toUpperCase() || '?'
}

const AVATAR_TONES: Tone[] = ['violet', 'blue', 'grey', 'red', 'amber', 'green']

/** The same person always gets the same colour, chosen from their key. */
export function avatarTone(seed: string): Tone {
  let hash = 0
  for (const ch of seed) hash = (hash * 31 + ch.charCodeAt(0)) | 0
  return AVATAR_TONES[Math.abs(hash) % AVATAR_TONES.length]!
}

export function Avatar({ name, seed, size = 'md' }: { name: string; seed: string; size?: 'md' | 'lg' }) {
  return (
    <span
      aria-hidden="true"
      className={`grid shrink-0 place-items-center rounded-full font-semibold ${TONE_CLASSES[avatarTone(seed)].badge}
                  ${size === 'lg' ? 'h-14 w-14 text-[17px]' : 'h-9 w-9 text-[12.5px]'}`}
    >
      {initials(name)}
    </span>
  )
}

/**
 * What a customer spent, in the currency they spent most in, and "+ £23.99" for any other.
 * Never added across currencies: there are no exchange rates to do it with.
 */
export function spentText(spent: NonNullable<AdminCustomer['spent']>): string {
  if (spent.length === 0) return '—'
  const [first, ...rest] = spent
  return rest.length ? `${first!.formatted} + ${rest.map((s) => s.formatted).join(' + ')}` : first!.formatted
}
