import type { CoinPricingLimits, CoinStructure } from '../../../lib/types'

/**
 * The Coin rates page's arithmetic and checks, kept apart from the page so they can be
 * read, and tested, on their own.
 *
 * <p>Prices are typed per 100,000 coins, the unit the business quotes in, and the server
 * stores them per million, the unit its engine multiplies. Every price on the page shows
 * both. The checks here are the server's, word for word, so an error appears under the
 * field it is about before Save is pressed; the server checks everything again.
 */

export const CURRENCY_SYMBOL: Record<string, string> = { INR: '₹', USD: '$', EUR: '€', GBP: '£' }

/** A bracket as its two inputs hold it. */
export interface BracketDraft {
  fromK: string
  per100k: string
}

export interface CurrencyDraft {
  base: string
  brackets: BracketDraft[]
}

/** A structure as the page's inputs hold it: text, as typed. */
export interface StructureDraft {
  minK: string
  maxK: string
  stepK: string
  /** Comma-separated amounts in K: "50, 100, 250". */
  picks: string
  rates: Record<string, CurrencyDraft>
}

// ------------------------------------------------------------------ amounts and money

/** 50 -> "50K", 1000 -> "1M", 1500 -> "1.5M": how the slider says an amount. */
export function describeK(k: number): string {
  return k >= 1000 ? `${Number((k / 1000).toFixed(3))}M` : `${k}K`
}

/** A minor amount as the server formats it: Indian grouping for rupees, two decimals. */
export function formatMinor(minor: number, currency: string): string {
  const major = minor / 100
  return (CURRENCY_SYMBOL[currency] ?? '') + major.toLocaleString(currency === 'INR' ? 'en-IN' : 'en-US', {
    minimumFractionDigits: 2, maximumFractionDigits: 2,
  })
}

/**
 * A typed price per 100K as minor units per million, or null when it is not a price.
 * At most three decimals: anything finer cannot be stored per million.
 */
export function perMillionMinor(per100k: string): number | null {
  const clean = per100k.replace(/[,\s]/g, '')
  if (!/^\d+(\.\d{1,3})?$/.test(clean)) return null
  return Math.round(Number(clean) * 1000)
}

/** "₹13,000.00 per 1M" beside a typed price; a dash until it is a price. */
export function perMillionText(per100k: string, currency: string): string {
  const amount = perMillionAmount(per100k, currency)
  return amount === '—' ? amount : `${amount} per 1M`
}

/** The per-million amount alone, for a column already headed "Per 1M". */
export function perMillionAmount(per100k: string, currency: string): string {
  const minor = perMillionMinor(per100k)
  return minor === null || minor <= 0 ? '—' : formatMinor(minor, currency)
}

/** A price per 100K, from its per-million minor units. */
export function per100kText(perMillion: number, currency: string): string {
  return perMillion % 10 === 0
    ? formatMinor(perMillion / 10, currency)
    : `${CURRENCY_SYMBOL[currency] ?? ''}${(perMillion / 1000).toString()}`
}

/** Whether a 10K step lands on a whole cent at this price; when not, the page says so. */
export function stepIsWholeMinorUnit(perMillion: number): boolean {
  return perMillion % 100 === 0
}

// ------------------------------------------------------------------ to and from the server

export function toDraft(s: CoinStructure | null, limits: CoinPricingLimits, currencies: string[]): StructureDraft {
  const rates: Record<string, CurrencyDraft> = {}
  for (const currency of currencies) {
    const live = s?.rates.find((r) => r.currency === currency)
    rates[currency] = {
      base: live?.base ? live.base.per100k : '',
      brackets: (live?.brackets ?? []).map((b) => ({ fromK: String(b.fromK), per100k: b.per100k })),
    }
  }
  const minK = s?.minK ?? limits.vendorMinTransferK
  const maxK = s?.maxK ?? 1000
  const stepK = s?.stepK ?? limits.smallestStepK
  const picks = s?.quickPicksK
    ?? limits.defaultQuickPicksK.filter((k) => k >= minK && k <= maxK && (k - minK) % stepK === 0)
  return { minK: String(minK), maxK: String(maxK), stepK: String(stepK), picks: picks.join(', '), rates }
}

/** The page's draft as the server reads it. Only called once the draft has no errors. */
export function toRequest(d: StructureDraft, previewK?: number[]) {
  return {
    minK: wholeK(d.minK),
    maxK: wholeK(d.maxK),
    stepK: wholeK(d.stepK),
    quickPicksK: parsePicks(d.picks) ?? [],
    rates: Object.entries(d.rates).map(([currency, r]) => ({
      currency,
      per100k: r.base.replace(/[,\s]/g, ''),
      brackets: r.brackets.map((b) => ({ fromK: wholeK(b.fromK), per100k: b.per100k.replace(/[,\s]/g, '') })),
    })),
    ...(previewK ? { previewK } : {}),
  }
}

function wholeK(text: string): number | null {
  const clean = text.trim()
  return /^\d+$/.test(clean) ? Number(clean) : null
}

/** "50, 100 250" -> [50, 100, 250]; null when any entry is not a whole number. */
export function parsePicks(text: string): number[] | null {
  const parts = text.split(/[,\s]+/).map((p) => p.trim()).filter(Boolean)
  const out: number[] = []
  for (const p of parts) {
    if (!/^\d+$/.test(p)) return null
    out.push(Number(p))
  }
  return out
}

// ------------------------------------------------------------------ checks

export interface BracketErrors {
  fromK?: string
  per100k?: string
}

export interface StructureErrors {
  minK?: string
  maxK?: string
  stepK?: string
  picks?: string
  rates: Record<string, { base?: string; list?: string; brackets: Record<number, BracketErrors> }>
}

/** Everything wrong with a draft, by the field it is about, in the server's own words. */
export function validate(d: StructureDraft, limits: CoinPricingLimits): StructureErrors {
  const errors: StructureErrors = { rates: {} }
  const step = wholeK(d.stepK)
  const min = wholeK(d.minK)
  const max = wholeK(d.maxK)
  const smallest = limits.smallestStepK

  if (step === null) errors.stepK = 'Enter the step in K, e.g. 10.'
  else if (step < smallest || step % smallest !== 0) errors.stepK = 'The step must be a whole multiple of 10K, at least 10K.'
  if (min === null) errors.minK = 'Enter the minimum in K, e.g. 50.'
  else if (min < smallest || min % smallest !== 0) errors.minK = 'The minimum must be a whole multiple of 10K, at least 10K.'
  if (max === null) errors.maxK = 'Enter the maximum in K, e.g. 1000.'
  else if (max > limits.maxCapK) errors.maxK = `The maximum can be at most ${describeK(limits.maxCapK)}.`
  else if (min !== null && max < min) errors.maxK = 'The maximum must be at least the minimum.'

  const rangeOk = !errors.stepK && !errors.minK && !errors.maxK
  if (rangeOk && (max! - min!) % step! !== 0) {
    const below = max! - ((max! - min!) % step!)
    errors.maxK = `The maximum must be the minimum plus whole steps of ${describeK(step!)}: `
      + `${describeK(below)} or ${describeK(below + step!)}, not ${describeK(max!)}.`
  }
  const sliderOk = rangeOk && !errors.maxK

  /** Why `k` is not an amount the slider stops on, if it is not. */
  const offSlider = (what: string, k: number, minIncluded: boolean): string | undefined => {
    if (!sliderOk) return undefined
    if (k < min! || (!minIncluded && k === min)) return `${what} ${describeK(k)} is below the minimum (${describeK(min!)}).`
    if (k > max!) return `${what} ${describeK(k)} is above the maximum (${describeK(max!)}).`
    if ((k - min!) % step! !== 0) {
      const below = k - ((k - min!) % step!)
      return `${what} ${describeK(k)} is not on a step: the slider goes ${describeK(below)}, ${describeK(below + step!)}…`
    }
    return undefined
  }

  const picks = parsePicks(d.picks)
  if (picks === null) {
    errors.picks = 'Quick picks are amounts in K, separated by commas: 50, 100, 250.'
  } else if (picks.length > limits.maxQuickPicks) {
    errors.picks = `At most ${limits.maxQuickPicks} quick picks.`
  } else {
    const seen = new Set<number>()
    for (const k of picks) {
      const problem = seen.has(k) ? `Quick pick ${describeK(k)} is listed twice.` : offSlider('Quick pick', k, true)
      seen.add(k)
      if (problem) {
        errors.picks = problem
        break
      }
    }
  }

  for (const [currency, r] of Object.entries(d.rates)) {
    const own: StructureErrors['rates'][string] = { brackets: {} }
    own.base = priceProblem(r.base, currency, `Set a base price for ${currency}.`)
    if (r.brackets.length > limits.maxBrackets) own.list = `At most ${limits.maxBrackets} brackets for ${currency}.`
    const starts = new Set<number>()
    r.brackets.forEach((b, i) => {
      const e: BracketErrors = {}
      const from = wholeK(b.fromK)
      if (from === null) e.fromK = 'Enter where the bracket starts, in K.'
      else if (starts.has(from)) e.fromK = `Two brackets start at ${describeK(from)}.`
      else if (sliderOk && from <= min!) {
        e.fromK = `A bracket must start above the minimum (${describeK(min!)}); the base price covers the minimum.`
      } else e.fromK = offSlider('The bracket from', from, false)
      if (from !== null) starts.add(from)
      e.per100k = priceProblem(b.per100k, currency, "Set this bracket's price per 100K.")
      if (e.fromK || e.per100k) own.brackets[i] = e
    })
    if (own.base || own.list || Object.keys(own.brackets).length > 0) errors.rates[currency] = own
  }
  return errors
}

function priceProblem(text: string, currency: string, whenEmpty: string): string | undefined {
  if (text.trim() === '') return whenEmpty
  const minor = perMillionMinor(text)
  if (minor === null) return 'Enter a price per 100K, e.g. 1300 or 14.30 (at most three decimals).'
  if (minor <= 0) return `The ${currency} price must be greater than zero.`
  return undefined
}

export function hasErrors(e: StructureErrors): boolean {
  return Boolean(e.minK || e.maxK || e.stepK || e.picks) || Object.keys(e.rates).length > 0
}

// ------------------------------------------------------------------ what a save changes

/**
 * The difference between what is live and what would be saved, in sentences, for the
 * confirmation: the range, the quick picks, and every price that moves, per 100K with
 * per 1M beside it. Prices are compared as amounts, so "1300" and "1300.00" are no change.
 */
export function describeChanges(live: CoinStructure | null, d: StructureDraft): string[] {
  const out: string[] = []
  const range: [string, number | undefined, string][] = [
    ['Minimum', live?.minK, d.minK], ['Maximum', live?.maxK, d.maxK], ['Step', live?.stepK, d.stepK],
  ]
  for (const [name, was, now] of range) {
    const next = wholeK(now)
    if (next !== null && was !== next) {
      out.push(was === undefined ? `${name}: ${describeK(next)}` : `${name}: ${describeK(was)} → ${describeK(next)}`)
    }
  }
  const picks = parsePicks(d.picks) ?? []
  const livePicks = live?.quickPicksK ?? []
  if (picks.join(',') !== livePicks.join(',')) {
    out.push(`Quick picks: ${list(livePicks)} → ${list(picks)}`)
  }

  for (const [currency, r] of Object.entries(d.rates)) {
    const was = live?.rates.find((x) => x.currency === currency)
    const base = perMillionMinor(r.base)
    if (base !== null && base !== was?.base?.perMillionMinor) {
      out.push(was?.base
        ? `${currency} base price: ${pair(was.base.perMillionMinor, base, currency)}`
        : `${currency} base price set: ${single(base, currency)}`)
    }
    const before = new Map((was?.brackets ?? []).map((b) => [b.fromK, b.perMillionMinor]))
    const after = new Map<number, number>()
    for (const b of r.brackets) {
      const from = wholeK(b.fromK)
      const price = perMillionMinor(b.per100k)
      if (from !== null && price !== null) after.set(from, price)
    }
    for (const [from, price] of [...after].sort((a, b) => a[0] - b[0])) {
      const old = before.get(from)
      if (old === undefined) out.push(`${currency}: new bracket from ${describeK(from)} at ${single(price, currency)}`)
      else if (old !== price) out.push(`${currency}: from ${describeK(from)}, ${pair(old, price, currency)}`)
    }
    for (const [from, price] of [...before].sort((a, b) => a[0] - b[0])) {
      if (!after.has(from)) {
        out.push(`${currency}: bracket from ${describeK(from)} removed (was ${single(price, currency)})`)
      }
    }
  }
  return out
}

function list(ks: number[]): string {
  return ks.length === 0 ? 'none' : ks.map(describeK).join(', ')
}

function single(perMillion: number, currency: string): string {
  return `${per100kText(perMillion, currency)} per 100K (${formatMinor(perMillion, currency)} per 1M)`
}

function pair(was: number, now: number, currency: string): string {
  return `${per100kText(was, currency)} → ${per100kText(now, currency)} per 100K `
    + `(${formatMinor(was, currency)} → ${formatMinor(now, currency)} per 1M)`
}
