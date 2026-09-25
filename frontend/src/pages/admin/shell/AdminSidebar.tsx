import { useEffect, useId, useState } from 'react'
import { NavLink, useLocation } from 'react-router-dom'
import { LuChevronDown, LuCrown, LuMenu } from 'react-icons/lu'
import { useAuth } from '../../../state/AuthContext'
import { isGroup, matches, navFor, type NavGroup, type NavLeaf } from './nav'

/**
 * The console's navigation column.
 *
 * <p>Three presentations of one list. {@code full} is the reference design, drawn at
 * 1280px and up and inside the mobile drawer. {@code rail} is the same list reduced to
 * its icons for the widths in between, where 197px of labels would take a sixth of the
 * screen from the order table. The rail cannot show a group's children, so it carries a
 * button that opens the full list in the drawer rather than hiding them.
 *
 * <p>Every entry is a real link or a real button, in document order, so the whole column
 * is reachable with Tab and nothing depends on hover. The active entry is announced with
 * {@code aria-current}, which NavLink sets, rather than by colour alone.
 */
export function AdminSidebar({
  variant, onExpand,
}: {
  variant: 'full' | 'rail'
  /** Rail only: open the full navigation in the drawer. */
  onExpand?: () => void
}) {
  const { account } = useAuth()
  const items = navFor(account?.role)
  const rail = variant === 'rail'

  return (
    <nav aria-label="Admin" className="flex h-full flex-col bg-admin-sidebar text-admin-sidebar-text">
      {rail ? (
        <div className="flex h-[72px] shrink-0 items-center justify-center">
          <button
            type="button"
            onClick={onExpand}
            aria-label="Show full menu"
            title="Show full menu"
            className="grid h-10 w-10 place-items-center rounded-admin-control text-admin-sidebar-text
                       transition-colors hover:bg-white/[0.07] focus-visible:outline-none
                       focus-visible:ring-2 focus-visible:ring-white/80"
          >
            <LuMenu aria-hidden="true" className="h-5 w-5" />
          </button>
        </div>
      ) : (
        <Wordmark />
      )}

      <ul className={`flex-1 overflow-y-auto pb-4 ${rail ? 'space-y-0.5 px-3' : 'px-2'}`}>
        {items.map((item) => (
          <li key={item.label}>
            {isGroup(item)
              ? <Group group={item} rail={rail} />
              : <Leaf leaf={item} rail={rail} />}
          </li>
        ))}
      </ul>

      {!rail && <BrandCard />}
    </nav>
  )
}

/**
 * Stand-in for the reference's "GFS" wordmark, which is not among the repository's
 * brand assets. The circular badge is the mark the storefront already uses; the caption
 * beneath matches the reference. Swap the image when the wordmark file exists.
 */
function Wordmark() {
  return (
    <div className="flex h-[72px] shrink-0 flex-col items-center justify-center gap-1">
      <img src="/brand/gfs-mark-128.png" alt="" width={34} height={34} className="h-[34px] w-[34px]" />
      <span className="text-[8.5px] font-semibold uppercase tracking-[0.3em] text-white">
        Global FUT Services
      </span>
    </div>
  )
}

const itemBase =
  'flex items-center rounded-admin-control text-admin-nav transition-colors duration-150 ' +
  'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-white/80'

function Leaf({ leaf, rail }: { leaf: NavLeaf; rail: boolean }) {
  const Icon = leaf.icon
  return (
    <NavLink
      to={leaf.to}
      aria-label={rail ? leaf.label : undefined}
      title={rail ? leaf.label : undefined}
      className={({ isActive }) => [
        itemBase,
        rail ? 'h-11 w-11 justify-center' : 'h-10 gap-4 px-3',
        isActive
          ? 'bg-admin-pink font-semibold text-admin-red-text'
          : 'font-normal hover:bg-white/[0.07] hover:text-white',
      ].join(' ')}
    >
      {Icon && <Icon aria-hidden="true" className="h-[19px] w-[19px] shrink-0" />}
      {!rail && <span className="truncate">{leaf.label}</span>}
    </NavLink>
  )
}

function Group({ group, rail }: { group: NavGroup; rail: boolean }) {
  const { pathname } = useLocation()
  const active = group.children.some((c) => matches(pathname, c.to))
  // Open by default when you are inside it, and otherwise closed: the reference shows the
  // group you are in expanded and the rest folded. After that it is the reader's choice.
  const [open, setOpen] = useState(active)
  // Arriving inside the group from elsewhere — the top-bar search, a link on a page —
  // opens it, so the highlighted child is never hidden inside a folded group.
  useEffect(() => { if (active) setOpen(true) }, [active])
  const listId = useId()
  const Icon = group.icon
  const first = group.children[0]

  if (rail) {
    // No room for the children, so the icon goes to the first of them. The rest are one
    // press away in the full menu the rail's top button opens.
    return first ? (
      <NavLink
        to={first.to}
        aria-label={group.label}
        title={group.label}
        className={[
          itemBase, 'h-11 w-11 justify-center',
          active ? 'bg-admin-pink text-admin-red-text' : 'hover:bg-white/[0.07] hover:text-white',
        ].join(' ')}
      >
        <Icon aria-hidden="true" className="h-[19px] w-[19px]" />
      </NavLink>
    ) : null
  }

  return (
    <>
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
        aria-controls={listId}
        className={[
          itemBase, 'h-10 w-full px-3 text-left',
          active
            ? 'bg-admin-pink font-semibold text-admin-red-text'
            : 'font-normal hover:bg-white/[0.07] hover:text-white',
        ].join(' ')}
      >
        <Icon aria-hidden="true" className="h-[19px] w-[19px] shrink-0" />
        <span className="ml-4 min-w-0 flex-1 truncate">{group.label}</span>
        <LuChevronDown
          aria-hidden="true"
          className={`h-4 w-4 shrink-0 transition-transform duration-200 ${open ? '' : '-rotate-90'}`}
        />
      </button>
      <ul
        id={listId}
        hidden={!open}
        className="ml-[12px] mt-[3px] space-y-0.5 border-l border-admin-sidebar-line pb-3"
      >
        {group.children.map((child) => (
          <li key={child.to}>
            <NavLink
              to={child.to}
              className={({ isActive }) => [
                itemBase, 'h-[29px] gap-4 pl-[13px] pr-3 text-[12.5px]',
                isActive
                  ? 'bg-admin-pink-sub font-semibold text-admin-red-text'
                  : 'font-normal hover:bg-white/[0.07] hover:text-white',
              ].join(' ')}
            >
              <span aria-hidden="true" className="h-[5px] w-[5px] shrink-0 rounded-full bg-current" />
              <span className="truncate">{child.label}</span>
            </NavLink>
          </li>
        ))}
      </ul>
    </>
  )
}

/**
 * The branded card at the foot of the column.
 *
 * <p>Text is 12px and 10px rather than the reference's 13px and 10.5px, and the card sits
 * 4px closer to the edges. The reference is set in a narrower face than this site's
 * Poppins, which needs ~25% more width for the same words; at the reference's sizes both
 * lines truncate.
 *
 * <p>"GFS" is set in a lighter red than the reference's fill red: at 11px on this ground
 * the fill red measures 3.45:1, under AA for text that small, and this one holds 5.26:1.
 * The crown is not text and needs only 3:1, so it keeps the fill red.
 */
function BrandCard() {
  return (
    <div className="mx-2 mb-10 mt-2 flex shrink-0 items-center gap-2 rounded-admin-control border
                    border-admin-sidebar-line bg-admin-sidebar-raise px-2.5 py-4">
      <LuCrown aria-hidden="true" fill="currentColor" className="h-7 w-7 shrink-0 text-admin-red" />
      <div className="min-w-0 leading-tight">
        <p className="text-[11px] font-bold text-[#FF4A55]">GFS</p>
        <p className="mt-0.5 truncate text-[12px] font-medium text-white">Global FUT Services</p>
        <p className="mt-1 truncate text-[10px] text-admin-sidebar-muted">Your Ultimate FC Partner</p>
      </div>
    </div>
  )
}
