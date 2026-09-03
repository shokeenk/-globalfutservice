import { Link } from 'react-router-dom'
import { PageHeader } from '../components/PageHeader'
import { Section } from '../components/ui'
import { BUSINESS, EMAIL_HREF, PHONE_HREF } from '../content/business'
import { useSeo } from '../lib/seo'
import { useCatalog } from '../state/CatalogContext'

/**
 * About us.
 *
 * <p>Written to be read by two audiences at once: a customer deciding whether to hand
 * over money, and a payment gateway's reviewer deciding whether this is a real business
 * doing what it claims. Those want the same thing — a plain account of what is sold, to
 * whom, and what happens when it goes wrong — which is why there is no separate
 * "corporate" register of language here.
 *
 * <p><b>The numbers are read from live configuration, not typed.</b> The guarantee
 * window and delivery commitment shown here are the same values the pricing engine and
 * the terms use. An about page that advertises a stronger promise than the software
 * enforces is worse than one that says nothing.
 */
export default function About() {
  const { policy } = useCatalog()
  const guaranteeDays = policy?.guaranteeDays ?? 7
  const sla = policy?.deliverySlaHours ?? 48

  useSeo({
    title: 'About us',
    description:
      'Global FUT Services sells FC coins, coaching and Champs & Rivals boosting for EA '
      + 'Sports FC. Who we are, what we sell, and what we guarantee.',
  })

  return (
    <>
      <PageHeader eyebrow="About" title="Who we are" />

      <Section className="rhythm-section">
        <div className="mx-auto max-w-[46rem] space-y-8 text-[15px] leading-[1.75] text-chalk-muted">

          {/*
            The line-of-business statement, first and unmissable.

            It is the sentence a reviewer is looking for, and burying it under three
            paragraphs of story is how a page that contains the right answer still fails
            the check. Set at reading size rather than as a pull quote so it reads as a
            statement of fact rather than marketing.
          */}
          <p className="hairline rounded-panel bg-paper p-6 text-[16px] leading-relaxed text-chalk shadow-e1">
            {BUSINESS.lineOfBusiness}
          </p>

          <section className="space-y-4">
            <h2 className="display text-display-sm text-chalk">What we do</h2>
            <p>
              Global FUT Services sells three things to players of EA Sports FC Ultimate
              Team, and nothing else.
            </p>
            <p>
              <strong className="text-chalk">Coins.</strong> We work the in-game transfer
              market and move coins into your club. The usual route needs no password at
              all — you list a card we name at a price we name, and we buy it. Where a
              customer prefers us to do it directly, we sign in, move the coins, and destroy
              the sign-in when the order is finished.
            </p>
            <p>
              <strong className="text-chalk">Coaching.</strong> One hour, one to one, live
              with a coach who plays at the level you are chasing. They watch how you
              actually play rather than how you describe it, and leave you with one habit to
              fix before the next session.
            </p>
            <p>
              <strong className="text-chalk">Boosting.</strong> Champions and Rivals runs
              played on your account by a trader, sold by the number of wins or the rank you
              want to reach.
            </p>
          </section>

          <section className="space-y-4">
            <h2 className="display text-display-sm text-chalk">Who it is for</h2>
            <p>
              Competitive Ultimate Team players who have run out of one of two things:
              coins, or time. Someone who knows exactly the squad they want and does not
              want to grind the market for a fortnight to afford it. Someone stuck at the
              same rank every weekend who would rather be shown what they are doing wrong
              than watch another highlight reel. Someone who cannot commit twenty games to a
              Champs qualifier this week.
            </p>
            <p>
              We are not the cheapest, and we are open about that. What we sell is a
              transfer that is done properly and a guarantee that means something if it is
              not.
            </p>
          </section>

          <section className="space-y-4">
            <h2 className="display text-display-sm text-chalk">Why you can trust us with an order</h2>
            <p>
              Trust in this market is mostly earned by being specific, so here is what we
              actually commit to:
            </p>
            <ul className="ml-5 list-disc space-y-2">
              <li>
                <strong className="text-chalk">Most orders delivered within 60 minutes</strong>,
                with a committed outside limit of {sla} hours. Miss it and you are entitled
                to a refund without having to argue for one.
              </li>
              <li>
                <strong className="text-chalk">The desk is staffed 24 hours a day.</strong> An
                order placed at four in the morning is worked the same as one placed at noon.
              </li>
              <li>
                <strong className="text-chalk">A {guaranteeDays}-day guarantee</strong> after
                delivery, covering EA sanctioning the account or removing the coins. You
                choose cash back or a larger amount as store credit.
              </li>
              <li>
                <strong className="text-chalk">Sign-in details, where a service needs them,
                are asked for only after payment</strong>, encrypted before they are stored,
                opened only by the trader working your order, and destroyed when it
                completes. That is enforced by a scheduled job, not by a promise.
              </li>
            </ul>
            <p>
              The full terms are in the{' '}
              <Link className="underline" to="/refund-policy">refund policy</Link> and{' '}
              <Link className="underline" to="/terms">terms of service</Link>, and both say
              the same thing this page does, because the figures come from the same place.
            </p>
          </section>

          <section className="space-y-4">
            <h2 className="display text-display-sm text-chalk">The business</h2>
            <p>
              This website is operated by <strong className="text-chalk">{BUSINESS.legalName}</strong>,
              trading as {BUSINESS.tradingName}, from Jodhpur, Rajasthan. It is a small
              operation rather than a marketplace: the people answering support are the
              people working the orders.
            </p>

            <div className="hairline rounded-panel bg-paper p-6 shadow-e1">
              <p className="stamp mb-4">Business details</p>
              <dl className="space-y-1.5 text-[14px]">
                <div className="flex gap-2">
                  <dt className="w-[132px] shrink-0 text-chalk-faint">Legal name</dt>
                  <dd className="text-chalk-muted">{BUSINESS.legalName}</dd>
                </div>
                <div className="flex gap-2">
                  <dt className="w-[132px] shrink-0 text-chalk-faint">Registered address</dt>
                  <dd className="text-chalk-muted">{BUSINESS.registeredAddress}</dd>
                </div>
                <div className="flex gap-2">
                  <dt className="w-[132px] shrink-0 text-chalk-faint">Mobile</dt>
                  <dd>
                    <a className="text-chalk-muted underline" href={PHONE_HREF}>{BUSINESS.phone}</a>
                  </dd>
                </div>
                <div className="flex gap-2">
                  <dt className="w-[132px] shrink-0 text-chalk-faint">Email</dt>
                  <dd>
                    <a className="text-chalk-muted underline" href={EMAIL_HREF}>{BUSINESS.email}</a>
                  </dd>
                </div>
              </dl>
              <p className="mt-4 text-[14px]">
                For anything order-related, the{' '}
                <Link className="underline" to="/contact">contact page</Link> is faster than
                email — it attaches your order reference automatically.
              </p>
            </div>
          </section>

        </div>
      </Section>
    </>
  )
}
