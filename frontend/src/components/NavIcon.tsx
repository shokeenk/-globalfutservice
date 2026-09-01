/**
 * Glyphs for the primary navigation.
 *
 * <p>Stroked at 1.7 by default, like every other icon on the site, so a glyph does not
 * look like it was imported from somewhere else. The weight is a prop because the
 * header row needs more: those glyphs sit at 19px inside a 32px tile, and a hairline
 * that reads correctly at 38px in the picker goes thin and papery at half that size.
 * Optical weight is a function of size, so one constant cannot serve both. Each one is
 * drawn for what the section
 * *does* rather than for the noun in its label — "Track order" is a parcel with a
 * clock, not a magnifying glass, because the question a customer has there is when,
 * not where.
 *
 * <p>Decorative in every case. The label sits directly under the glyph, so the icon
 * speeds up recognition for someone who has been here before and carries no meaning
 * of its own for anyone else. That is why they are `aria-hidden` at the call site
 * rather than labelled.
 */
export function NavIcon(
  { name, size = 19, strokeWidth = 1.7 }:
  { name: string; size?: number; strokeWidth?: number },
) {
  const common = {
    width: size,
    height: size,
    viewBox: '0 0 24 24',
    fill: 'none',
    stroke: 'currentColor',
    strokeWidth,
    strokeLinecap: 'round' as const,
    strokeLinejoin: 'round' as const,
    'aria-hidden': true,
  }

  switch (name) {
    /* Trading — two flows crossing. Coins go one way, value comes back the other. */
    case 'trading':
      return (
        <svg {...common}>
          <path d="M4 8h13" /><path d="m14 5 3 3-3 3" />
          <path d="M20 16H7" /><path d="m10 13-3 3 3 3" />
        </svg>
      )

    /* Boosting — a climb, not just an arrow. The steps are the wins. */
    case 'boosting':
      return (
        <svg {...common}>
          <path d="M4 19h4v-5H4z" /><path d="M10 19h4V9h-4z" /><path d="M16 19h4V4h-4z" />
        </svg>
      )

    /* Coaching — one person pointing something out to another. */
    case 'coaching':
      return (
        <svg {...common}>
          <circle cx="9" cy="8" r="3" />
          <path d="M3.5 19a5.5 5.5 0 0 1 11 0" />
          <path d="M17 8.5h4" /><path d="M17 12h4" /><path d="M17 15.5h2.5" />
        </svg>
      )

    /* Rewards — points accumulating, drawn as a stack rather than a trophy. Nobody
       wins anything here; they earn a balance. */
    case 'rewards':
      return (
        <svg {...common}>
          <ellipse cx="12" cy="6.5" rx="7.5" ry="3" />
          <path d="M4.5 6.5v5c0 1.7 3.4 3 7.5 3s7.5-1.3 7.5-3v-5" />
          <path d="M4.5 11.5v5c0 1.7 3.4 3 7.5 3s7.5-1.3 7.5-3v-5" />
        </svg>
      )

    /* Track — a parcel and a clock. The question is when, not where. */
    case 'track':
      return (
        <svg {...common}>
          <path d="M11 20.3 4 16.6V7.4L12 3.2l8 4.2v4.3" />
          <path d="m4 7.4 8 4.2 8-4.2" /><path d="M12 11.6v8.7" />
          <circle cx="17.5" cy="17.5" r="4.2" />
          <path d="M17.5 15.6v2l1.3.9" />
        </svg>
      )

    /* FAQs — a question inside a speech bubble, because the help centre is written
       as a conversation and the widget in the corner is literally one. */
    case 'faqs':
      return (
        <svg {...common}>
          <path d="M20.5 12.5a7.5 7.5 0 0 1-10.8 6.7L4.5 20.5l1.3-5.2A7.5 7.5 0 1 1 20.5 12.5Z" />
          <path d="M10.2 10.1a2.3 2.3 0 1 1 3 2.2c-.5.2-.8.7-.8 1.3v.3" />
          <path d="M12.4 16.6h.01" />
        </svg>
      )

    default:
      return null
  }
}
