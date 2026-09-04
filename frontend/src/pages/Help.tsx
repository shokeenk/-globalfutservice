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

    /*
     * Scroll to the answer, and keep re-scrolling for as long as the page is still
     * changing height underneath it.
     *
     * A single delayed scroll cannot work here, and the reason is circular: the
     * answers sit inside reveal wrappers that lay out when they enter the viewport,
     * so scrolling towards the target is itself what changes the height of everything
     * above it. Measured in a real 1280x900 viewport, one shot landed 1184px past the
     * anchor -- scroll clamped to the bottom of a document that then shrank.
     *
     * Retrying on a timer does not fix it either, and that was the first attempt at
     * this: while the scroll sits clamped at the bottom the position reads identical
     * on consecutive ticks, so any "has it stopped moving" test declares victory
     * early. What is actually being waited for is not time and not stillness, it is
     * the layout, so a ResizeObserver is the thing that knows.
     *
     * The window is bounded. An observer left running for the life of the route would
     * yank a reader back to the anchor every time something below them resized, which
     * is a page that fights anyone who tries to scroll away from where they landed.
     */
    let stop = false

    const scrollToTarget = () => {
      if (stop) return
      document.getElementById(id)?.scrollIntoView({
        block: 'center',
        // Explicit, because the page sets `scroll-behavior: smooth` globally and this
        // would otherwise inherit it. Someone who picked an answer out of the search
        // asked to be at that answer; animating seventeen hundred pixels to get there
        // is a journey they did not ask to watch.
        behavior: 'instant' as ScrollBehavior,
      })
    }

    // A timeout rather than requestAnimationFrame: rAF does not fire while the
    // document is hidden, so a link opened into a background tab would never scroll
    // and would still not scroll when the tab was finally brought forward.
    const first = window.setTimeout(scrollToTarget, 60)

    const observer = new ResizeObserver(scrollToTarget)
    observer.observe(document.body)

    // Long enough to cover the reveal cascade, short enough that a reader who has
    // started scrolling away is not pulled back.
    const release = window.setTimeout(() => {
      stop = true
      observer.disconnect()
    }, 1500)

    return () => {
      stop = true
      observer.disconnect()
      window.clearTimeout(first)
      window.clearTimeout(release)
    }
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
