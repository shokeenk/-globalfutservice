import { useCallback, useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { api, ApiError } from '../../lib/api'
import { useSeo } from '../../lib/seo'
import { AdminPage } from './shell/AdminPage'
import { StatusBadge } from './ui/Badge'
import { AdminButton } from './ui/controls'
import { TableCard, TableState, Th } from './ui/Table'
import { PopupEditor } from './popups/PopupEditor'
import { PAGE_LABELS, scheduleText, stateLabel } from './popups/types'
import type { AdminPopup } from './popups/types'

/**
 * The Popup Manager. Admins only: the route is behind RequireAdmin and every endpoint is
 * hasRole('ADMIN'). Every change is logged on the server, with who made it.
 *
 * <p>A popup is live only when it is published, switched on and inside its schedule. The
 * list says which of those it is, and each is its own button.
 */
export default function AdminPopups() {
  useSeo({ title: 'Popups', noindex: true })
  const [params, setParams] = useSearchParams()
  const editing = params.get('edit')
  const [popups, setPopups] = useState<AdminPopup[] | null>(null)
  const [failed, setFailed] = useState(false)
  const [steps, setSteps] = useState<string[]>([])
  const [busy, setBusy] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    setFailed(false)
    try {
      setPopups(await api.get<AdminPopup[]>('/api/v1/admin/popups'))
    } catch {
      setFailed(true)
    }
  }, [])

  useEffect(() => {
    void load()
    api.get<{ steps: string[] }>('/api/v1/admin/popups/options').then((o) => setSteps(o.steps)).catch(() => {})
  }, [load])

  const open = (id: string | null) => setParams(id ? { edit: id } : {}, { replace: false })

  async function act(id: string, call: () => Promise<unknown>) {
    setBusy(id)
    setError(null)
    try {
      await call()
      await load()
    } catch (e) {
      setError(e instanceof ApiError
        ? Object.values(e.fieldErrors).join(' ') || e.message
        : 'That did not work. Try again.')
    } finally {
      setBusy(null)
    }
  }

  const current = editing && editing !== 'new' ? popups?.find((p) => p.id === editing) ?? null : null

  if (editing) {
    if (editing !== 'new' && !current) {
      return <AdminPage eyebrow="Marketing" title="Popups">{popups ? <p>That popup is not there any more.</p> : <p>Loading…</p>}</AdminPage>
    }
    return (
      <AdminPage eyebrow="Popups" title={current ? current.name : 'New popup'}
                 description="Saved as a draft, switched off. Publish and switch it on from the list.">
        <PopupEditor key={current?.id ?? 'new'} popup={current} steps={steps}
                     onSaved={(p) => { setPopups((list) => [p, ...(list ?? []).filter((x) => x.id !== p.id)]); if (!current) open(p.id) }}
                     onClose={() => open(null)} />
      </AdminPage>
    )
  }

  return (
    <AdminPage eyebrow="Marketing" title="Popups"
               description="Offers shown on the storefront. Nothing shows until it is published, switched on and inside its schedule."
               action={<AdminButton variant="primary" onClick={() => open('new')}>New popup</AdminButton>}>
      {error && <p role="alert" className="mb-3 rounded-admin-control border border-[#F3B9BE] bg-[#FDF1F2] p-3 text-[13px] text-admin-red-text">{error}</p>}
      <TableCard>
        <table className="w-full min-w-[980px] border-collapse text-admin-cell">
          <thead className="bg-[#FAFBFC]">
            <tr className="border-b border-admin-line">
              <Th>Popup</Th>
              <Th>Status</Th>
              <Th>Pages</Th>
              <Th>Schedule</Th>
              <Th>Views (30 days)</Th>
              <Th>Closes</Th>
              <Th>Clicks</Th>
              <Th className="text-right">Actions</Th>
            </tr>
          </thead>
          {failed && !popups ? (
            <TableState kind="error" columns={8} title="Could not load popups" onRetry={() => void load()}>
              The list did not come back. Nothing has changed.
            </TableState>
          ) : !popups ? (
            <TableState kind="loading" columns={8} />
          ) : popups.length === 0 ? (
            <TableState kind="empty" columns={8} title="No popups yet">Create one to start.</TableState>
          ) : (
            <tbody>
              {popups.map((p) => {
                const state = stateLabel(p)
                const off = busy === p.id
                return (
                  <tr key={p.id} className="border-b border-admin-line last:border-0" data-testid={`popup-row-${p.id}`}>
                    <td className="px-3 py-2.5">
                      <p className="font-medium text-admin-ink">{p.name}</p>
                      <p className="text-[12px] text-admin-muted">
                        {p.type === 'CENTER_MODAL' ? 'Centred modal' : 'Bottom-right card'} · v{p.version}
                      </p>
                    </td>
                    <td className="px-3 py-2.5"><StatusBadge label={state.label} tone={state.tone} /></td>
                    <td className="px-3 py-2.5 text-[12.5px] text-admin-ink">
                      {[...p.targetPages.map((x) => PAGE_LABELS[x]), ...p.targetPaths].join(', ') || '—'}
                    </td>
                    <td className="px-3 py-2.5 text-[12.5px] text-admin-ink">{scheduleText(p)}</td>
                    <td className="px-3 py-2.5 tabular-nums">{p.stats.views30d}</td>
                    <td className="px-3 py-2.5 tabular-nums">{p.stats.closes30d}</td>
                    <td className="px-3 py-2.5 tabular-nums">{p.stats.clicks30d}</td>
                    <td className="px-3 py-2.5">
                      <div className="flex flex-wrap justify-end gap-1.5">
                        <AdminButton size="sm" onClick={() => open(p.id)}>Edit</AdminButton>
                        {p.status === 'DRAFT'
                          ? <AdminButton size="sm" disabled={off} onClick={() => void act(p.id, () => api.post(`/api/v1/admin/popups/${p.id}/publish`))}>Publish</AdminButton>
                          : <AdminButton size="sm" disabled={off} onClick={() => void act(p.id, () => api.post(`/api/v1/admin/popups/${p.id}/unpublish`))}>Unpublish</AdminButton>}
                        <AdminButton size="sm" disabled={off} aria-pressed={p.enabled}
                                     onClick={() => void act(p.id, () => api.put(`/api/v1/admin/popups/${p.id}/enabled`, { enabled: !p.enabled }))}>
                          {p.enabled ? 'Switch off' : 'Switch on'}
                        </AdminButton>
                        <AdminButton size="sm" disabled={off} onClick={() => void act(p.id, () => api.post(`/api/v1/admin/popups/${p.id}/duplicate`))}>Duplicate</AdminButton>
                        <AdminButton size="sm" disabled={off}
                                     onClick={() => {
                                       if (window.confirm(`Delete “${p.name}”? It comes off the site and out of this list.`)) {
                                         void act(p.id, () => api.del(`/api/v1/admin/popups/${p.id}`))
                                       }
                                     }}>Delete</AdminButton>
                      </div>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          )}
        </table>
      </TableCard>
    </AdminPage>
  )
}
