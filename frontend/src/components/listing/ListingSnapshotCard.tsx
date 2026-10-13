import { LuCircleCheck } from 'react-icons/lu'
import { useT } from '../../i18n'
import type { ListingSnapshot } from '../../lib/types'
import { listingImageSrc } from '../../lib/listings'

/**
 * An Objectives or Tournaments order's service, as it was sold: the listing frozen when the
 * order was placed, never the listing as it reads now. The team can retitle, reprice or
 * delete a listing and this does not move.
 */
export function ListingSnapshotCard({ snapshot }: { snapshot: ListingSnapshot }) {
  const t = useT()
  const c = t.listingCheckout
  const time = [snapshot.estimatedTime, snapshot.schedulingNote].filter(Boolean) as string[]
  const rows: [string, string][] = [
    ...(snapshot.hours != null
      ? [[c.hoursBought, c.hours(snapshot.hours)], [c.pricePerHour, snapshot.unitPriceFormatted]] as [string, string][]
      : [[c.price, snapshot.priceFormatted]] as [string, string][]),
    ...(snapshot.requiredGames != null ? [[c.requiredGames, String(snapshot.requiredGames)]] as [string, string][] : []),
    ...(time.length > 0 ? [[c.estimatedTime, time.join(' · ')]] as [string, string][] : []),
    ...(snapshot.reward ? [[c.reward, snapshot.reward]] as [string, string][] : []),
  ]

  return (
    <div data-testid="listing-snapshot">
      <p className="stamp mb-4">{c.orderedTitle}</p>
      <div className="rounded-panel border border-ink-400 bg-paper p-5">
        <div className="flex items-start gap-4">
          {snapshot.imageUrl && (
            <span className="grid h-20 w-16 shrink-0 place-items-center">
              <img src={listingImageSrc(snapshot.imageUrl) ?? undefined} alt="" aria-hidden="true" className="h-full w-full object-contain" />
            </span>
          )}
          <div className="min-w-0">
            <p className="text-[11px] font-semibold uppercase tracking-[0.12em] text-chalk-faint">
              {t.catalog.services[snapshot.category] ?? snapshot.categoryName}
            </p>
            <h3 className="mt-1 text-[15px] font-semibold text-chalk">{snapshot.title}</h3>
            {snapshot.subtitle && <p className="mt-0.5 text-[13px] text-chalk-muted">{snapshot.subtitle}</p>}
          </div>
        </div>

        {rows.length > 0 && (
          <dl className="mt-4 divide-y divide-ink-400/70 border-t border-ink-400/70">
            {rows.map(([label, value]) => (
              <div key={label} className="flex justify-between gap-4 py-2.5 text-[13px]">
                <dt className="text-chalk-muted">{label}</dt>
                <dd className="tnum text-right font-semibold text-chalk">{value}</dd>
              </div>
            ))}
          </dl>
        )}

        {snapshot.requirements.length > 0 && (
          <div className="mt-4">
            <p className="text-[13px] font-semibold text-chalk">{c.tasksIncluded}</p>
            <ul className="mt-2 space-y-2">
              {snapshot.requirements.map((task, index) => (
                <li key={index} className="flex gap-2.5">
                  <LuCircleCheck aria-hidden="true" size={16} className="mt-0.5 shrink-0 text-brand-500" />
                  <span className="whitespace-pre-line text-[12.5px] leading-relaxed text-chalk-muted">{task}</span>
                </li>
              ))}
            </ul>
          </div>
        )}

        {snapshot.customerInstructions && (
          <div className="mt-4 rounded-edge bg-ink-700 px-4 py-3">
            <p className="text-[12.5px] font-semibold text-chalk">{c.customerInstructions}</p>
            <p className="mt-1 whitespace-pre-line text-[12.5px] leading-relaxed text-chalk-muted">
              {snapshot.customerInstructions}
            </p>
          </div>
        )}

        {snapshot.hourlyAcknowledgedAt && (
          <p className="mt-4 text-[12px] text-chalk-faint" data-testid="snapshot-acknowledged">{c.acknowledged}</p>
        )}
      </div>
    </div>
  )
}
