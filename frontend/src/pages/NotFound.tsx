import { Atmosphere } from '../components/Atmosphere'
import { ButtonLink, Section } from '../components/ui'
import { useT } from '../i18n'
import { useSeo } from '../lib/seo'

export default function NotFound() {
  const t = useT()
  useSeo({ title: t.notFound.title, noindex: true })

  return (
    <section className="relative isolate flex min-h-[calc(100dvh-72px)] items-center overflow-hidden">
      <Atmosphere intensity={0.5} />
      <Section className="relative">
        <div className="mx-auto max-w-md text-center">
          {/*
            The code is set as a graphic and outlined rather than filled. A solid
            72px "404" is the loudest thing on a page whose entire purpose is to get
            the reader somewhere else — the heading and the two links are what
            matter, so the number is treated as background.
          */}
          <p aria-hidden="true" className="display text-hollow text-[clamp(5rem,18vw,9rem)] leading-none">
            404
          </p>
          <h1 className="display mt-2 text-display-lg text-sheen">{t.notFound.heading}</h1>
          <p className="mt-4 text-body-sm leading-relaxed text-chalk-muted">{t.notFound.body}</p>
          <div className="mt-9 flex flex-wrap justify-center gap-3">
            <ButtonLink to="/" size="md">{t.notFound.home}</ButtonLink>
            <ButtonLink to="/track" variant="secondary" size="md">{t.notFound.track}</ButtonLink>
          </div>
        </div>
      </Section>
    </section>
  )
}
