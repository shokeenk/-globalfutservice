import { useCallback, useEffect, useState } from 'react'
import { Button, Card } from '../../components/ui'
import { api } from '../../lib/api'
import type { ServerMemory as Memory } from '../../lib/types'

/** At or above this share of the container's limit, the figure is drawn as a warning. */
export const MEMORY_WARN_SHARE = 0.85

/** "460 of 512 MB (90%)", or just "460 MB" when there is no limit to compare with. */
export function ofLimit(used: number | null | undefined, max: number | null | undefined): string {
  if (used == null) return 'Unknown'
  if (max == null || max <= 0) return `${used} MB`
  return `${used} of ${max} MB (${Math.round((used / max) * 100)}%)`
}

/**
 * How much memory the server holds, for admins: what Render's limit counts (the container, or
 * the process's resident size when the container's own figure cannot be read), and the JVM's
 * parts. Render restarts the service when the container goes over its limit; this is how a
 * climb towards it is seen first. Read when the dashboard loads and every 30 seconds.
 */
export function ServerMemory() {
  const [memory, setMemory] = useState<Memory | null>(null)
  const [failed, setFailed] = useState(false)
  const [loading, setLoading] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      setMemory(await api.get<Memory>('/api/v1/admin/system/memory'))
      setFailed(false)
    } catch {
      setFailed(true)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
    const timer = setInterval(() => { void load() }, 30_000)
    return () => clearInterval(timer)
  }, [load])

  const used = memory?.containerUsedMb ?? memory?.processRssMb ?? null
  const limit = memory?.containerLimitMb ?? null
  const warn = used != null && limit != null && limit > 0 && used / limit >= MEMORY_WARN_SHARE

  return (
    <section aria-labelledby="server-memory-heading">
    <Card className="mt-5 p-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 id="server-memory-heading" className="display text-[15px] text-chalk">Server memory</h2>
        <Button size="sm" variant="secondary" loading={loading} onClick={() => void load()}>Refresh</Button>
      </div>
      {failed && !memory ? (
        <p className="mt-3 text-[13px] text-warn">Could not be read. Try Refresh.</p>
      ) : !memory ? (
        <p className="mt-3 text-[13px] text-chalk-muted">Reading…</p>
      ) : (
        <dl className="mt-3 grid gap-x-6 gap-y-2 text-[13px] sm:grid-cols-2" data-testid="server-memory">
          <Row label={memory.containerUsedMb != null ? 'Container (what Render counts)' : 'Process resident'}
               value={ofLimit(used, limit)} warn={warn} />
          <Row label="Heap" value={ofLimit(memory.heapUsedMb, memory.heapMaxMb)} />
          <Row label="Class metadata" value={ofLimit(memory.metaspaceUsedMb, memory.metaspaceMaxMb)} />
          <Row label="JIT code" value={ofLimit(memory.codeCacheUsedMb, memory.codeCacheMaxMb)} />
          <Row label="Direct buffers" value={ofLimit(memory.directUsedMb, memory.directMaxMb)} />
          <Row label="Threads" value={`${memory.threads} (peak ${memory.peakThreads})`} />
          <Row label="Garbage collector" value={memory.gc} />
          <Row label="Up for" value={uptime(memory.uptimeSeconds)} />
        </dl>
      )}
    </Card>
    </section>
  )
}

function Row({ label, value, warn = false }: { label: string; value: string; warn?: boolean }) {
  return (
    <div className="flex items-baseline justify-between gap-3 border-b border-ink-400 py-1.5">
      <dt className="text-chalk-muted">{label}</dt>
      <dd className={`tnum font-semibold ${warn ? 'text-warn' : 'text-chalk'}`}>{value}</dd>
    </div>
  )
}

function uptime(seconds: number): string {
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  return h > 0 ? `${h} h ${m} min` : `${m} min`
}
