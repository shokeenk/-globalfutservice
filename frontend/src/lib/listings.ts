import { api } from './api'
import type { CatalogListing, ListingSku } from './types'

/**
 * Objectives and Tournaments: services the GFS team writes listing by listing in Service
 * Listings, each with its own title, artwork, tasks and price, fixed or per hour.
 *
 * <p>A listing's rate card variant is `LISTING_<id>`, and its id is what the Boosting page
 * passes to checkout.
 */
export const LISTING_SKUS: readonly ListingSku[] = ['BOOST_OBJECTIVES', 'BOOST_TOURNAMENTS']

export function isListingSku(sku: string | null | undefined): sku is ListingSku {
  return sku === 'BOOST_OBJECTIVES' || sku === 'BOOST_TOURNAMENTS'
}

export function listingVariant(id: number | string): string {
  return `LISTING_${id}`
}

/** "LISTING_12" -> 12; null for anything else. */
export function listingIdOf(variant: string | null | undefined): number | null {
  const match = /^LISTING_(\d+)$/.exec(variant ?? '')
  return match ? Number(match[1]) : null
}

export function isHourly(listing: Pick<CatalogListing, 'pricingType'> | null | undefined): boolean {
  return listing?.pricingType === 'PER_HOUR'
}

/**
 * A listing image's address as the browser fetches it: the API serves the artwork, and in
 * production the API is its own host.
 */
export function listingImageSrc(path: string | null | undefined): string | null {
  if (!path) return null
  if (/^(https?:|data:)/.test(path)) return path
  return `${api.baseUrl ?? ''}${path}`
}
