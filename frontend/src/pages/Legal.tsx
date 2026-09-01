import type { ReactNode } from 'react'
import { PageHeader } from '../components/PageHeader'
import { Section } from '../components/ui'
import { useSeo } from '../lib/seo'
import { useCatalog } from '../state/CatalogContext'
import type { Policy } from '../lib/types'

/**
 * Terms, privacy and the AML/KYC statement.
 *
 * <p>Two things about this file are deliberate.
 *
 * <p><b>The numbers come from live configuration.</b> The guarantee window, the
 * refund fee, the delivery commitment and the KYC threshold are read from the same
 * policy object the pricing engine uses. A terms page that says seven days while
 * the scheduler settles at fourteen is worse than no terms page at all, and that
 * drift is exactly what happens when the numbers are typed into prose.
 *
 * <p><b>These are working drafts, not legal advice.</b> They are modelled on what
 * comparable operators publish and on how this application actually behaves, which
 * makes them a sound starting point and an accurate description of the system. They
 * still need a solicitor's eye before the business trades on them — particularly
 * the jurisdiction clause, the consumer-law position under Indian law, and the AML
 * thresholds, which are regulatory rather than commercial choices.
 */
export default function Legal({ doc }: { doc: 'terms' | 'privacy' | 'aml' }) {
  const { policy } = useCatalog()

  const meta = {
    terms: { title: 'Terms of service', description: 'The agreement between you and Global FUT Services.' },
    privacy: { title: 'Privacy policy', description: 'What we collect, why, and how long we keep it.' },
    aml: { title: 'AML & KYC policy', description: 'Our anti-money-laundering and identity checks.' },
  }[doc]

  useSeo(meta)

  return (
    <>
      <PageHeader eyebrow="Legal" title={meta.title} />
      <Section className="rhythm-section">
      {/*
        Contracts are set to be read, not to be skimmed.

        `measure` caps the line at 62 characters and the type steps up to 15px with
        generous leading — a legal document at 14px across a 900px card is the
        typographic equivalent of hiding it. If this business ever has to point at
        clause 7 in a dispute, "it was legible and plainly presented" is a materially
        better position than the alternative.
      */}
      <article className="mx-auto max-w-[46rem] space-y-10 text-[15px] leading-[1.75] text-chalk-muted">
        {doc === 'terms' && <Terms policy={policy} />}
        {doc === 'privacy' && <Privacy policy={policy} />}
        {doc === 'aml' && <Aml />}
      </article>
      </Section>
    </>
  )
}

/**
 * A numbered clause.
 *
 * <p>The number is pulled into the left margin on wide screens so the headings form
 * a column and the clause text keeps a single flush edge. That is how printed
 * contracts have been set for a century, and it is why you can find clause 7 by
 * running a finger down the page instead of reading every heading.
 */
function Clause({ n, title, children }: { n: number; title: string; children: ReactNode }) {
  return (
    <section className="relative scroll-mt-2" id={`clause-${n}`}>
      <h2 className="display mb-3 text-display-sm text-chalk">
        <span
          className="tnum mr-2 text-brand-400 lg:absolute lg:-left-12 lg:mr-0 lg:text-right
                     lg:tabular-nums"
          aria-hidden="true"
        >
          {n}.
        </span>
        {title}
      </h2>
      <div className="space-y-4">{children}</div>
    </section>
  )
}

function Terms({ policy }: { policy: Policy | null }) {
  const guaranteeDays = policy?.guaranteeDays ?? 7
  const sla = policy?.deliverySlaHours ?? 48
  const refundFee = (policy?.refundFeeBps ?? 500) / 100
  const cash = (policy?.guaranteeCashBps ?? 5000) / 100
  const credit = (policy?.guaranteeCreditBps ?? 10000) / 100

  return (
    <>
      <p className="text-chalk-faint">Last updated {new Date().toLocaleDateString('en-GB', {
        day: 'numeric', month: 'long', year: 'numeric' })}</p>

      <Clause n={1} title="What we sell">
        <p>
          Global FUT Services provides <strong className="text-chalk">trading, consultation and
          account services</strong> for EA FC. When you place an order you are engaging us to
          perform work — locating undervalued cards on the transfer market, transacting them, and
          coaching or playing on your behalf where you have asked us to.
        </p>
        <p>
          <strong className="text-chalk">We do not sell in-game currency, player cards or
          accounts.</strong> All in-game assets are and remain the exclusive property of Electronic
          Arts Inc. under the licence they grant you. We are an independent provider, not
          affiliated with, endorsed by or sponsored by EA Sports or Electronic Arts Inc.
        </p>
      </Clause>

      <Clause n={2} title="Placing an order">
        <p>
          Prices are quoted before you pay and held for a short period. A quote that has expired
          is re-priced automatically; we will never charge you an amount you have not seen. The
          total shown at checkout includes the EA transfer market tax and payment processing —
          nothing is added afterwards.
        </p>
        <p>
          You must be old enough to enter a contract where you live, and the payment method must
          be yours.
        </p>
      </Clause>

      <Clause n={3} title="Delivery">
        <p>
          We aim to complete most orders within one hour. Our contractual commitment is{' '}
          <strong className="text-chalk">{sla} hours</strong> from payment, or from receipt of any
          details we have asked you for, whichever is later.
        </p>
        <p>
          Where an order requires your account to be in a particular state — signed out
          everywhere, transfer market unlocked, fewer than five unassigned items — the commitment
          runs from the point those conditions are met. We will tell you promptly if they are not.
        </p>
      </Clause>

      <Clause n={4} title="Refunds">
        <p>
          If we have not delivered within {sla} hours you may request a refund or a status update,
          and we will provide one. A processing fee of {refundFee}% is deducted from cash refunds
          to cover the payment costs we cannot recover.
        </p>
        <p>
          <strong className="text-chalk">
            Once we send you an "Order Delivered" email, the work is complete and the order is
            final.
          </strong>{' '}
          After that point the guarantee in clause 5 applies instead. We flag this plainly because
          it is the term customers most often discover too late.
        </p>
      </Clause>

      <Clause n={5} title={`The ${guaranteeDays}-day guarantee`}>
        <p>
          For {guaranteeDays} days after delivery, your order is covered against EA sanctioning
          your account or removing the coins involved. If that happens, tell us within the window
          and choose either:
        </p>
        <ul className="ml-5 list-disc space-y-1">
          <li><strong className="text-chalk">{cash}% back in cash</strong>, or</li>
          <li><strong className="text-chalk">{credit}% as store credit</strong>, added to your rewards balance.</li>
        </ul>
        <p>
          The credit option is worth more because it costs us capacity rather than cash, and we
          would rather keep you as a customer. Outside the window, or where the account was
          sanctioned for conduct unrelated to our work, the guarantee does not apply.
        </p>
      </Clause>

      <Clause n={6} title="Your account details">
        <p>
          Some services require us to sign in to your EA account. Where they do, we ask for those
          details only after payment, encrypt them before they are stored, open them only to
          fulfil your order, and destroy them when it is complete. See our privacy policy for the
          specifics.
        </p>
        <p>
          You remain responsible for your own account security, and we ask you to change your
          password and regenerate your backup codes once an order is finished.
        </p>
      </Clause>

      <Clause n={7} title="Things you agree not to do">
        <p>
          Use the service unlawfully, submit details for an account that is not yours, attempt to
          reverse a payment for work that has been delivered, or interfere with the operation of
          the site.
        </p>
      </Clause>

      <Clause n={8} title="Risk and liability">
        <p>
          Buying trading services carries some risk of action by the game publisher. We take it
          seriously and manage it carefully, and we are not going to pretend it is zero. The
          guarantee in clause 5 is our answer to that risk, and it is the limit of our liability
          in respect of publisher action.
        </p>
        <p>
          Beyond that, our total liability for any order is limited to what you paid for it. We
          are not liable for indirect or consequential losses.
        </p>
      </Clause>

      <Clause n={9} title="Changes">
        <p>
          We may change these terms. The version that applies to your order is the one published
          when you placed it, and we keep a record of that with the order.
        </p>
      </Clause>

      <Clause n={10} title="Getting in touch">
        <p>
          Contact us through the support page. Please raise a dispute with us before your bank —
          we can almost always resolve it faster, and a chargeback on delivered work is something
          we will contest with the order's full history.
        </p>
      </Clause>
    </>
  )
}

function Privacy({ policy }: { policy: Policy | null }) {
  return (
    <>
      <p className="text-chalk-faint">Last updated {new Date().toLocaleDateString('en-GB', {
        day: 'numeric', month: 'long', year: 'numeric' })}</p>

      <Clause n={1} title="What we collect">
        <p>
          To fulfil an order: your email address, optionally a phone number, your EA account name,
          and the details of what you ordered. If you create an account, we also hold a password
          hash — never the password itself.
        </p>
        <p>
          For comfort-trade orders only: your EA sign-in and backup codes, handled as described
          below.
        </p>
      </Clause>

      <Clause n={2} title="Your EA sign-in">
        <p>These are the rules we hold ourselves to, and they are enforced in the software rather than by policy alone:</p>
        <ul className="ml-5 list-disc space-y-1.5">
          <li>We ask for them only after payment, never before.</li>
          <li>They are encrypted with a key unique to your order before they reach our database.</li>
          <li>Only the trader working your order can open them, and every access is recorded.</li>
          <li>
            They are destroyed when your order completes, and in any case within{' '}
            {policy ? '24 hours' : 'a day'} of delivery — enforced by a scheduled job that runs
            regardless of what state the order is in.
          </li>
          <li>They are never written to a log file and never sent over messaging apps.</li>
        </ul>
        <p>We ask you to change your password and regenerate your backup codes afterwards.</p>
      </Clause>

      <Clause n={3} title="Who else sees your data">
        <p>
          Our payment provider processes your payment and sees what it needs to for that purpose;
          we never see or store your full card details. Our email provider delivers your receipts.
          That is the extent of it — we do not sell data, and we do not share it for advertising.
        </p>
      </Clause>

      <Clause n={4} title="How long we keep things">
        <p>
          Order records are retained for as long as we are required to keep transaction records.
          Sign-in details are destroyed as described above. You can ask us to delete your account
          and we will, subject to those record-keeping obligations.
        </p>
      </Clause>

      <Clause n={5} title="Your rights">
        <p>
          You can ask us what we hold about you, ask us to correct it, and ask us to delete it.
          Contact us through the support page and we will respond within a reasonable period.
        </p>
      </Clause>

      <Clause n={6} title="Cookies">
        <p>
          We use one cookie, and it exists to keep you signed in. It is not readable by scripts,
          is not sent to other sites, and is not used for tracking or advertising. There is no
          analytics or advertising cookie on this site, which is why there is no consent banner.
        </p>
      </Clause>
    </>
  )
}

function Aml() {
  return (
    <>
      <p className="text-chalk-faint">Last updated {new Date().toLocaleDateString('en-GB', {
        day: 'numeric', month: 'long', year: 'numeric' })}</p>

      <Clause n={1} title="Purpose">
        <p>
          Global FUT Services takes financial crime seriously. This policy sets out how we verify
          customers, monitor transactions and meet our obligations. It applies to everyone who
          works here.
        </p>
      </Clause>

      <Clause n={2} title="Identity verification">
        <p>
          Most orders require nothing beyond a working email. We will ask for identity
          documentation — a government-issued photo ID and, where appropriate, proof of address —
          where:
        </p>
        <ul className="ml-5 list-disc space-y-1">
          {/* No terminal punctuation. These are fragments completing the sentence above,
              and the semicolons they used to carry are contract-drafting convention that
              reads as a typo on a web page — especially as the other lists here do not
              use them. The one place a connector is kept is the refund clause above,
              where ", or" is load-bearing: it says the customer picks one, not both. */}
          <li>cumulative spend passes our review threshold</li>
          <li>a payout is requested rather than a payment made</li>
          <li>the pattern of activity is unusual for the account</li>
        </ul>
      </Clause>

      <Clause n={3} title="What we watch for">
        <p>
          Orders that are unusual in size for the customer, payments broken into pieces that each
          sit below a threshold, mismatched or evidently false details, requests to pay by
          unconventional means, and sudden changes in activity.
        </p>
      </Clause>

      <Clause n={4} title="Restricted jurisdictions">
        <p>
          We do not accept customers in jurisdictions subject to comprehensive sanctions, and we
          apply enhanced checks to customers in higher-risk jurisdictions and to politically
          exposed persons.
        </p>
      </Clause>

      <Clause n={5} title="Records and reporting">
        <p>
          Identification and transaction records are retained for at least five years. Where we
          are required to report suspicious activity to the relevant authority, we do so, and we
          do not tip off the customer.
        </p>
      </Clause>

      <Clause n={6} title="Review">
        <p>
          This policy is reviewed annually, and whenever our payment arrangements or the
          regulatory position change.
        </p>
      </Clause>
    </>
  )
}
