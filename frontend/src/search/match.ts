/**
 * Text matching for the site search.
 *
 * <p><b>No search library.</b> The corpus here is around forty entries — every route,
 * every FAQ answer, every sellable service — and it lives entirely in memory. Fuse.js
 * would be six kilobytes to rank forty strings, and its default fuzzy behaviour is
 * actively wrong for this content: on a storefront, "cards" must not match "coaching"
 * because a customer who typed one and was shown the other will conclude the search is
 * broken. Everything below is prefix and substring matching with weights, which is
 * predictable in a way edit-distance is not.
 *
 * <p><b>Folding matters more than usual here.</b> The interface ships in English,
 * Spanish and French, so the index contains `garantía`, `sécurité`, `entrañable`. A
 * customer typing on a phone keyboard types `garantia`. Stripping combining marks
 * before comparison is the difference between the search working in two of the three
 * languages and working in all three.
 */

/** Combining marks left behind by NFD decomposition. */
const COMBINING = /[̀-ͯ]/g

/**
 * Lowercase, strip accents, and reduce everything that is not a letter or digit to a
 * single space. Used for matching, never for display.
 */
export function fold(input: string): string {
  return input
    .normalize('NFD')
    .replace(COMBINING, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, ' ')
    .trim()
}

/**
 * The same fold, character by character, so the result lines up index-for-index with
 * the input.
 *
 * <p>This exists only for highlighting: to underline the matched run in the *original*
 * text, the offsets found in the folded text have to mean the same thing in both. The
 * collapsing fold above cannot do that, because it changes the length.
 *
 * <p>A character that decomposes to more than one visible character would break the
 * alignment. Rather than mis-highlight, callers check the length and skip.
 */
export function foldAligned(input: string): string {
  let out = ''
  for (const ch of input) {
    const folded = ch.normalize('NFD').replace(COMBINING, '').toLowerCase()
    out += folded.length === 1 ? folded : ch.toLowerCase()
  }
  return out
}

/** Split a raw query into folded terms. Empty terms are dropped. */
export function terms(query: string): string[] {
  const f = fold(query)
  return f ? f.split(' ').filter(Boolean) : []
}

/**
 * How much each field contributes.
 *
 * <p>The title dominates deliberately. Someone typing "boost" wants the Boosting page,
 * not the FAQ answer that happens to use the word twice in a sentence — so a title hit
 * has to outrank any number of body hits.
 */
export const FIELD = { title: 1, keywords: 0.72, body: 0.42 } as const

/**
 * How much each kind of match contributes.
 *
 * <p>A word-prefix hit ("coach" in "FUT coaching") scores nearly as well as a match at
 * the very start of the field, because typing the first letters of any word in a label
 * is how people actually search. A bare substring — "ach" inside "coaching" — is worth
 * far less; it is usually a coincidence.
 */
const PREFIX = 1
const WORD_PREFIX = 0.78
const SUBSTRING = 0.4

/** Shortest term allowed to match mid-word. See scoreTermInField. */
const SUBSTRING_MIN = 4

/** A field of an entry, pre-folded at build time so matching never re-folds. */
export interface FoldedField {
  folded: string
  weight: number
}

/** The best score one term can achieve against one field, or 0 for no match. */
function scoreTermInField(term: string, field: FoldedField): number {
  const { folded, weight } = field
  if (!folded) return 0

  if (folded.startsWith(term)) return weight * PREFIX

  // Word-prefix: the term begins some word in the field. The field is already
  // space-collapsed by `fold`, so a leading space is a reliable word boundary.
  if (folded.includes(' ' + term)) return weight * WORD_PREFIX

  /*
   * Bare substring matching is withheld from short terms.
   *
   * Three letters land inside an unrelated word constantly: "ban" is in "net banking",
   * "pc" is in "upcoming", "fut" is in "future". Those hits are always coincidence and
   * they always look like the search is guessing. Four characters or more, a substring
   * hit is usually someone typing the middle of a word they half-remember, which is
   * worth serving.
   */
  if (term.length >= SUBSTRING_MIN && folded.includes(term)) return weight * SUBSTRING

  return 0
}

/**
 * Score an entry against a set of terms.
 *
 * <p>Returns 0 unless <em>every</em> term matches somewhere. That AND semantics is what
 * keeps a two-word query useful: "coach price" should return the coaching page, not
 * everything containing either word. Search that widens as you type more is search that
 * punishes you for being specific.
 */
export function scoreFields(queryTerms: string[], fields: FoldedField[]): number {
  if (queryTerms.length === 0) return 0

  let total = 0
  for (const term of queryTerms) {
    let best = 0
    for (const field of fields) {
      const s = scoreTermInField(term, field)
      if (s > best) best = s
    }
    if (best === 0) return 0
    total += best
  }
  return total
}

/**
 * A bonus for matching the title as one continuous phrase.
 *
 * <p>Without it, "track order" scores the same against a page titled "Track your order"
 * as against one that merely mentions both words far apart. The exact-title case gets
 * more again, so typing a page's name in full always puts that page first.
 */
export function phraseBonus(query: string, foldedTitle: string): number {
  const f = fold(query)
  if (!f) return 0
  if (foldedTitle === f) return 3
  if (foldedTitle.startsWith(f)) return 1.6
  if (foldedTitle.includes(f)) return 1
  return 0
}

/** A half-open range of character offsets in the original string. */
export type Range = [start: number, end: number]

/**
 * Find the runs of `text` that the terms matched, for highlighting.
 *
 * <p>Overlapping and adjacent runs are merged, so "coach" and "coaching" against the
 * same word produce one underline rather than two stacked on top of each other.
 *
 * <p>Returns nothing at all when the aligned fold does not line up with the source —
 * highlighting is a nicety, and a wrong highlight is worse than none.
 */
export function highlightRanges(text: string, queryTerms: string[]): Range[] {
  const aligned = foldAligned(text)
  if (aligned.length !== text.length) return []

  const found: Range[] = []
  for (const term of queryTerms) {
    let from = 0
    for (;;) {
      const at = aligned.indexOf(term, from)
      if (at === -1) break
      found.push([at, at + term.length])
      from = at + term.length
    }
  }
  if (found.length === 0) return []

  found.sort((a, b) => a[0] - b[0])
  const merged: Range[] = [found[0]!]
  for (const range of found.slice(1)) {
    const last = merged[merged.length - 1]!
    if (range[0] <= last[1]) last[1] = Math.max(last[1], range[1])
    else merged.push(range)
  }
  return merged
}

/**
 * Does this look like one of our order references?
 *
 * <p>`GFS-26-BWG6NGG3`. People paste these with the dashes, without them, in lower
 * case, and with a stray space from the email they copied it out of — so the test runs
 * against the folded form, where all four are the same string.
 *
 * <p>Worth special-casing because it is the single most likely thing to be typed into a
 * search box on a site like this, and no amount of text matching would ever find it:
 * the reference exists in the customer's inbox, not in the index.
 */
export function asOrderRef(query: string): string | null {
  const f = fold(query).replace(/ /g, '')
  const m = /^gfs(\d{2})([a-z0-9]{5,})$/.exec(f)
  if (!m) return null
  return `GFS-${m[1]}-${m[2]!.toUpperCase()}`
}
