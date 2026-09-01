import { LANGUAGES, useI18n, type Language } from '../i18n'
import { useCatalog } from '../state/CatalogContext'

/**
 * Language and currency pickers.
 *
 * <p>Native `<select>` elements on purpose. A custom dropdown here would mean
 * reimplementing keyboard navigation, type-ahead, focus trapping and the mobile
 * picker — four things the platform already does correctly and that a hand-rolled
 * menu usually gets wrong for exactly the users who most need them.
 *
 * <p>The two are deliberately independent. Currency follows where a customer's money
 * is, language follows what they read; a Spanish speaker paying in pounds is an
 * ordinary customer, not an edge case, and coupling them would make that unbookable.
 */

const CURRENCY_LABELS: Record<string, string> = {
  INR: '₹ INR',
  USD: '$ USD',
  EUR: '€ EUR',
  GBP: '£ GBP',
  AED: 'AED',
}

const SELECT_CLASS =
  'cursor-pointer rounded-lg border border-ink-400 bg-ink-700 px-2.5 py-1.5 text-[12.5px] ' +
  'font-medium text-chalk-muted transition-colors hover:text-chalk ' +
  'focus:outline-none focus:ring-2 focus:ring-brand-500 focus:ring-offset-2 focus:ring-offset-ink'

export function LanguageSwitcher({ className = '' }: { className?: string }) {
  const { lang, setLang, t } = useI18n()

  return (
    <label className={`inline-flex items-center ${className}`}>
      <span className="sr-only">{t.nav.language}</span>
      <select
        value={lang}
        onChange={(e) => setLang(e.target.value as Language)}
        className={SELECT_CLASS}
      >
        {LANGUAGES.map((option) => (
          <option key={option.code} value={option.code}>
            {option.short}
          </option>
        ))}
      </select>
    </label>
  )
}

export function CurrencySwitcher({ className = '' }: { className?: string }) {
  const { t } = useI18n()
  const { catalog, currency, setCurrency } = useCatalog()

  /*
   * Only currencies the server says are available. That list is the intersection of
   * the configured allow-list with the currencies that actually have live rate cards,
   * so a currency can never appear here without prices behind it — which is what stops
   * the picker offering a checkout that cannot be completed.
   */
  const available = catalog?.availableCurrencies ?? []
  if (available.length < 2) {
    return null
  }

  return (
    <label className={`inline-flex items-center ${className}`}>
      <span className="sr-only">{t.nav.currency}</span>
      <select
        value={currency}
        onChange={(e) => setCurrency(e.target.value)}
        className={SELECT_CLASS}
      >
        {available.map((code) => (
          <option key={code} value={code}>
            {CURRENCY_LABELS[code] ?? code}
          </option>
        ))}
      </select>
    </label>
  )
}
