import { useEffect, useState } from 'react'
import { api } from '../lib/api'
import { countryCode, countryFromLocale } from '../lib/countries'
import type { Dictionary } from '../i18n/en'

/**
 * Whether International is "International / Cards" for this customer: a card method is
 * offered in their country. The server decides from what Payop lists and the fee table
 * prices; nothing here assumes cards exist.
 *
 * <p>The country is the one the International panel starts from: India for an INR order --
 * the customer can change it there -- and otherwise Cloudflare's guess, then the browser's.
 */
export function useInternationalOffer(orderCurrency: string | null | undefined): { cards: boolean } {
  const [cards, setCards] = useState(false)
  useEffect(() => {
    let live = true
    const country: Promise<string | null> = orderCurrency === 'INR' ? Promise.resolve('IN')
      : api.get<{ country: string | null }>('/api/v1/payments/payop/country')
        .then((found) => countryCode(found?.country))
        .catch(() => null)
        .then((fromIp) => fromIp ?? countryFromLocale(window.navigator.language))
    country
      .then((code) => (code ? api.get<{ cards?: boolean }>(`/api/v1/payments/payop/offer?country=${encodeURIComponent(code)}`) : null))
      .then((offer) => { if (live) setCards(offer?.cards === true) })
      .catch(() => { if (live) setCards(false) })
    return () => { live = false }
  }, [orderCurrency])
  return { cards }
}

/** International's name and the line under it: with cards only where a card method is offered. */
export function internationalCopy(t: Dictionary, cards: boolean): { title: string; body: string } {
  return cards
    ? { title: t.order.payTabInternationalCards, body: t.order.payIntlChoiceCards }
    : { title: t.order.payTabInternational, body: t.order.payIntlChoiceLocal }
}
