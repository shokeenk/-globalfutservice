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
        {doc === 'terms' && <Terms />}
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
/**
 * Terms of service.
 *
 * <p><b>This is the client's finalised document, reproduced rather than authored.</b>
 * The wording is theirs and is not paraphrased, tightened or merged with what was here
 * before. Where a figure in it disagrees with one the software enforces, the
 * disagreement is reported rather than quietly reconciled in either direction — a
 * developer picking a number in a legal document is how a business ends up bound to a
 * promise nobody in it agreed to.
 *
 * <p><b>It replaced the previous terms rather than being added beside them.</b> A site
 * carrying two terms-of-service documents that disagree is worse than one that
 * disagrees with its own configuration: in a dispute, the customer gets to pick which
 * one they read.
 *
 * <p>The PDF's publication note — that this is a working draft pending review by Indian
 * counsel — is deliberately not published. It is guidance addressed to GFS, not a term
 * offered to a customer, and putting it on the page would tell every reader the
 * contract they are agreeing to is provisional.
 */
function Terms() {
  return (
    <>
      <p className="text-chalk-faint">Last updated 3 September 2026</p>

      <Clause n={1} title="About GFS & Our Services">
        <p>
          Global FUT Services provides independent trading (coins), boosting, consultation
          and coaching services for EA SPORTS FC.
        </p>
        <p>
          When you place an order, you are purchasing the selected service from GFS. We do
          not sell or claim ownership of EA SPORTS FC in-game currency, player cards or
          other EA-owned in-game assets.
        </p>
        <p>
          Global FUT Services is an independent third-party service provider and is not
          affiliated with, endorsed by, sponsored by, or officially connected with
          Electronic Arts Inc. or EA SPORTS.
        </p>
      </Clause>

      <Clause n={2} title="Eligibility & Requirements">
        <p>
          You must be legally able to enter into a contract in your country to use our
          services.
        </p>
        <p>
          Depending on the service, you may need access to the relevant EA account, game,
          platform, Web/Companion App, Transfer Market and other requirements shown during
          checkout.
        </p>
      </Clause>

      <Clause n={3} title="Orders, Pricing & Payment">
        <p>
          All prices are shown before payment. The price displayed at checkout is the
          amount shown before completing your purchase.
        </p>
        <p>
          Applicable fees, taxes and discounts are displayed during checkout. We will not
          knowingly charge an amount that was not shown before payment.
        </p>
        <p>Payment must be made using a payment method you are authorized to use.</p>
      </Clause>

      <Clause n={4} title="Trading Services">
        <p>
          GFS provides trading-related services through the applicable EA SPORTS FC market.
        </p>
        <p>
          Customers must provide the information and account conditions required to
          complete the order. The selected amount, pricing and applicable requirements are
          shown before checkout.
        </p>
      </Clause>

      <Clause n={5} title="Boosting Services">
        <p>
          A professional player may play on your account to work toward your selected rank.
        </p>
        <p>
          Rare issues such as disconnections, server problems, maintenance or heavy delays
          may affect the result. If we achieve a lower rank than selected, the price
          difference will be credited back to you.
        </p>
        <p>
          You may use the credit toward another order or request a refund of the credited
          amount.
        </p>
      </Clause>

      <Clause n={6} title="Coaching Services">
        <p>
          GFS coaching is provided through scheduled sessions. Package details and session
          duration are shown before purchase.
        </p>
        <p>
          After ordering, customers can connect with their coach or GFS Support through our
          official Discord for scheduling and session-related communication.
        </p>
        <p>
          Official Discord:{' '}
          <a className="underline" href={BUSINESS.discordInvite} target="_blank" rel="noreferrer">
            {BUSINESS.discordName}
          </a>
        </p>
      </Clause>

      <Clause n={7} title="Delivery & Completion">
        <p>
          We aim to complete orders within the estimated delivery time shown on the website.
        </p>
        <p>
          Our maximum contractual delivery period is{' '}
          <strong className="text-chalk">48 hours</strong>, starting from payment or from
          receipt of required customer information or completion of required account
          conditions, whichever is later.
        </p>
      </Clause>

      <Clause n={8} title="Refunds & Cancellations">
        <p>
          If we cannot complete an order within the applicable delivery commitment, you may
          request a refund or choose to use the amount toward another order.
        </p>
        <p>
          Once an order has been completed and marked as delivered, it is considered
          complete, subject to the 100% Safety Policy and any other rights available under
          applicable law.
        </p>
      </Clause>

      <Clause n={9} title="100% Safety Policy">
        <p>
          If your account is affected within{' '}
          <strong className="text-chalk">24 hours</strong> of your order, you are covered by
          our 100% Safety Policy.
        </p>
        <ul className="ml-5 list-disc space-y-1.5">
          <li>
            <strong className="text-chalk">Full Refund:</strong> Receive a refund for the
            affected order.
          </li>
          <li>
            <strong className="text-chalk">Replacement Account:</strong> Receive a
            replacement account with the same amount of coins ordered, along with Ultimate
            Team access and an open Transfer Market where applicable.
          </li>
        </ul>
        <p>
          We will make reasonable efforts to provide a playable team similar in value to
          your existing team, but the exact same players or squad are not guaranteed.
        </p>
        <p>
          The replacement account does not guarantee ownership or inclusion of the EA
          SPORTS FC game itself. You must have access to the relevant game and platform to
          use it.
        </p>
        <p>
          Official Discord:{' '}
          <a className="underline" href={BUSINESS.discordInvite} target="_blank" rel="noreferrer">
            {BUSINESS.discordName}
          </a>
        </p>
      </Clause>

      <Clause n={10} title="Rewards & Loyalty">
        <p>
          Earn points as you use GFS and progress through six lifetime tiers, with discounts
          of up to 5%.
        </p>
        <p>
          Points are added after the applicable 24-hour Safety Policy period. Up to 20% of
          an order can be paid using points.
        </p>
        <p>
          Using points does not reduce your lifetime tier. Account holders can also earn 3
          points per day through daily check-in.
        </p>
        <p>
          Guest orders cannot earn or store points. Points have no cash value and cannot be
          withdrawn.
        </p>
        <p>
          GFS may correct or restrict rewards in cases of technical errors, duplicate
          accounts, fraud or misuse.
        </p>
      </Clause>

      <Clause n={11} title="Account Details & Security">
        <p>Some services require access to your EA or console account.</p>
        <p>
          Where required, account credentials, backup codes and related information are
          encrypted, accessed only when necessary to fulfil your order, and deleted after
          use.
        </p>
        <p>
          You remain responsible for your account security. We recommend changing your
          password and regenerating backup codes after the service is completed.
        </p>
      </Clause>

      <Clause n={12} title="Customer Responsibilities">
        <p>
          You agree to provide accurate information and only provide details for an account
          you are authorized to use.
        </p>
        <p>
          You must not use GFS services unlawfully, provide false information, misuse the
          website, interfere with our services, or knowingly attempt to obtain duplicate
          refunds or payment reversals for completed services.
        </p>
      </Clause>

      <Clause n={13} title="EA / EA SPORTS FC Disclaimer">
        <p>
          EA SPORTS FC, Electronic Arts, EA and related names, trademarks and intellectual
          property belong to their respective owners.
        </p>
        <p>
          GFS is an independent third-party provider and is not affiliated with, endorsed
          by, sponsored by, or officially connected with Electronic Arts Inc. or EA SPORTS.
        </p>
        <p>
          Customers acknowledge that EA may apply its own rules, restrictions or enforcement
          to EA accounts and services.
        </p>
      </Clause>

      <Clause n={14} title="Privacy & Data Handling">
        <p>
          GFS may collect information needed to provide and manage its services, including
          name, email address, optional phone number, GFS account details, EA account
          details, backup codes, console login details where required, Discord username,
          payment information, order history and rewards history.
        </p>
        <p>
          Payment processing is handled by our payment providers. GFS does not store
          sensitive card or banking details. EA account, backup-code and console-login
          information is encrypted, accessed only when required to fulfil an order, and
          deleted after use.
        </p>
        <p>For privacy questions or requests to correct or delete your information:</p>
        <ul className="ml-5 list-disc space-y-1">
          <li>
            Email: <a className="underline" href={EMAIL_HREF}>{BUSINESS.email}</a>
          </li>
          <li>
            Discord:{' '}
            <a className="underline" href={BUSINESS.discordInvite} target="_blank" rel="noreferrer">
              {BUSINESS.discordName}
            </a>
          </li>
        </ul>
      </Clause>

      <Clause n={15} title="Service Availability & Third-Party Issues">
        <p>
          Services may occasionally be affected by EA server outages, maintenance, game
          updates, connection problems or platform issues.
        </p>
        <p>
          If we cannot complete an order because of such an issue, you may choose a full
          refund or use the amount toward a future order.
        </p>
        <p>
          Where a service can safely continue later, we may resume it once the issue is
          resolved.
        </p>
      </Clause>

      <Clause n={16} title="Chargebacks & Payment Disputes">
        <p>
          If you have an issue with your order or payment, please contact GFS Support or our
          official Discord first so we can review and resolve it in good faith.
        </p>
        <p>
          For disputed orders, GFS may provide relevant payment, order, delivery and
          communication records to the payment provider.
        </p>
        <p>
          If a payment has already been refunded, the same transaction should not be
          disputed again.
        </p>
      </Clause>

      <Clause n={17} title="Suspension & Termination">
        <p>
          GFS may suspend or cancel an order or account where we identify fraud, payment
          abuse, false information, misuse of our services, or other activity that puts GFS
          or its customers at risk.
        </p>
      </Clause>

      <Clause n={18} title="Liability & Service Risk">
        <p>
          EA SPORTS FC services involve risks that may arise from the game publisher,
          platform or other third parties.
        </p>
        <p>
          GFS will provide the protections specifically stated in these Terms, including the
          100% Safety Policy where applicable.
        </p>
        <p>
          To the extent permitted by applicable law, GFS will not be responsible for indirect
          or consequential losses. Nothing in these Terms excludes or limits liability that
          cannot lawfully be excluded or limited.
        </p>
      </Clause>

      <Clause n={19} title="Dispute Resolution">
        <p>Please contact GFS Support or our official Discord first.</p>
        <p>
          We will review the available order, payment, delivery, account and communication
          records and work to resolve the issue fairly.
        </p>
        <p>
          Where applicable, coin orders may be reviewed using opening and closing account
          balances and other delivery evidence.
        </p>
        <p>
          If the matter cannot be resolved directly, customers retain any dispute-resolution
          or consumer remedies available under applicable law.
        </p>
      </Clause>

      <Clause n={20} title="Grievance & Customer Support">
        <p>
          For concerns about your order or service, contact GFS Support or our official
          Discord.
        </p>
        <ul className="ml-5 list-disc space-y-1">
          <li>
            Email: <a className="underline" href={EMAIL_HREF}>{BUSINESS.email}</a>
          </li>
          <li>
            Discord:{' '}
            <a className="underline" href={BUSINESS.discordInvite} target="_blank" rel="noreferrer">
              {BUSINESS.discordName}
            </a>
          </li>
        </ul>
        <p>
          For formal complaints, please use the same email or official Discord so the matter
          can be documented and reviewed.
        </p>
      </Clause>

      <Clause n={21} title="Governing Law">
        <p>
          These Terms are governed by the laws of India, subject to any mandatory consumer
          or other rights that may apply to you under applicable law.
        </p>
      </Clause>

      <Clause n={22} title="Changes to These Terms">
        <p>We may update these Terms from time to time.</p>
        <p>
          The version applicable to your order will be the version published when you placed
          the order, unless applicable law requires otherwise.
        </p>
      </Clause>

      <Clause n={23} title="Contact">
        <p>Global FUT Services</p>
        <ul className="ml-5 list-disc space-y-1">
          <li>
            Email: <a className="underline" href={EMAIL_HREF}>{BUSINESS.email}</a>
          </li>
          <li>Official Discord: {BUSINESS.discordName}</li>
          <li>
            Discord:{' '}
            <a className="underline" href={BUSINESS.discordInvite} target="_blank" rel="noreferrer">
              {BUSINESS.discordInvite}
            </a>
          </li>
        </ul>
        <p>The current business/legal address is maintained in the website footer.</p>
      </Clause>

      <Operator />
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
/**
 * AML &amp; KYC policy.
 *
 * <p><b>A full replacement, not an edit.</b> The previous version claimed more than the
 * business actually operates, and three claims in particular were removed on the
 * client's written instruction rather than softened:
 *
 * <ul>
 *   <li>a fixed cumulative-spend verification threshold — now "unusual" transactions,
 *       because a published threshold is a commitment to a control that has to exist;</li>
 *   <li>automatic reporting of suspicious activity to FIU-IND — now information "may" be
 *       provided "where legally required", because claiming a reporting relationship you
 *       do not have is a worse position than claiming none;</li>
 *   <li>a five-year retention promise — now "as reasonably required", because a fixed
 *       period is a promise to delete on a schedule nothing enforces.</li>
 * </ul>
 *
 * <p>None of the three may be reintroduced for sounding more thorough. Each one reads as
 * reassurance and functions as an unbacked undertaking, which is the failure mode the
 * replacement exists to close.
 *
 * <p>Only the "Final AML &amp; KYC wording for the website" block of the supplied change
 * note is published. The rest of that document — the rationale, the numbered
 * implementation notes and the status line — is instruction addressed to whoever makes
 * this change, and none of it is a term offered to a customer.
 */
function Aml() {
  return (
    <>
      <p className="text-chalk-faint">Last updated {updatedOn()}</p>

      <p className="text-chalk">
        Anti-Money Laundering, Fraud Prevention &amp; Customer Verification
      </p>

      <Clause n={1} title="Purpose">
        <p>
          GFS takes fraud, unauthorized payments and financial misuse seriously. We may
          carry out customer and transaction checks when required by law, our payment
          providers, or our security procedures.
        </p>
      </Clause>

      <Clause n={2} title="Customer Verification">
        <p>
          Most customers will not need additional verification. We may request identity or
          address information where a transaction is unusual, requires additional
          verification, or is requested by our payment provider or applicable law.
        </p>
      </Clause>

      <Clause n={3} title="Transaction Monitoring">
        <p>
          We may review unusual payment patterns, false or mismatched information, repeated
          payment disputes, suspicious activity, or other signs of fraud or misuse.
        </p>
      </Clause>

      <Clause n={4} title="Restricted Transactions">
        <p>
          We may decline, delay, cancel or refund an order where required information cannot
          be verified or where completing the transaction creates a legal, payment or
          security risk.
        </p>
      </Clause>

      <Clause n={5} title="Records & Compliance">
        <p>
          We retain relevant order and transaction records as reasonably required for fraud
          prevention, customer support, payment disputes, payment-provider requirements and
          applicable law. Where disclosure or reporting is legally required, we may provide
          information to the relevant authority or payment provider.
        </p>
      </Clause>

      <Clause n={6} title="Review">
        <p>
          We review this policy periodically and when our services, payment arrangements or
          applicable requirements change.
        </p>
      </Clause>

      <Operator />
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
          Our maximum contractual delivery period is{' '}
          <strong className="text-chalk">{sla} hours</strong>, starting from payment or from
          receipt of required customer information or completion of required account
          conditions, <strong className="text-chalk">whichever is later</strong>.
        </p>
        <p>
          If we cannot complete an order within that commitment, you may{' '}
          <strong className="text-chalk">request a refund</strong> or{' '}
          <strong className="text-chalk">use the amount toward another order</strong>. You do
          not have to give a reason, and you may ask for a status update instead if you
          would rather wait.
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

      {/*
        The 100% Safety Policy, as its own named block.

        It is referenced by name throughout the terms of service — clause 8 makes a
        completed order "subject to the 100% Safety Policy", and clauses 18 and 19 treat
        it as the protection GFS actually undertakes — so it is set apart rather than
        folded into the surrounding refund prose. A named policy that only exists as a
        paragraph inside another one is a policy a customer cannot find when they need it.

        UNRESOLVED, AND DELIBERATELY LEFT SO: the clause above states a 7-day guarantee
        with a 50%/100% split, which is the remedy the software actually settles and the
        figure the homepage advertises. This block states 24 hours with a full refund or a
        replacement account. They are different windows and different remedies, and which
        one is correct is a business decision rather than a developer's. Both are shown so
        the disagreement is visible rather than silently decided.
      */}
      <div className="hairline rounded-panel border-brand-500/40 bg-brand-500/[0.05] p-6">
        <p className="stamp mb-4">100% Safety Policy</p>
        <p className="text-chalk">
          If your account is affected within{' '}
          <strong>24 hours</strong> of your order, you are covered by our 100% Safety Policy.
        </p>
        <ul className="ml-5 mt-4 list-disc space-y-2">
          <li>
            <strong className="text-chalk">Full Refund:</strong> Receive a refund for the
            affected order.
          </li>
          <li>
            <strong className="text-chalk">Replacement Account:</strong> Receive a
            replacement account with the same amount of coins ordered, along with Ultimate
            Team access and an open Transfer Market where applicable.
          </li>
        </ul>
        <p className="mt-4">
          We will make reasonable efforts to provide a playable team similar in value to
          your existing team, but the exact same players or squad are not guaranteed.
        </p>
        <p className="mt-3">
          The replacement account does not guarantee ownership or inclusion of the EA SPORTS
          FC game itself. You must have access to the relevant game and platform to use it.
        </p>
        <p className="mt-4 text-[14px]">
          To make a claim, contact us on our official Discord:{' '}
          <a className="underline" href={BUSINESS.discordInvite} target="_blank" rel="noreferrer">
            {BUSINESS.discordName}
          </a>
        </p>
      </div>

      <Clause n={5} title="Boosting — a lower rank than the one ordered">
        <p>
          A professional player may play on your account to work toward your selected rank.
          Rare issues such as disconnections, server problems, maintenance or heavy delays
          may affect the result.
        </p>
        <p>
          If we achieve a lower rank than selected, the{' '}
          <strong className="text-chalk">price difference is credited back to you</strong>.
          You may use the credit toward another order or request a refund of the credited
          amount.
        </p>
      </Clause>

      <Clause n={6} title="How refunds are paid">
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

      <Clause n={7} title="How to request one">
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
