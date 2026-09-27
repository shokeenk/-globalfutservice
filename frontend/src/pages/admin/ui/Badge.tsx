import { orderStatus, paymentState, SERVICE_TAG, type Tone } from './status'

/**
 * Class names per tone, written out in full so Tailwind finds them.
 */
export const TONE_CLASSES: Record<Tone, { badge: string; tile: string; soft: string; line: string }> = {
  red: {
    line: 'border-[#F6D3D6]',
    badge: 'bg-admin-red-tint text-admin-red-ink',
    tile: 'bg-admin-red-tint text-admin-red-icon',
    soft: 'border-[#F6D3D6] bg-admin-red-tint text-admin-red-ink hover:bg-[#F9DADD]',
  },
  blue: {
    line: 'border-[#CFDDF7]',
    badge: 'bg-admin-blue-tint text-admin-blue-ink',
    tile: 'bg-admin-blue-tint text-admin-blue-icon',
    soft: 'border-[#CFDDF7] bg-admin-blue-tint text-admin-blue-ink hover:bg-[#DCE7FA]',
  },
  amber: {
    line: 'border-[#F2DDBE]',
    badge: 'bg-admin-amber-tint text-admin-amber-ink',
    tile: 'bg-admin-amber-tint text-admin-amber-icon',
    soft: 'border-[#F2DDBE] bg-admin-amber-tint text-admin-amber-ink hover:bg-[#F8E6CC]',
  },
  green: {
    line: 'border-[#C6EBD3]',
    badge: 'bg-admin-green-tint text-admin-green-ink',
    tile: 'bg-admin-green-tint text-admin-green-icon',
    soft: 'border-[#C6EBD3] bg-admin-green-tint text-admin-green-ink hover:bg-[#D4F1DF]',
  },
  grey: {
    line: 'border-[#DCDFE5]',
    badge: 'bg-admin-grey-tint text-admin-grey-ink',
    tile: 'bg-admin-grey-tint text-admin-grey-icon',
    soft: 'border-admin-line bg-white text-admin-ink hover:bg-admin-page',
  },
  violet: {
    line: 'border-[#DCD2FA]',
    badge: 'bg-admin-violet-tint text-admin-violet-ink',
    tile: 'bg-admin-violet-tint text-admin-violet-icon',
    soft: 'border-[#DCD2FA] bg-admin-violet-tint text-admin-violet-ink hover:bg-[#E4DCFB]',
  },
}

/**
 * The console's one badge.
 *
 * <p>Uppercase by stylesheet, not by text, so a screen reader says "Awaiting payment"
 * rather than spelling out capitals.
 */
export function StatusBadge({ label, tone, className = '' }: { label: string; tone: Tone; className?: string }) {
  return (
    <span
      className={`inline-flex items-center whitespace-nowrap rounded-[5px] px-2 py-[5px] text-admin-badge
                  font-semibold uppercase ${TONE_CLASSES[tone].badge} ${className}`}
    >
      {label}
    </span>
  )
}

export function OrderStatusBadge({ status }: { status: string }) {
  const style = orderStatus(status)
  return <StatusBadge label={style.label} tone={style.tone} />
}

export function PaymentBadge({ state }: { state: string | null | undefined }) {
  const style = paymentState(state)
  return <StatusBadge label={style.label} tone={style.tone} />
}

/** Which product an order is, as the small outlined tag above its description. */
export function ServiceTag({ sku }: { sku: string }) {
  const style = SERVICE_TAG[sku] ?? { label: sku, tone: 'grey' as Tone }
  return (
    <span
      className={`inline-flex rounded-[4px] border px-1.5 py-[3px] text-[9.5px] font-semibold uppercase
                  leading-none tracking-[0.06em] ${TONE_CLASSES[style.tone].badge} ${TONE_CLASSES[style.tone].line}`}
    >
      {style.label}
    </span>
  )
}
