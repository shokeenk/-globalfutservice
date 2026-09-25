import type { ReactNode } from 'react'

/**
 * The top of every console page: a small uppercase section label, the page title, one
 * line of description, and an optional action on the right.
 *
 * <p>Replaces the storefront's PageHeader inside the console. That one is a hero — an
 * animated backdrop, a gradient title, a centred measure — which is right for a landing
 * page and wrong for a screen somebody works in all day. This is flat, left-aligned and
 * sized from the reference: the title is read once, the content below it is read
 * constantly, and the header should cost as little vertical space as it can.
 *
 * <p>The page's own content is passed through untouched. Wrapping a screen in this is
 * meant to change its frame and nothing inside it.
 */
export function AdminPage({
  eyebrow, title, description, action, children,
}: {
  eyebrow: string
  title: ReactNode
  description?: ReactNode
  /** A control that belongs to the page as a whole, e.g. "Campaign History". */
  action?: ReactNode
  children?: ReactNode
}) {
  return (
    <>
      <header className="mb-5 flex flex-wrap items-start justify-between gap-x-6 gap-y-4">
        <div className="min-w-0">
          <p className="text-admin-eyebrow font-medium uppercase text-admin-faint">{eyebrow}</p>
          <h1 className="mt-1 text-balance text-admin-title font-bold text-admin-ink">{title}</h1>
          {description && (
            <p className="text-[14px] leading-snug text-admin-muted">{description}</p>
          )}
        </div>
        {action && <div className="flex shrink-0 items-center gap-3 sm:mt-[19px]">{action}</div>}
      </header>
      {children}
    </>
  )
}
