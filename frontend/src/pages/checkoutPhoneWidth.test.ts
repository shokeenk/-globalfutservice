import { describe, expect, it } from 'vitest'

/*
 * The checkouts must fit a phone.
 *
 * A grid with no column template on a phone sizes its single column to the widest thing
 * inside it that cannot wrap. The payment cards' one-line captions, the coupon row and
 * the like then pushed the checkout to 401px (472px in French) on a 375px screen. The
 * body clips sideways overflow, so the right edge of the cards and buttons was simply
 * cut off. `grid-cols-1` gives that column `minmax(0, 1fr)`, which cannot grow past the
 * screen, and a caption meant to truncate then truncates.
 *
 * Layout cannot be measured here, so this holds the rule in the source: in the checkout
 * pages, a grid that sets columns for wider screens also sets them for a phone.
 */
const PAGES: Record<string, string> = import.meta.glob<string>(
  ['./BoostingCheckout.tsx', './CoachingBook.tsx', './Order.tsx'],
  { query: '?raw', import: 'default', eager: true },
)

/** Every class list in a file: plain strings and the literal parts of template strings. */
function classLists(source: string): string[] {
  const out: string[] = []
  for (const m of source.matchAll(/className=(?:"([^"]*)"|\{`([^`]*)`\})/g)) {
    out.push((m[1] ?? m[2] ?? '').replace(/\$\{[^}]*\}/g, ' '))
  }
  return out
}

function missingPhoneTemplate(source: string): string[] {
  return classLists(source).filter((list) => {
    const classes = list.split(/\s+/)
    const responsive = classes.some((c) => /^(sm|md|lg|xl):grid-cols-/.test(c))
    const base = classes.some((c) => c.startsWith('grid-cols-'))
    return classes.includes('grid') && responsive && !base
  })
}

describe('checkout pages on a phone', () => {
  it('finds the three checkout pages', () => {
    expect(Object.keys(PAGES)).toHaveLength(3)
  })

  for (const [file, source] of Object.entries(PAGES)) {
    it(`${file}: every grid with wider-screen columns also sets a phone column`, () => {
      expect(missingPhoneTemplate(source)).toEqual([])
    })
  }

  it('the rule catches the pattern that caused the overflow', () => {
    expect(missingPhoneTemplate('<div className="grid gap-5 lg:grid-cols-[1.35fr_1fr]">')).toHaveLength(1)
    expect(missingPhoneTemplate('<div className="grid grid-cols-1 gap-5 lg:grid-cols-[1.35fr_1fr]">')).toEqual([])
    expect(missingPhoneTemplate('<div className={`grid gap-3 ${x} sm:grid-cols-2`}>')).toHaveLength(1)
    expect(missingPhoneTemplate('<div className="flex gap-3 sm:gap-4">')).toEqual([])
  })
})
