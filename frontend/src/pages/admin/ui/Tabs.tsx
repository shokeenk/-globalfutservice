import type { IconType } from 'react-icons'

export interface TabItem {
  key: string
  label: string
  /** Null while the counts are loading or could not be read: no pill rather than a 0. */
  count: number | null
  icon?: IconType
}

/**
 * A row of filter tabs with counts, as the Orders reference draws two of.
 *
 * <p>Buttons that are pressed or not, in a labelled group, rather than an ARIA tablist:
 * these filter one table, they do not switch between panels, and a tablist promises a
 * screen-reader user panels that are not there. The row scrolls sideways on a narrow
 * screen instead of wrapping into a second line of tabs.
 */
export function TabRow({
  items, active, onChange, label, size = 'md',
}: {
  items: TabItem[]
  active: string | null
  onChange: (key: string) => void
  label: string
  /** {@code lg} for the service row, with icons; {@code md} for the status row. */
  size?: 'lg' | 'md'
}) {
  return (
    <div role="group" aria-label={label} className="flex overflow-x-auto">
      {items.map((item) => {
        const on = item.key === active
        const Icon = item.icon
        return (
          <button
            key={item.key}
            type="button"
            aria-pressed={on}
            onClick={() => onChange(item.key)}
            className={[
              'relative flex shrink-0 items-center gap-2.5 whitespace-nowrap transition-colors',
              'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-admin-red',
              size === 'lg' ? 'h-[52px] px-5 text-[14.5px] font-semibold' : 'h-11 px-4 text-[13px] font-medium',
              on ? 'bg-[#FEF5F6] text-admin-red-text' : 'text-admin-ink hover:bg-admin-page',
            ].join(' ')}
          >
            {Icon && (
              <Icon aria-hidden="true" className={`h-5 w-5 ${on ? 'text-admin-red' : 'text-admin-faint'}`} />
            )}
            {item.label}
            {item.count !== null && (
              <span
                className={`min-w-[26px] rounded-[6px] px-1.5 py-0.5 text-center text-[12px] font-semibold tabular-nums
                            ${on ? 'bg-admin-red text-white' : 'bg-[#EEF0F3] text-admin-ink'}`}
              >
                <span className="sr-only">, </span>
                {item.count.toLocaleString('en-IN')}
              </span>
            )}
            {on && <span aria-hidden="true" className="absolute inset-x-0 bottom-0 h-[2px] bg-admin-red" />}
          </button>
        )
      })}
    </div>
  )
}
