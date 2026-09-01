import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import en, { type Dictionary } from './en'
import es from './es'
import fr from './fr'

/**
 * Interface translation, hand-rolled.
 *
 * <p>No i18n library. This storefront translates a fixed set of interface strings into
 * three known locales — it does not need runtime loading, pluralisation rules or
 * ICU message syntax, and a dependency that provides them would also provide the
 * silent-fallback behaviour this deliberately avoids. Everything is typed against the
 * English dictionary, so a missing key is a build failure.
 *
 * <p><b>Scope.</b> Interface only. The Terms, Privacy Policy and AML & KYC pages stay
 * in English and say so in the footer. Those are contractual documents: an unreviewed
 * translation of a refund window or a guarantee clause is a liability, not a feature,
 * and which language governs has to be stated rather than implied.
 */
export const LANGUAGES = [
  { code: 'en', label: 'English', short: 'EN' },
  { code: 'es', label: 'Español', short: 'ES' },
  { code: 'fr', label: 'Français', short: 'FR' },
] as const

export type Language = (typeof LANGUAGES)[number]['code']

const DICTIONARIES: Record<Language, Dictionary> = { en, es, fr }

const STORAGE_KEY = 'gfs.language'

type I18nValue = {
  lang: Language
  setLang: (lang: Language) => void
  t: Dictionary
}

const I18nContext = createContext<I18nValue>({ lang: 'en', setLang: () => {}, t: en })

function isLanguage(value: string | null): value is Language {
  return value === 'en' || value === 'es' || value === 'fr'
}

/**
 * The initial language: an explicit past choice first, then the browser's preference,
 * then English.
 *
 * <p>A stored choice always wins. Someone who switched to English on a Spanish-locale
 * machine meant it, and re-guessing from the browser on every visit would undo their
 * decision on every page load.
 */
function initialLanguage(): Language {
  if (typeof window === 'undefined') return 'en'

  const stored = window.localStorage.getItem(STORAGE_KEY)
  if (isLanguage(stored)) return stored

  for (const preference of window.navigator.languages ?? []) {
    const base = preference.split('-')[0]
    if (isLanguage(base ?? null)) return base as Language
  }
  return 'en'
}

export function I18nProvider({ children }: { children: ReactNode }) {
  const [lang, setLangState] = useState<Language>(initialLanguage)

  const setLang = useCallback((next: Language) => {
    setLangState(next)
    try {
      window.localStorage.setItem(STORAGE_KEY, next)
    } catch {
      // Private browsing denies localStorage. The choice still applies for this
      // session; losing it on reload is a far better outcome than a crash.
    }
  }, [])

  // Keep the document in step: screen readers announce content in the declared
  // language, and getting this wrong makes a Spanish page read aloud in English.
  useEffect(() => {
    document.documentElement.lang = lang
  }, [lang])

  const value = useMemo<I18nValue>(
    () => ({ lang, setLang, t: DICTIONARIES[lang] }),
    [lang, setLang],
  )

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>
}

/** Strings for the active language, plus the switcher. */
export function useI18n(): I18nValue {
  return useContext(I18nContext)
}

/** Shorthand for the common case of only needing the strings. */
export function useT(): Dictionary {
  return useContext(I18nContext).t
}
