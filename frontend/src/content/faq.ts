import { useT } from '../i18n'

/**
 * The help-centre questions, in one place.
 *
 * <p>Three things render these: the help page, the site search, and the ask widget in
 * the corner. They used to build the list independently, which is a guarantee that
 * someone adds a question in one and the other two quietly disagree — the widget was
 * still serving nine questions from a list of its own when the help page moved here.
 * One source, three consumers.
 *
 * <p>The `key` is deliberately not derived from the question text. It is the anchor a
 * search result deep-links to, so it has to survive translation and rewording; a slug
 * made from "What is your refund policy?" would break the moment either changed.
 *
 * <p><b>No interpolation any more.</b> The previous set wove live policy numbers into
 * the answers — the guarantee window, the delivery SLA — so the copy could never
 * disagree with configuration. This set is the client's own wording and states its own
 * figures, so the two are no longer coupled. That is a deliberate trade and it has a
 * cost: change `deliverySlaHours` in configuration now and the FAQ will not follow.
 */
export interface FaqItem {
  /** Stable, locale-independent anchor id. */
  key: string
  question: string
  answer: string
}

export interface FaqGroup {
  title: string
  items: FaqItem[]
}

export function useFaq(): FaqGroup[] {
  const t = useT()

  return [
    {
      title: t.help.groupBefore,
      items: [
        { key: 'services', question: t.help.qServices, answer: t.help.aServices },
        { key: 'safety', question: t.help.qSafety, answer: t.help.aSafety },
        { key: 'partners', question: t.help.qPartners, answer: t.help.aPartners },
        { key: 'speed', question: t.help.qSpeed, answer: t.help.aSpeed },
      ],
    },
    {
      title: t.help.groupOrdering,
      items: [
        { key: 'ordering', question: t.help.qOrdering, answer: t.help.aOrdering },
        { key: 'credentials', question: t.help.qCredentials, answer: t.help.aCredentials },
        // Deep-linked from the credential form's "How to find backup codes" button, so
        // the key is load-bearing: it is the `#faq-backup-codes` anchor, not a slug.
        { key: 'backup-codes', question: t.help.qBackupCodes, answer: t.help.aBackupCodes },
        { key: 'tax', question: t.help.qTax, answer: t.help.aTax },
      ],
    },
    {
      title: t.help.groupMoney,
      items: [
        { key: 'payment', question: t.help.qPayment, answer: t.help.aPayment },
        { key: 'refund', question: t.help.qRefund, answer: t.help.aRefund },
        { key: 'support', question: t.help.qSupport, answer: t.help.aSupport },
      ],
    },
  ]
}

/** The same questions as one flat list, for consumers that do not group. */
export function useFaqFlat(): FaqItem[] {
  return useFaq().flatMap((group) => group.items)
}
