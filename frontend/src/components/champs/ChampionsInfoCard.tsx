import { Link } from 'react-router-dom'
import { LuTrophy } from 'react-icons/lu'
import { useT } from '../../i18n'

/**
 * "Champions Boosting Information", on the boosting checkout before payment. Champions
 * orders only.
 *
 * <p>The client's copy, word for word, and no amount: what is owed is worked out after the
 * wins are recorded, so a number here would be a promise nobody can keep yet. "Live Chat
 * Support" goes to the Support page -- there is no order yet, so no order's own chat.
 */
export function ChampionsInfoCard() {
  const t = useT()
  const c = t.champs
  return (
    <div
      className="flex gap-4 rounded-panel border border-brand-500/25 bg-brand-500/[0.06] p-4 sm:p-5"
      data-testid="champs-info"
    >
      <span aria-hidden="true" className="mt-0.5 shrink-0 text-brand-500">
        <LuTrophy size={30} />
      </span>
      <div className="min-w-0">
        <h2 className="display text-[15px] font-semibold text-chalk">{c.infoTitle}</h2>
        <p className="mt-1.5 text-[12.5px] leading-relaxed text-chalk-muted">
          {c.infoBody}{' '}
          <Link to="/support" className="font-semibold text-brand-400 hover:underline">{c.liveChat}</Link>.
        </p>
      </div>
    </div>
  )
}
