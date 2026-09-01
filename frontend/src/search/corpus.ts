import { useMemo } from 'react'
import { useT } from '../i18n'
import { useCatalog } from '../state/CatalogContext'
import { SEASON } from '../lib/seo'
import { useFaq } from '../content/faq'
import { fold, type FoldedField, FIELD } from './match'

/**
 * Everything the search can find, assembled from what the site already knows.
 *
 * <p><b>Why this is not a backend endpoint.</b> The whole corpus is about forty short
 * records and it is already in memory — the routes are in the router, the answers are
 * in the dictionary, the services arrive with the catalogue. A `/api/v1/search` would
 * add a network round trip to every keystroke, a second place for the copy to live, and
 * the problem of translating results server-side for a dictionary that only exists in
 * the client. Searching locally is instant, works on a stalled connection, and cannot
 * disagree with the page it is searching.
 *
 * <p><b>Why it is built from the dictionary rather than a static list.</b> A hardcoded
 * English index would quietly stop working the moment a visitor switched to Spanish —
 * they would type Spanish and match nothing, which reads as "this site has no search"
 * rather than "this search is monolingual". Everything below re-derives when the
 * language changes.
 */

export type SearchGroup = 'action' | 'page' | 'faq' | 'service'

export interface SearchEntry {
  id: string
  group: SearchGroup
  /** The short, recognisable name — what the result row shows. */
  title: string
  /** One line of supporting context, shown under the title. */
  body: string
  /** Right-aligned hint: a price, a status. Optional. */
  meta?: string
  to: string
  /** Pre-folded fields, so a keystroke never re-folds the corpus. */
  fields: FoldedField[]
  foldedTitle: string
}

function entry(
  id: string,
  group: SearchGroup,
  title: string,
  body: string,
  keywords: string,
  to: string,
  meta?: string,
): SearchEntry {
  const foldedTitle = fold(title)
  return {
    id, group, title, body, to, meta, foldedTitle,
    fields: [
      { folded: foldedTitle, weight: FIELD.title },
      { folded: fold(keywords), weight: FIELD.keywords },
      { folded: fold(body), weight: FIELD.body },
    ],
  }
}

/**
 * Which page a service's card should send you to.
 *
 * <p>An unknown SKU falls through to the trading configurator rather than being dropped
 * from the index. A service the catalogue is selling but this map has not heard of is a
 * deploy-order problem, and hiding it from search is the worst available response —
 * better to land the customer one click away than nowhere.
 */
const SKU_ROUTE: Record<string, string> = {
  TRADING_SERVICE: '/order',
  COACHING: '/coaching',
  BOOST_CHAMPS: '/boosting',
  BOOST_RIVALS: '/boosting',
  CARDS: '/cards',
}

export function useSearchCorpus(): SearchEntry[] {
  const t = useT()
  const { catalog } = useCatalog()
  const faq = useFaq()

  return useMemo(() => {
    const p = t.search.pages
    const out: SearchEntry[] = []

    /* -------------------------------------------------------------- pages --- */
    out.push(
      entry('page-home', 'page', p.home.label, t.home.seoDescription(SEASON), p.home.keywords, '/'),
      entry('page-order', 'page', p.order.label, t.order.seoDescription, p.order.keywords, '/order'),
      entry('page-boosting', 'page', p.boosting.label, t.boosting.seoDescription(SEASON), p.boosting.keywords, '/boosting'),
      entry('page-coaching', 'page', p.coaching.label, t.coaching.seoDescription(SEASON), p.coaching.keywords, '/coaching'),
      entry('page-rewards', 'page', p.rewards.label, t.rewards.seoDescription, p.rewards.keywords, '/rewards'),
      entry('page-track', 'page', p.track.label, t.track.lead, p.track.keywords, '/track'),
      entry('page-help', 'page', p.help.label, t.help.seoDescription, p.help.keywords, '/help'),
      entry('page-support', 'page', p.support.label, t.support.seoDescription, p.support.keywords, '/support'),
      // These six have no SEO description to borrow, so they carry a purpose-written
      // blurb. Passing the keyword string as the body — which this did at first —
      // printed "cards player cards icons coming soon" under the title in the results
      // list. Keywords are matching material and must never reach the screen.
      entry('page-account', 'page', p.account.label, p.account.blurb, p.account.keywords, '/account'),
      entry('page-login', 'page', p.login.label, p.login.blurb, p.login.keywords, '/login'),
      entry('page-cards', 'page', p.cards.label, p.cards.blurb, p.cards.keywords, '/cards'),
      entry('page-terms', 'page', p.terms.label, p.terms.blurb, p.terms.keywords, '/terms'),
      entry('page-privacy', 'page', p.privacy.label, p.privacy.blurb, p.privacy.keywords, '/privacy'),
      entry('page-aml', 'page', p.aml.label, p.aml.blurb, p.aml.keywords, '/aml-kyc'),
    )

    /* ---------------------------------------------------------------- faq --- */
    for (const group of faq) {
      for (const item of group.items) {
        out.push(
          entry(
            `faq-${item.key}`,
            'faq',
            item.question,
            item.answer,
            group.title,
            `/help#faq-${item.key}`,
          ),
        )
      }
    }

    /* ----------------------------------------------------------- services --- */
    // Only what is actually for sale. Indexing an unsellable SKU sends someone to a
    // page that cannot take their order, which is a worse outcome than not finding it.
    for (const service of catalog?.services ?? []) {
      if (!service.sellable) continue

      const platforms = service.options
        .map((o) => o.label ?? o.platform ?? '')
        .filter(Boolean)
      const cheapest = service.options.reduce<string | undefined>(
        (best, o) => (best === undefined ? o.unitPriceFormatted : best),
        undefined,
      )

      /*
       * A service is described by its own words, not the storefront's.
       *
       * This first spliced the trading page's whole keyword list onto every service,
       * which made "trading" return FUT Coaching and Champs Boosting alongside the
       * actual trading service. A service's SKU, its name and its platforms are what
       * identify it; the generic buying vocabulary belongs to the page that sells it.
       */
      out.push(
        entry(
          `service-${service.sku}`,
          'service',
          service.displayName,
          platforms.join(' · ') || t.search.pages.order.label,
          `${service.sku.replace(/_/g, ' ')} ${platforms.join(' ')}`,
          SKU_ROUTE[service.sku] ?? '/order',
          cheapest,
        ),
      )
    }

    return out
  }, [t, catalog, faq])
}
