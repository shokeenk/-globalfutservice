import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { BrandBadge, Logo } from '../brand/Logo'
import { useT } from '../i18n'
import { BUSINESS, EMAIL_HREF, PHONE_HREF } from '../content/business'
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
        { to: '/about', label: t.footer.about },
        { to: '/contact', label: t.footer.contact },
        { to: '/rewards', label: t.footer.rewards },
        { to: '/help', label: t.footer.help },
        { to: '/track', label: t.nav.track },
      ],
    },
    {
      /*
       * Every policy document, on every page, without hunting.
       *
       * The four a payment gateway checks for by name — refund, cancellation, shipping
       * and privacy — now sit together rather than being reachable only from inside
       * another document. `/track` moved up to the menu column, which is where someone
       * would look for it anyway: it was never a legal document, it was filling space.
       */
      title: t.footer.legal,
      links: [
        { to: '/terms', label: t.footer.terms },
        { to: '/privacy', label: t.footer.privacy },
        { to: '/refund-policy', label: t.footer.refund },
        { to: '/cancellation-policy', label: t.footer.cancellation },
        { to: '/shipping-policy', label: t.footer.shipping },
        { to: '/aml-kyc', label: t.footer.aml },
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
              {/*
                Discord sits first, and it is not really a social link.

                The terms of service name it as the route for coaching scheduling, support,
                safety-policy claims and disputes — clauses 6, 9, 16, 19 and 20 all point at
                it. That makes it a contractual contact channel that happens to share a row
                with the marketing accounts, so it leads them.
              */}
              <SocialLink
                href={BUSINESS.discordInvite}
                label={`Global FUT Services on Discord (${BUSINESS.discordName})`}
              >
                <path d="M20.3 4.4A19.8 19.8 0 0 0 15.4 3l-.24.5a18.3 18.3 0 0 1 4.3 1.4c-2-1.1-4.1-1.6-6.4-1.6-2.3 0-4.4.5-6.4 1.6A18.3 18.3 0 0 1 11 3.5L10.7 3a19.8 19.8 0 0 0-4.9 1.4C2.6 9.1 1.7 13.7 2.1 18.2a19.9 19.9 0 0 0 6 3c.5-.65.9-1.35 1.25-2.1-.7-.25-1.35-.55-1.95-.9.16-.12.32-.25.47-.38a14.2 14.2 0 0 0 12.2 0c.16.14.31.26.47.38-.62.36-1.27.66-1.96.9.36.75.78 1.45 1.25 2.1a19.8 19.8 0 0 0 6-3c.5-5.2-.85-9.75-3.5-13.8ZM8.7 15.4c-1.18 0-2.15-1.07-2.15-2.4S7.5 10.6 8.7 10.6s2.17 1.08 2.15 2.4c0 1.33-.96 2.4-2.15 2.4Zm6.6 0c-1.18 0-2.15-1.07-2.15-2.4s.95-2.4 2.15-2.4 2.17 1.08 2.15 2.4c0 1.33-.95 2.4-2.15 2.4Z" />
              </SocialLink>
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
                {' '}Orders are covered by our 100% Safety Policy; our published
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
          {/*
            The line of business, in plain words, on every page.

            It is the sentence that answers "what does this company actually sell" for
            anyone who arrives without context — a payment gateway reviewing the site, or
            a customer who followed a link straight to a policy page. Cheap to state
            once here; expensive to be vague about.
          */}
          <p className="mt-5 max-w-3xl text-[12px] leading-relaxed text-chalk-faint">
            {BUSINESS.lineOfBusiness}
          </p>
          <p className="mt-3 text-[12px] leading-relaxed text-chalk-faint">
            Operated by {BUSINESS.legalName} · {BUSINESS.registeredAddress} ·{' '}
            <a className="underline" href={PHONE_HREF}>{BUSINESS.phone}</a> ·{' '}
            <a className="underline" href={EMAIL_HREF}>{BUSINESS.email}</a>
          </p>
          <p className="mt-3 text-[12px] text-chalk-faint">
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
