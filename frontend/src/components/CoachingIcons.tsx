/**
 * The line icons used on the coaching page and in the booking flow.
 *
 * Drawn in one stroke weight on a 24px grid, like NavIcon, so the badges, process steps
 * and area cards read as one set. No platform logos: PlayStation and Xbox are marked by
 * PlatformIcon, which deliberately does not reproduce either trademark.
 */
export type CoachIconName =
  | 'chat' | 'clipboard' | 'gamepad' | 'video' | 'bars' | 'bulb' | 'checkCircle'
  | 'target' | 'shield' | 'trend' | 'sliders' | 'brain' | 'trophy' | 'arrowRight'
  | 'lock' | 'globe' | 'check' | 'card' | 'qr' | 'clock' | 'shieldCheck' | 'quote'

export function CoachIcon({
  name, className = 'h-5 w-5', strokeWidth = 1.8,
}: {
  name: CoachIconName
  className?: string
  strokeWidth?: number
}) {
  const common = {
    width: 24,
    height: 24,
    viewBox: '0 0 24 24',
    fill: 'none',
    stroke: 'currentColor',
    strokeWidth,
    strokeLinecap: 'round' as const,
    strokeLinejoin: 'round' as const,
    className,
    'aria-hidden': true,
  }
  switch (name) {
    case 'chat':
      return <svg {...common}><path d="M4 5h16v11H9l-5 4z" /><path d="M8 9.5h8M8 12.5h5" /></svg>
    case 'clipboard':
      return <svg {...common}><path d="M9 3.5h6v3H9z" /><path d="M7 5H5v15.5h14V5h-2" /><path d="m9 13 2 2 4-4" /></svg>
    case 'gamepad':
      return (
        <svg {...common}>
          <path d="M6.5 9h11a4 4 0 0 1 3.9 4.9l-.7 3a2 2 0 0 1-3.4.9L15.4 16H8.6l-1.9 1.8a2 2 0 0 1-3.4-.9l-.7-3A4 4 0 0 1 6.5 9z" />
          <path d="M8.5 11.5v3M7 13h3" /><path d="M15.5 12.5h.01M17.5 14h.01" />
        </svg>
      )
    case 'video':
      return <svg {...common}><rect x="3" y="6.5" width="12" height="11" rx="1.5" /><path d="m15 10.5 6-3.5v10l-6-3.5" /></svg>
    case 'bars':
      return <svg {...common}><path d="M5 20v-7M10 20V6M15 20v-9M20 20V9" /></svg>
    case 'bulb':
      return (
        <svg {...common}>
          <path d="M9.5 18h5M10.5 21h3" />
          <path d="M12 3a6 6 0 0 0-3.6 10.8c.7.5 1.1 1.3 1.1 2.2h5c0-.9.4-1.7 1.1-2.2A6 6 0 0 0 12 3z" />
        </svg>
      )
    case 'checkCircle':
      return <svg {...common}><circle cx="12" cy="12" r="9" /><path d="m8 12.3 2.8 2.7L16 9.5" /></svg>
    case 'target':
      return <svg {...common}><circle cx="12" cy="12" r="9" /><circle cx="12" cy="12" r="5" /><circle cx="12" cy="12" r="1.2" /></svg>
    case 'shield':
      return <svg {...common}><path d="M12 3 5 6v5.5c0 4.4 3 8.3 7 9.5 4-1.2 7-5.1 7-9.5V6z" /><path d="M12 3v18" /></svg>
    case 'trend':
      return <svg {...common}><path d="m3 17 6-6 4 4 8-8" /><path d="M15 7h6v6" /></svg>
    case 'sliders':
      return <svg {...common}><path d="M4 7h9M17 7h3M4 17h3M11 17h9" /><circle cx="15" cy="7" r="2" /><circle cx="9" cy="17" r="2" /></svg>
    case 'brain':
      return (
        <svg {...common}>
          <path d="M12 5.5A3 3 0 0 0 6.2 6 3 3 0 0 0 4 9a3 3 0 0 0 1 2.3A3.2 3.2 0 0 0 7 17a3 3 0 0 0 5 1.5z" />
          <path d="M12 5.5A3 3 0 0 1 17.8 6 3 3 0 0 1 20 9a3 3 0 0 1-1 2.3A3.2 3.2 0 0 1 17 17a3 3 0 0 1-5 1.5" />
        </svg>
      )
    case 'trophy':
      return (
        <svg {...common}>
          <path d="M8 4h8v5a4 4 0 0 1-8 0z" /><path d="M8 6H5a3 3 0 0 0 3 4M16 6h3a3 3 0 0 1-3 4" />
          <path d="M12 13v4M9 20.5h6M10 17h4" />
        </svg>
      )
    case 'arrowRight':
      return <svg {...common}><path d="M5 12h14M13 6l6 6-6 6" /></svg>
    case 'lock':
      return <svg {...common}><rect x="5" y="11" width="14" height="10" rx="2" /><path d="M8 11V8a4 4 0 0 1 8 0v3" /></svg>
    case 'globe':
      return (
        <svg {...common}>
          <circle cx="12" cy="12" r="9" /><path d="M3 12h18" />
          <path d="M12 3c2.4 2.6 3.7 5.6 3.7 9s-1.3 6.4-3.7 9c-2.4-2.6-3.7-5.6-3.7-9S9.6 5.6 12 3z" />
        </svg>
      )
    case 'check':
      return <svg {...common}><path d="m5 12.5 4 4L19 7" /></svg>
    case 'card':
      return <svg {...common}><rect x="3" y="6" width="18" height="12" rx="2" /><path d="M3 10h18M7 14.5h3" /></svg>
    case 'qr':
      return (
        <svg {...common}>
          <path d="M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4z" />
          <path d="M14 14h2.5v2.5H14zM17.5 17.5H20V20h-2.5zM14 20h1.5M20 14v1.5" />
        </svg>
      )
    case 'clock':
      return <svg {...common}><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></svg>
    case 'shieldCheck':
      return <svg {...common}><path d="M12 3 5 6v5.5c0 4.4 3 8.3 7 9.5 4-1.2 7-5.1 7-9.5V6z" /><path d="m9 12 2.2 2.2L15 10.5" /></svg>
    case 'quote':
      return <svg {...common}><path d="M6.5 8.5h4v4c0 2.5-1.3 4-4 4.5M13.5 8.5h4v4c0 2.5-1.3 4-4 4.5" /></svg>
  }
}

/**
 * Discord's mark, for the button that opens the invite. Discord's brand guidelines allow
 * its logo on a link to a Discord server, in its own blurple; the same path is used by
 * the sign-in button.
 */
export function DiscordMark({ className = 'h-5 w-5' }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" className={className}>
      <path d="M20.3 4.4A19.8 19.8 0 0 0 15.4 3l-.24.5a18.3 18.3 0 0 1 4.3 1.4c-2-1.1-4.1-1.6-6.4-1.6-2.3 0-4.4.5-6.4 1.6A18.3 18.3 0 0 1 11 3.5L10.7 3a19.8 19.8 0 0 0-4.9 1.4C2.6 9.1 1.7 13.7 2.1 18.2a19.9 19.9 0 0 0 6 3c.5-.65.9-1.35 1.25-2.1-.7-.25-1.35-.55-1.95-.9.16-.12.32-.25.47-.38a14.2 14.2 0 0 0 12.2 0c.16.14.31.26.47.38-.62.36-1.27.66-1.96.9.36.75.78 1.45 1.25 2.1a19.8 19.8 0 0 0 6-3c.5-5.2-.85-9.75-3.5-13.8ZM8.7 15.4c-1.18 0-2.15-1.07-2.15-2.4S7.5 10.6 8.7 10.6s2.17 1.08 2.15 2.4c0 1.33-.96 2.4-2.15 2.4Zm6.6 0c-1.18 0-2.15-1.07-2.15-2.4s.95-2.4 2.15-2.4 2.17 1.08 2.15 2.4c0 1.33-.95 2.4-2.15 2.4Z" />
    </svg>
  )
}
