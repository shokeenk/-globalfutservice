import { useMemo } from 'react'
import { useT } from '../i18n'
import { useSearchCorpus, type SearchEntry, type SearchGroup } from './corpus'
import { asOrderRef, phraseBonus, scoreFields, terms } from './match'

/**
 * How many results a query is allowed to return.
 *
 * <p>Nine, not "all of them". A search box that returns thirty rows has moved the
 * problem from finding the page to reading a list, and on a storefront the honest
 * answer to a vague query is a short list plus the search page — not everything that
 * shares a word.
 */
const LIMIT = 9

/**
 * Tie-break order, used only when two groups lead with the same score.
 *
 * <p>Groups are otherwise ordered by their best member. A fixed order looks tidier but
 * buries the answer: searching "refund" put the Help centre *page* above the FAQ that
 * literally answers the question, purely because pages outranked answers in this list.
 * Relevance decides which heading comes first; this only settles ties.
 */
const GROUP_ORDER: SearchGroup[] = ['action', 'page', 'service', 'faq']

export interface ResultGroup {
  group: SearchGroup
  label: string
  entries: SearchEntry[]
}

export interface SearchOutcome {
  /** Flat, in render order — this is what the arrow keys walk. */
  flat: SearchEntry[]
  groups: ResultGroup[]
  /** Folded query terms, handed to the highlighter. */
  queryTerms: string[]
}

export function useSearch(query: string): SearchOutcome {
  const t = useT()
  const corpus = useSearchCorpus()

  return useMemo(() => {
    const queryTerms = terms(query)
    if (queryTerms.length === 0) {
      return { flat: [], groups: [], queryTerms }
    }

    const scored: { entry: SearchEntry; score: number }[] = []

    /*
     * An order reference is answered before the text index is even consulted.
     *
     * Someone pasting GFS-26-BWG6NGG3 is not browsing — they want the tracker, now.
     * No amount of matching would find it either: the reference lives in their inbox,
     * not in the corpus.
     */
    const ref = asOrderRef(query)
    if (ref) {
      scored.push({
        score: Number.POSITIVE_INFINITY,
        entry: {
          id: `action-track-${ref}`,
          group: 'action',
          title: t.search.trackOrder(ref),
          body: t.search.trackOrderHint,
          to: `/track?ref=${encodeURIComponent(ref)}`,
          fields: [],
          foldedTitle: '',
        },
      })
    }

    for (const candidate of corpus) {
      const base = scoreFields(queryTerms, candidate.fields)
      if (base === 0) continue
      scored.push({
        entry: candidate,
        score: base + phraseBonus(query, candidate.foldedTitle),
      })
    }

    scored.sort((a, b) => b.score - a.score)
    const flatByScore = scored.slice(0, LIMIT).map((s) => s.entry)

    const label: Record<SearchGroup, string> = {
      action: t.search.groupActions,
      page: t.search.groupPages,
      service: t.search.groupServices,
      faq: t.search.groupFaq,
    }

    const groups: ResultGroup[] = []
    for (const g of GROUP_ORDER) {
      const entries = flatByScore.filter((e) => e.group === g)
      if (entries.length) groups.push({ group: g, label: label[g], entries })
    }
    // Best-scoring group first. `flatByScore` is already sorted, so a group's position
    // in it is its best member's rank.
    groups.sort(
      (a, b) => flatByScore.indexOf(a.entries[0]!) - flatByScore.indexOf(b.entries[0]!),
    )

    // Rebuilt from the grouped view so the keyboard order is exactly the reading
    // order. Walking score order while the eye walks group order is the bug that
    // makes an otherwise good palette feel haunted.
    const flat = groups.flatMap((g) => g.entries)

    return { flat, groups, queryTerms }
  }, [query, corpus, t])
}
