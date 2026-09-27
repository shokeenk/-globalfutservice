import { FaWindows, FaXbox } from 'react-icons/fa6'
import { SiPlaystation } from 'react-icons/si'
import { PLATFORM_LABEL } from './status'

/**
 * A platform as its logo, the way the Orders reference shows it: PlayStation, Xbox, and
 * the Windows mark for PC. The name goes to screen readers and to the hover title.
 */
export function PlatformMark({ platform }: { platform: string | null | undefined }) {
  if (!platform) {
    return <span className="text-admin-faint">—<span className="sr-only">No platform</span></span>
  }
  const label = PLATFORM_LABEL[platform] ?? platform
  const common = 'h-[18px] w-[18px]'
  const icon = platform === 'PLAYSTATION'
    ? <SiPlaystation aria-hidden="true" className={`${common} text-[#1F4FC4]`} />
    : platform === 'XBOX'
      ? <FaXbox aria-hidden="true" className={`${common} text-[#107C10]`} />
      : platform === 'PC'
        ? <FaWindows aria-hidden="true" className={`${common} text-[#1B2A4A]`} />
        : null
  return (
    <span title={label} className="inline-flex items-center">
      {icon ?? <span className="text-[12px] text-admin-ink">{label}</span>}
      {icon && <span className="sr-only">{label}</span>}
    </span>
  )
}
