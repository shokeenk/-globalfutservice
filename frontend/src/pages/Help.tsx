import { useEffect } from 'react'
import { useLocation } from 'react-router-dom'
import { Bubble } from '../components/AskWidget'
import { PageHeader } from '../components/PageHeader'
import { Reveal } from '../motion/Reveal'
import { ButtonLink, Section } from '../components/ui'
import { useSeo } from '../lib/seo'
import { useT } from '../i18n'
import { useFaq } from '../content/faq'

export default function Help() {
  const t = useT()
  const groups = useFaq()
  const { hash } = useLocation()

  /*
   * Deep links from the site search land on a single answer.
   *
   * The router's ScrollToTop already fired for this navigation, so this runs after
   * it and wins. The frame of delay is not superstition: the answers render inside
   * reveal wrappers, and measuring before they have laid out scrolls to where the
   * target used to be.
   */
  useEffect(() => {
    if (!hash) return
    const id = decodeURIComponent(hash.slice(1))
    // A timeout rather than requestAnimationFrame: rAF does not fire while the
    // document is hidden, so a link opened into a background tab would never scroll
    // and would still not scroll when the tab was finally brought forward. The delay
    // is for the reveal wrappers to settle, not for the frame.
    const timer = window.setTimeout(() => {
      document.getElementById(id)?.scrollIntoView({
        block: 'center',
        // Explicit, because the page sets `scroll-behavior: smooth` globally and this
        // would otherwise inherit it. Someone who picked an answer out of the search
        // asked to be at that answer; animating seventeen hundred pixels to get there
        // is a journey they did not ask to watch.
        behavior: 'instant' as ScrollBehavior,
      })
    }, 60)
    return () => window.clearTimeout(timer)
  }, [hash])

  useSeo({
    title: t.help.seoTitle,
    description: t.help.seoDescription,
  })


  return (
    <>
      <PageHeader
        eyebrow={t.help.eyebrow}
        title={t.help.title}
        lead={t.help.lead}
      />
      <Section className="rhythm-section">
      {/*
        Rendered as a transcript rather than an accordion.

        Every question and answer is in the DOM as plain text, which is what a search
        engine indexes and what a screen reader reads straight through — an accordion
        hides eight of nine answers behind a click and this does not. The chat framing
        is presentation; the content is the same content the widget serves.
      */}
      <div className="mx-auto max-w-2xl space-y-12">
        {groups.map((group) => (
          <section key={group.title} aria-label={group.title}>
            {/*
              A centred rule with the group name set into it.

              A bare centred eyebrow floats — there is nothing holding it to the
              column. Two hairlines running out to the measure on either side give
              the label a line to sit on and separate one group of questions from
              the next without a heavy divider.
            */}
            <div className="mb-7 flex items-center gap-4">
              <span aria-hidden="true" className="h-px flex-1 bg-gradient-to-r from-transparent to-ink-300" />
              <h2 className="eyebrow whitespace-nowrap">{group.title}</h2>
              <span aria-hidden="true" className="h-px flex-1 bg-gradient-to-l from-transparent to-ink-300" />
            </div>

            <div className="space-y-4">
              {group.items.map((item, index) => (
                <Reveal
                  key={item.key}
                  id={`faq-${item.key}`}
                  delay={Math.min(index, 4) * 60}
                  distance={12}
                  className="scroll-mt-2 space-y-2"
                >
                  <Bubble role="user" text={item.question} />
                  <Bubble role="assistant" text={item.answer} />
                </Reveal>
              ))}
            </div>
          </section>
        ))}
      </div>

      {/*
        The escape hatch, given the same treatment as the closing CTA on the
        homepage rather than a plain card. Anyone who has read this far and not
        found their answer is the reader most at risk of leaving.
      */}
      <Reveal className="mx-auto mt-16 max-w-2xl">
        <div className="relative overflow-hidden rounded-panel border border-brand-500/25 bg-paper p-7">
          <div
            aria-hidden="true"
            className="pointer-events-none absolute -right-20 -top-20 h-56 w-56 rounded-full
                       bg-brand-500/[0.12] blur-[80px]"
          />
          <div className="relative flex flex-col items-start justify-between gap-5 md:flex-row md:items-center">
            <div>
              <h2 className="display text-display-sm text-chalk">{t.help.stillStuck}</h2>
              <p className="mt-2 text-body-sm text-chalk-muted">{t.help.stillStuckBody}</p>
            </div>
            <ButtonLink to="/support" size="md">{t.help.contactSupport}</ButtonLink>
          </div>
        </div>
      </Reveal>
      </Section>
    </>
  )
}
