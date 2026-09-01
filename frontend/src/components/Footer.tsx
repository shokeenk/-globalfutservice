import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { BrandBadge, Logo } from '../brand/Logo'
import { useT } from '../i18n'
import { useCatalog } from '../state/CatalogContext'
import { ButtonLink } from './ui'

export function Footer() {
  const { policy } = useCatalog()
  const t = useT()
  const year = new Date().getFullYear()

  // Built inside the component so the labels follow the language.
  const COLUMNS = [
    {
      // Not `t.nav.trading`: that is the label of the first link inside this column, so
      // the heading and its own first entry both read "Trading". The column lists four
      // services, and that is what it should be called.
      title: t.footer.services,
      links: [
        { to: '/order', label: t.nav.trading },
        { to: '/boosting', label: t.nav.boosting },
        { to: '/cards', label: t.footer.cards },
        { to: '/coaching', label: t.footer.futClasses },
      ],
    },
    {
      title: t.footer.menu,
      links: [
        { to: '/rewards', label: t.footer.rewards },
        { to: '/help', label: t.footer.help },
        { to: '/support', label: t.footer.support },
      ],
    },
    {
      title: t.footer.legal,
      links: [
        { to: '/terms', label: t.footer.terms },
        { to: '/privacy', label: t.footer.privacy },
        { to: '/aml-kyc', label: t.footer.aml },
        { to: '/track', label: t.nav.track },
      ],
    },
  ]

  return (
    <footer className="relative z-10 overflow-hidden border-t border-ink-400 bg-ink-800">
      <div className="mx-auto max-w-[1320px] px-5 pb-0 pt-16 sm:px-8 lg:px-10 lg:pt-20">
        <div className="grid gap-12 md:grid-cols-[1.5fr_repeat(3,1fr)] md:gap-10">
          <div>
            {/* Badge above the wordmark rather than beside it: the footer is the
                one place with vertical room to show the mark at a size where it
                is the actual crest and not a bullet point. */}
            <BrandBadge size={88} aura className="mb-5" />
            {/* Wordmark only — the crest is already above it at 88px, and `Logo`
                in its default form would draw it a second time at 38px. */}
            <Logo variant="wordmark" />
            <p className="mt-5 max-w-xs text-[13px] leading-relaxed text-chalk-faint">
              {t.footer.tagline}
            </p>

            <ButtonLink to="/order" size="sm" className="mt-6">
              {t.nav.buyCoins}
            </ButtonLink>

            <div className="mt-7 flex gap-2.5">
              <SocialLink
                href="https://www.youtube.com/@FCvinuhunter"
                label="Global FUT Services on YouTube"
              >
                <path d="M22.5 6.9a2.8 2.8 0 0 0-2-2C18.8 4.5 12 4.5 12 4.5s-6.8 0-8.5.4a2.8 2.8 0 0 0-2 2A29 29 0 0 0 1.1 12a29 29 0 0 0 .4 5.1 2.8 2.8 0 0 0 2 2c1.7.4 8.5.4 8.5.4s6.8 0 8.5-.4a2.8 2.8 0 0 0 2-2 29 29 0 0 0 .4-5.1 29 29 0 0 0-.4-5.1Z" />
                <path d="m9.8 15.3 5.7-3.3-5.7-3.3Z" fill="#111114" stroke="none" />
              </SocialLink>
              <SocialLink
                href="https://www.instagram.com/global_fut_services/"
                label="Global FUT Services on Instagram"
              >
                <rect x="3" y="3" width="18" height="18" rx="5" />
                <circle cx="12" cy="12" r="4" />
                <circle cx="17.5" cy="6.5" r="1" fill="currentColor" />
              </SocialLink>
              {/* `?s=11` stripped from the supplied URL — it is the X mobile app's
                  share-source parameter and does nothing outside that app. */}
              <SocialLink
                href="https://x.com/globlfutservice"
                label="Global FUT Services on X"
              >
                <path d="m4 4 7.2 9.3L4.5 20h2.1l5.4-5.9 4.6 5.9H20l-7.5-9.7L19.5 4h-2.1l-5 5.4L8.3 4Z" />
              </SocialLink>
            </div>
          </div>

          {COLUMNS.map((column) => (
            <nav key={column.title} aria-label={column.title}>
              {/*
                h2, not h3. These are top-level sections of the footer landmark, so
                they sit at the same depth as a page's own sections. As h3 they read
                as children of whatever heading happened to come last — and on a page
                whose main content has no h2 at all (/track), they followed the h1
                directly and produced a 1 -> 3 skip on every load.
              */}
              <h2 className="stamp mb-5">{column.title}</h2>
              {/*
                `space-y-0.5` plus vertical padding on the anchor rather than margin
                on the list item.

                A 13.5px link is a ~20px box, which is under the 24px WCAG 2.2 asks
                of a pointer target and nowhere near comfortable on a phone. Padding
                the anchor grows the target to 36px while the negative margin keeps
                the visual rhythm of the column unchanged — the links look the same
                distance apart, they are simply much easier to hit. Giving each one a
                full 44px instead would make the footer twice as tall for no gain,
                since these are secondary navigation and not primary actions.
              */}
              <ul className="space-y-0.5">
                {column.links.map((link) => (
                  <li key={link.to}>
                    <Link
                      to={link.to}
                      className="group -my-1 inline-flex items-center gap-2 py-2 text-[13.5px] link-quiet"
                    >
                      {/* A tick that appears on hover instead of a colour change alone.
                          Movement toward the pointer reads as a response; a shade of
                          grey shifting by 15% mostly does not. */}
                      <span
                        aria-hidden="true"
                        className="h-px w-0 bg-brand-500 transition-all duration-300 ease-out-expo
                                   group-hover:w-3"
                      />
                      {link.label}
                    </Link>
                  </li>
                ))}
              </ul>
            </nav>
          ))}
        </div>

        {/*
          The disclaimer is not boilerplate. What is sold here is a service performed
          on the customer's own account — the in-game assets are and remain EA's
          property. That distinction is what the Terms rest on and what a payment
          processor's risk team reads, so it says the same thing here as it does
          there.
        */}
        <div className="mt-16 border-t border-ink-400 pt-8">
          <p className="max-w-3xl text-[12px] leading-relaxed text-chalk-faint">
            Global FUT Services provides trading, consultation and account services. All in-game
            currency, items and player cards are the exclusive property of Electronic Arts Inc.
            We are an independent provider and are not affiliated with, endorsed by, or sponsored
            by EA Sports or Electronic Arts Inc.
            {policy && (
              <>
                {' '}Orders are covered by our {policy.guaranteeDays}-day guarantee; our published
                delivery window is {policy.deliverySlaHours} hours, and we normally beat it by a
                wide margin.
              </>
            )}
          </p>
          {/*
            The interface is translated; the contracts are not. Saying which version
            governs is not a courtesy — without it, a customer can reasonably argue
            that a translated summary of a refund window is a term of the agreement,
            and the whole point of publishing Terms is that there is one text to point
            at. Rendered in the reader's language, because a notice about language is
            useless in a language they may not read.
          */}
          <p className="mt-5 max-w-3xl text-[12px] leading-relaxed text-chalk-faint">
            {t.footer.legalLanguageNotice}
          </p>
          <p className="mt-5 text-[12px] text-chalk-faint">
            © {year} Global FUT Services. All rights reserved.
          </p>
        </div>

        {/*
          The wordmark, set enormous and clipped by the bottom of the page.

          It is the last thing on the site and it is doing one job: leaving the name
          in the reader's eye at a scale nothing else on the page uses. Outlined
          rather than filled so it stays background — a solid slab of white type this
          size would out-shout the entire footer above it. `select-none` and
          `aria-hidden` because it is a graphic, and a screen reader announcing the
          company name a fourth time is noise.
        */}
        <div
          aria-hidden="true"
          className="pointer-events-none mt-12 select-none overflow-hidden"
        >
          <p
            className="display text-hollow translate-y-[0.16em] whitespace-nowrap text-center
                       text-[clamp(2.6rem,12.4vw,11rem)] leading-none"
          >
            GLOBAL FUT
          </p>
        </div>
      </div>
    </footer>
  )
}

function SocialLink({
  href, label, children,
}: {
  href: string
  label: string
  children: ReactNode
}) {
  return (
    <a
      href={href}
      aria-label={label}
      target="_blank"
      // noopener is a security control, not a formality: without it the opened page
      // gets a handle on this one through window.opener and can navigate it away to
      // a look-alike sign-in.
      rel="noopener noreferrer"
      className="grid h-10 w-10 place-items-center rounded-edge hairline bg-ink-700 text-chalk-muted
                 transition-[color,border-color,transform] duration-300 ease-out-expo
                 hover:-translate-y-0.5 hover:border-brand-500/40 hover:text-brand-400"
    >
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
           strokeWidth="1.7" strokeLinejoin="round" aria-hidden="true">
        {children}
      </svg>
    </a>
  )
}
