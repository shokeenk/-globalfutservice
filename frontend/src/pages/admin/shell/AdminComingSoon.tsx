import { LuHourglass } from 'react-icons/lu'
import { useSeo } from '../../../lib/seo'
import { AdminPage } from './AdminPage'

/**
 * A section of the console that has a place in the navigation and nothing behind it yet.
 *
 * <p>Deliberately plain. A screen of placeholder tables and invented numbers would look
 * finished, and the one thing a console must never do is look like it knows something it
 * does not. This says what the section is and that it is not built.
 */
export function AdminComingSoon({ eyebrow, title }: { eyebrow: string; title: string }) {
  useSeo({ title, noindex: true })
  return (
    <AdminPage eyebrow={eyebrow} title={title}>
      <div className="rounded-admin-card border border-admin-line bg-white px-6 py-16 text-center shadow-admin-card">
        <span
          aria-hidden="true"
          className="mx-auto grid h-12 w-12 place-items-center rounded-full bg-admin-pink text-admin-red-text"
        >
          <LuHourglass className="h-5 w-5" />
        </span>
        <h2 className="mt-4 text-[17px] font-semibold text-admin-ink">Coming soon</h2>
        <p className="mx-auto mt-1.5 max-w-sm text-[14px] leading-relaxed text-admin-muted">
          {title} has not been built yet. When it is, it will be here.
        </p>
      </div>
    </AdminPage>
  )
}

export function AdminNotFound() {
  useSeo({ title: 'Not found', noindex: true })
  return (
    <AdminPage eyebrow="Admin" title="Page not found">
      <div className="rounded-admin-card border border-admin-line bg-white px-6 py-16 text-center shadow-admin-card">
        <p className="text-[14px] text-admin-muted">There is no console page at this address.</p>
      </div>
    </AdminPage>
  )
}
