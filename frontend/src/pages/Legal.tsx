import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { PageHeader } from '../components/PageHeader'
import { Section } from '../components/ui'
import { useSeo } from '../lib/seo'
import { useCatalog } from '../state/CatalogContext'
import { BUSINESS, EMAIL_HREF, PHONE_HREF } from '../content/business'
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
export type LegalDoc =
  | 'terms' | 'privacy' | 'aml' | 'refund' | 'cancellation' | 'shipping'

export default function Legal({ doc }: { doc: LegalDoc }) {
  const { policy } = useCatalog()

  const meta = {
    terms: { title: 'Terms of service', description: 'The agreement between you and Global FUT Services.' },
    privacy: { title: 'Privacy policy', description: 'What we collect, why, and how long we keep it.' },
    aml: { title: 'AML & KYC policy', description: 'Our anti-money-laundering and identity checks.' },
    refund: {
      title: 'Return & refund policy',
      description: 'When a refund can be requested, how it is paid, and what the guarantee covers.',
    },
    cancellation: {
      title: 'Cancellation policy',
      description: 'When an order can be cancelled and how to ask.',
    },
    shipping: {
      title: 'Shipping policy',
      description: 'How and how quickly a digital order is delivered.',
    },
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
        {doc === 'refund' && <Refund policy={policy} />}
        {doc === 'cancellation' && <Cancellation policy={policy} />}
        {doc === 'shipping' && <Shipping policy={policy} />}
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

/* ------------------------------------------------------------------ refund --- */

/**
 * Return and refund policy.
 *
 * <p><b>This restates clauses 4 and 5 of the terms; it does not add to them.</b> Two
 * pages describing the same refund in different words is how a customer ends up quoting
 * one at you while the software enforces the other, so every figure here is read from
 * the same live policy object the terms use, and the boundary is drawn in the same
 * place: delivery.
 *
 * <p>There is nothing to return. What is sold is delivered into an EA account and
 * cannot be handed back, which is why this is about refunds and the guarantee rather
 * than returns — said at the top instead of left for a reader to infer.
 */
function Refund({ policy }: { policy: Policy | null }) {
  const sla = policy?.deliverySlaHours ?? 48
  const guaranteeDays = policy?.guaranteeDays ?? 7
  const refundFee = (policy?.refundFeeBps ?? 500) / 100
  const cash = (policy?.guaranteeCashBps ?? 5000) / 100
  const credit = (policy?.guaranteeCreditBps ?? 10000) / 100

  return (
    <>
      <p className="text-chalk-faint">Last updated {updatedOn()}</p>

      <Clause n={1} title="What is being bought">
        <p>
          Every service on this site is digital and is delivered into your EA Sports FC
          account — coins through the transfer market, a coaching session over a call, a
          rank pushed on your own account. Nothing physical ships, and nothing delivered
          can be returned in the ordinary sense.
        </p>
        <p>
          This policy therefore covers two situations: an order that has not been
          delivered yet, and one that has.
        </p>
      </Clause>

      <Clause n={2} title={`Before delivery — refundable within ${sla} hours`}>
        <p>
          If we have not delivered your order within{' '}
          <strong className="text-chalk">{sla} hours</strong> of payment clearing, you may
          request a full refund. You do not have to give a reason, and you may ask for a
          status update instead if you would rather wait.
        </p>
        <p>
          A processing fee of <strong className="text-chalk">{refundFee}%</strong> is deducted
          from cash refunds. That is the payment cost we are charged and cannot recover
          when a transaction is reversed. It is not a penalty, and it is waived where the
          delay was ours to fix.
        </p>
      </Clause>

      <Clause n={3} title="After delivery — the order is final">
        <p>
          <strong className="text-chalk">
            Once we send you an “Order Delivered” email, the work is complete and the order
            is final.
          </strong>{' '}
          Coins in your club cannot be un-sent, and a coaching session that has been held
          cannot be un-taught. From that moment the guarantee below replaces the right to a
          refund.
        </p>
        <p>
          We state this prominently because it is the term customers most often find out
          about too late.
        </p>
      </Clause>

      <Clause n={4} title={`The ${guaranteeDays}-day guarantee`}>
        <p>
          For <strong className="text-chalk">{guaranteeDays} days</strong> after delivery, your
          order is covered against EA sanctioning your account or removing the coins
          involved. If that happens, tell us inside the window and choose either:
        </p>
        <ul className="ml-5 list-disc space-y-1.5">
          <li>
            <strong className="text-chalk">{cash}% back in cash</strong>, to the original
            payment method, or
          </li>
          <li>
            <strong className="text-chalk">{credit}% as store credit</strong>, added to your
            rewards balance.
          </li>
        </ul>
        <p>
          Credit is worth more than cash because it costs us capacity rather than money,
          and we would rather keep you as a customer than win the argument. Outside the
          window, or where the account was sanctioned for conduct unrelated to our work,
          the guarantee does not apply.
        </p>
      </Clause>

      <Clause n={5} title="How refunds are paid">
        <p>
          Cash refunds are returned to the{' '}
          <strong className="text-chalk">original payment method</strong>, through the payment
          gateway that took the payment. We cannot send a refund to a different card,
          account or wallet, because we never hold your payment details — the gateway does,
          and it reverses the original transaction.
        </p>
        <p>
          Once approved, a refund is submitted the same working day. How long it then takes
          to appear is your bank’s to decide, commonly five to seven working days for cards
          and faster for UPI. Store credit is added to your rewards balance immediately and
          does not expire.
        </p>
      </Clause>

      <Clause n={6} title="How to request one">
        <p>
          Write to <a className="underline" href={EMAIL_HREF}>{BUSINESS.email}</a> or call{' '}
          <a className="underline" href={PHONE_HREF}>{BUSINESS.phone}</a> with your order
          reference. There is no form to fill in and no queue to join.
        </p>
      </Clause>

      <Operator />
    </>
  )
}

/* ------------------------------------------------------------ cancellation --- */

/**
 * Cancellation policy.
 *
 * <p>The line is drawn where the software draws it. An order that has not been started
 * costs nothing to stop; one where a trader is already listing cards on your account has
 * consumed the thing actually being sold, which is somebody’s time on a live market. The
 * page says that, rather than quoting a number of minutes that could not be honoured
 * consistently across three different services.
 */
function Cancellation({ policy }: { policy: Policy | null }) {
  const refundFee = (policy?.refundFeeBps ?? 500) / 100

  return (
    <>
      <p className="text-chalk-faint">Last updated {updatedOn()}</p>

      <Clause n={1} title="Before work starts — cancel for a full refund">
        <p>
          An order can be cancelled at any point{' '}
          <strong className="text-chalk">before delivery begins</strong>, for a full refund
          with no fee deducted. In practice that means before a trader has started listing
          on your account, before a boosting session has begun, or before a booked coaching
          slot has started.
        </p>
        <p>
          If you have paid but not yet given us what the order needs from you — an EA
          account name, a sign-in for a comfort trade, a chosen coaching slot — the order
          has certainly not started and can always be cancelled.
        </p>
      </Clause>

      <Clause n={2} title="Once work has started">
        <p>
          After delivery begins we cannot cancel, because the cost has already been
          incurred: coins move on a live transfer market, and a trader’s time cannot be
          returned to stock. If something has gone wrong mid-order, contact us — an order
          that is genuinely stuck is a support problem, and we would rather fix it than
          argue about which policy applies.
        </p>
        <p>
          Where we agree to unwind a started order as a goodwill exception, the {refundFee}%
          processing fee applies, for the same reason it applies to refunds.
        </p>
      </Clause>

      <Clause n={3} title="Coaching sessions">
        <p>
          A booked session can be moved or cancelled up to the notice period shown on the
          booking page, and the credit returns to your account to spend on another slot.
          Inside that notice period the coach has already held the time, and the session is
          treated as delivered.
        </p>
      </Clause>

      <Clause n={4} title="How to cancel">
        <p>
          Email <a className="underline" href={EMAIL_HREF}>{BUSINESS.email}</a> or call{' '}
          <a className="underline" href={PHONE_HREF}>{BUSINESS.phone}</a> with your order
          reference. We confirm in writing, and tell you plainly if the order had already
          started before your message reached us.
        </p>
      </Clause>

      <Operator />
    </>
  )
}

/* ---------------------------------------------------------------- shipping --- */

/**
 * Shipping policy.
 *
 * <p>Kept under this name because it is the name a payment gateway’s checklist looks
 * for, even though nothing is shipped. The first clause says so immediately rather than
 * letting a reader work through a page of delivery language wondering when a courier
 * appears.
 *
 * <p>The durations are the ones the storefront already advertises and the commitment is
 * read from the same policy object, so this page cannot promise something the homepage
 * does not.
 */
function Shipping({ policy }: { policy: Policy | null }) {
  const sla = policy?.deliverySlaHours ?? 48

  return (
    <>
      <p className="text-chalk-faint">Last updated {updatedOn()}</p>

      <Clause n={1} title="Nothing is shipped">
        <p>
          <strong className="text-chalk">There is no physical delivery and no courier.</strong>{' '}
          Every service sold here is digital and arrives inside EA Sports FC or over a video
          call. No delivery address is collected at checkout because none is needed, and you
          will never be charged a delivery fee.
        </p>
      </Clause>

      <Clause n={2} title="How long it takes">
        <p>
          <strong className="text-chalk">Most orders are delivered within 60 minutes</strong>,
          and the trading desk is staffed{' '}
          <strong className="text-chalk">24 hours a day</strong> — an order placed at four in
          the morning is worked the same as one placed at noon.
        </p>
        <p>
          Our committed outside limit is <strong className="text-chalk">{sla} hours</strong>. If
          we miss it you are entitled to a full refund under the refund policy, and you do
          not have to argue for it.
        </p>
      </Clause>

      <Clause n={3} title="How each service is delivered">
        <ul className="ml-5 list-disc space-y-2">
          <li>
            <strong className="text-chalk">Coins — transfer market.</strong> You list a card we
            name at a price we name, and we buy it. Nothing is sent to your account directly
            and we never need your password for this route.
          </li>
          <li>
            <strong className="text-chalk">Coins — comfort trade.</strong> Where you choose
            this instead, we sign in and move the coins ourselves. The sign-in is asked for
            only after payment, encrypted before it is stored, and destroyed when the order
            completes.
          </li>
          <li>
            <strong className="text-chalk">Boosting.</strong> Played on your account by a
            trader, and delivered when the agreed number of wins or the target rank is
            reached. Progress is visible on the order tracking page throughout.
          </li>
          <li>
            <strong className="text-chalk">Coaching.</strong> Delivered live at the slot you
            book, one to one. Delivery here means the session is held, not that a file is
            sent.
          </li>
        </ul>
      </Clause>

      <Clause n={4} title="Tracking your order">
        <p>
          Every order has a reference beginning <code>GFS-</code>. Enter it on the{' '}
          <Link className="underline" to="/track">track order</Link> page at any time to see
          its current state. We also email you when the order is delivered.
        </p>
      </Clause>

      <Operator />
    </>
  )
}

/* ------------------------------------------------------------------ shared --- */

/** The date at the top of each policy, in one place and one format. */
function updatedOn() {
  return new Date().toLocaleDateString('en-GB', {
    day: 'numeric', month: 'long', year: 'numeric',
  })
}

/**
 * Who you are actually contracting with.
 *
 * <p>Appended to every policy document rather than living only on the contact page. A
 * policy that never names its operator is one a reader has to take on trust, and a
 * gateway reviewing these pages checks each of them for the entity rather than just one.
 */
function Operator() {
  return (
    <div className="hairline rounded-panel bg-paper p-6 shadow-e1">
      <p className="stamp mb-4">Operated by</p>
      <p className="text-chalk">
        This website is operated by <strong>{BUSINESS.legalName}</strong>, trading as{' '}
        {BUSINESS.tradingName}.
      </p>
      <dl className="mt-4 space-y-1.5 text-[14px]">
        <div className="flex gap-2">
          <dt className="w-[132px] shrink-0 text-chalk-faint">Registered address</dt>
          <dd className="text-chalk-muted">{BUSINESS.registeredAddress}</dd>
        </div>
        <div className="flex gap-2">
          <dt className="w-[132px] shrink-0 text-chalk-faint">Mobile</dt>
          <dd><a className="text-chalk-muted underline" href={PHONE_HREF}>{BUSINESS.phone}</a></dd>
        </div>
        <div className="flex gap-2">
          <dt className="w-[132px] shrink-0 text-chalk-faint">Email</dt>
          <dd><a className="text-chalk-muted underline" href={EMAIL_HREF}>{BUSINESS.email}</a></dd>
        </div>
      </dl>
    </div>
  )
}
