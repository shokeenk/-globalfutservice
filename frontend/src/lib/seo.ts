import { useEffect } from 'react'

/**
 * Per-route title, description and indexability.
 *
 * A single-page app renders one HTML document, so without this every route shares
 * the shell's metadata. That matters here beyond tidiness: the transactional
 * routes must carry `noindex`. A price configurator generates an unbounded number
 * of near-duplicate URL states, and an order-tracking page has nothing a search
 * engine should ever be holding.
 *
 * The season is a variable rather than a literal for a specific reason: the site
 * this business is modelled on still has "FC 25" in its meta description while
 * every heading says FC 26. That is what hard-coding a season costs, every
 * September.
 */
export const SEASON = 'FC 26'

type SeoOptions = {
  title: string
  description?: string
  noindex?: boolean
}

export function useSeo({ title, description, noindex = false }: SeoOptions) {
  useEffect(() => {
    document.title = `${title} — Global FUT Services`

    if (description) {
      setMeta('name', 'description', description)
      setMeta('property', 'og:description', description)
    }
    setMeta('property', 'og:title', title)

    // Robots is set on every route, not only the private ones, so navigating from
    // a noindex route back to a public one restores indexability.
    setMeta('name', 'robots', noindex ? 'noindex, nofollow' : 'index, follow')
  }, [title, description, noindex])
}

function setMeta(keyAttr: 'name' | 'property', key: string, value: string) {
  let tag = document.head.querySelector<HTMLMetaElement>(`meta[${keyAttr}="${key}"]`)
  if (!tag) {
    tag = document.createElement('meta')
    tag.setAttribute(keyAttr, key)
    document.head.appendChild(tag)
  }
  tag.setAttribute('content', value)
}
