import { Atmosphere } from '../components/Atmosphere'
import { ButtonLink, Badge, Card, Section } from '../components/ui'
import { useT } from '../i18n'
import { SEASON, useSeo } from '../lib/seo'

/**
 * A placeholder for services that are priced but not yet sellable.
 *
 * Deliberately a real page rather than a hidden route: the catalogue already
 * carries the price, the pricing engine already refuses to quote it, and the only
 * thing standing between here and launch is one flag. Saying "coming soon" plainly
 * is more honest than a nav link that 404s, and it captures the interest.
 */
export default function ComingSoon({ service }: { service: string }) {
  const t = useT()
  useSeo({
    title: service,
    description: t.comingSoon.seoDescription(service, SEASON),
  })

  return (
    <section className="relative isolate overflow-hidden">
      <Atmosphere intensity={0.5} />
      <Section className="relative rhythm-page">
        <Card className="mx-auto max-w-2xl p-10 text-center shadow-e3 sm:p-12">
          <Badge tone="gold">{t.comingSoon.badge}</Badge>
          <h1 className="display mt-6 text-display-lg text-sheen">{service}</h1>
          <p className="measure mx-auto mt-5 text-body-sm leading-relaxed text-chalk-muted">
            {t.comingSoon.body}
          </p>
          <div className="mt-9 flex flex-wrap justify-center gap-3">
            <ButtonLink to="/order" size="md">{t.comingSoon.buyCoins}</ButtonLink>
            <ButtonLink to="/boosting" variant="secondary" size="md">
              {t.comingSoon.seeBoosting}
            </ButtonLink>
          </div>
        </Card>
      </Section>
    </section>
  )
}
