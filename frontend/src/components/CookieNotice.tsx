import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Button } from './ui'
import { useT } from '../i18n'
import { readConsent, setConsent } from '../lib/consent'

/**
 * The first-visit cookie choice.
 *
 * <p><b>A bar, not a modal.</b> Nothing behind it is unsafe to read, and a full-screen
 * dialog over a price list on a first visit is a cost paid by every customer to answer a
 * question about two stored preferences. It sits at the bottom, takes the keyboard in
 * order, and does not trap focus.
 *
 * <p><b>Both buttons do something.</b> Accept lets the browser remember the language and
 * currency; decline means it does not, and clears anything already stored. The sign-in
 * cookie is not on the table either way -- it is set only when somebody signs in, exists
 * to keep them signed in, and a site cannot offer to switch off the thing that makes
 * signing in work.
 *
 * <p>It renders once a choice is missing and disappears the moment one is made. Declined
 * counts as made: a banner that returns after "no" is asking until it gets the answer it
 * wants.
 */
export function CookieNotice() {
  const t = useT()
  const [answered, setAnswered] = useState(true)

  /*
   * Read after mount rather than during render. The first paint is then identical for
   * everybody, and a visitor who has already answered never sees the bar appear and
   * vanish -- localStorage is synchronous, so this runs before the browser paints again.
   */
  useEffect(() => {
    setAnswered(readConsent() !== null)
  }, [])

  if (answered) return null

  const answer = (choice: 'accepted' | 'declined') => {
    setConsent(choice)
    setAnswered(true)
  }

  return (
    <div
      role="region"
      aria-label={t.cookies.title}
      className="fixed inset-x-0 bottom-0 z-[60] border-t border-ink-400 bg-paper/95 backdrop-blur
                 supports-[backdrop-filter]:bg-paper/90"
    >
      <div className="mx-auto flex max-w-[1320px] flex-col gap-4 px-5 py-4 sm:px-8 lg:flex-row
                      lg:items-center lg:gap-6 lg:px-10">
        <div className="lg:min-w-0 lg:flex-1">
          <p className="text-[13px] font-semibold text-chalk">{t.cookies.title}</p>
          <p className="mt-1 text-[12.5px] leading-relaxed text-chalk-muted">
            {t.cookies.body}{' '}
            <Link to="/privacy" className="font-semibold text-brand-400 underline-offset-2 hover:underline">
              {t.cookies.policyLink}
            </Link>
          </p>
        </div>

        <div className="flex shrink-0 gap-3">
          <Button variant="secondary" onClick={() => answer('declined')}>
            {t.cookies.decline}
          </Button>
          <Button onClick={() => answer('accepted')}>{t.cookies.accept}</Button>
        </div>
      </div>
    </div>
  )
}
