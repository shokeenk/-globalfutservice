import { PageHeader } from '../../components/PageHeader'
import { useCallback, useEffect, useState } from 'react'
import {
  Alert, Badge, Button, EmptyState, Field, Input, Section, Skeleton,
} from '../../components/ui'
import { ApiError, api } from '../../lib/api'
import { dateTime } from '../../lib/format'
import { useSeo } from '../../lib/seo'
import type { Coupon } from '../../lib/types'

/** The ceiling, mirrored from Coupon.MAX_DISCOUNT_BPS. The server refuses anything above. */
const MAX_PERCENT = 20

/**
 * Coupon administration.
 *
 * <p>The form validates the same things the server does — a code's shape, the 20% ceiling
 * — so the common mistakes are caught before a round trip. None of it is trusted: the
 * server re-checks everything, and the database refuses an over-ceiling row even if both
 * layers above it are wrong. This is the convenience, not the control.
 */
export default function AdminCoupons() {
  useSeo({ title: 'Coupons', noindex: true })

  const [coupons, setCoupons] = useState<Coupon[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      setCoupons(await api.get<Coupon[]>('/api/v1/admin/coupons'))
      setError(null)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not load coupons.')
      setCoupons([])
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  return (
    <>
      <PageHeader eyebrow="Operations" title="Coupon codes" intensity={0.3} />
      <Section className="rhythm-section">
      {error && <Alert tone="warn">{error}</Alert>}

      <CreateCoupon onCreated={load} />

      {!coupons && <Skeleton className="mt-6 h-56 w-full" />}

      {coupons?.length === 0 && !error && (
        <div className="mt-6">
          <EmptyState title="No coupons yet">
            Create one above and it works at checkout immediately.
          </EmptyState>
        </div>
      )}

      {coupons && coupons.length > 0 && (
        <div className="surface mt-6 overflow-x-auto">
          <table className="w-full min-w-[860px] text-left text-sm">
            {/* Sticky, for the same reason as the order queue: a coupon list grows
                and the column meanings must not scroll away from the numbers. */}
            <thead className="sticky top-0 z-10 border-b border-ink-400 bg-ink-500 backdrop-blur">
              <tr className="text-[11.5px] uppercase tracking-wider text-chalk-faint">
                <th scope="col" className="px-5 py-3.5 font-semibold">Code</th>
                <th scope="col" className="px-5 py-3.5 font-semibold">Off</th>
                <th scope="col" className="px-5 py-3.5 font-semibold">Used</th>
                <th scope="col" className="px-5 py-3.5 font-semibold">Per customer</th>
                <th scope="col" className="px-5 py-3.5 font-semibold">Expires</th>
                <th scope="col" className="px-5 py-3.5 font-semibold">State</th>
                <th scope="col" className="px-5 py-3.5 font-semibold" />
              </tr>
            </thead>
            <tbody className="divide-y divide-ink-400">
              {coupons.map((coupon) => (
                <CouponRow key={coupon.id} coupon={coupon} onChanged={load} />
              ))}
            </tbody>
          </table>
        </div>
      )}
      </Section>
    </>
  )
}

/* ------------------------------------------------------------------- create ------- */

function CreateCoupon({ onCreated }: { onCreated: () => void }) {
  const [code, setCode] = useState('')
  const [percent, setPercent] = useState(10)
  const [description, setDescription] = useState('')
  const [maxRedemptions, setMaxRedemptions] = useState('')
  const [maxPerAccount, setMaxPerAccount] = useState('1')
  const [expiresAt, setExpiresAt] = useState('')

  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [created, setCreated] = useState<string | null>(null)

  async function submit() {
    setError(null)
    setCreated(null)

    const trimmed = code.trim().toUpperCase()
    if (!/^[A-Z0-9_-]{3,32}$/.test(trimmed)) {
      setError('A code is 3 to 32 characters: letters, numbers, hyphens and underscores.')
      return
    }
    if (percent < 1 || percent > MAX_PERCENT) {
      setError(`A coupon must be between 1% and ${MAX_PERCENT}%.`)
      return
    }

    setBusy(true)
    try {
      await api.post<Coupon>('/api/v1/admin/coupons', {
        code: trimmed,
        discountPercent: percent,
        description: description.trim() || null,
        maxRedemptions: maxRedemptions.trim() ? Number(maxRedemptions) : null,
        maxPerAccount: maxPerAccount.trim() ? Number(maxPerAccount) : 1,
        minOrderMinor: null,
        // A date input gives a local calendar day; the code should stop working at the
        // end of it, not at midnight UTC part-way through somebody's evening.
        expiresAt: expiresAt ? new Date(`${expiresAt}T23:59:59`).toISOString() : null,
      })
      setCreated(trimmed)
      setCode('')
      setDescription('')
      onCreated()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not create that coupon.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="surface p-6 sm:p-7">
      <h2 className="display text-display-sm text-chalk">New coupon</h2>
      <p className="mt-2 text-body-sm text-chalk-muted">
        Codes are case-insensitive and take effect immediately. The maximum discount is{' '}
        {MAX_PERCENT}%.
      </p>

      {created && (
        <div className="mt-4">
          <Alert tone="ok">
            <strong>{created}</strong> is live and can be used at checkout now.
          </Alert>
        </div>
      )}
      {error && (
        <div className="mt-4">
          <Alert tone="warn">{error}</Alert>
        </div>
      )}

      <div className="mt-5 grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
        <Field label="Code" hint="What the customer types.">
          {(props) => (
            <Input
              {...props}
              value={code}
              onChange={(e) => setCode(e.target.value.toUpperCase())}
              placeholder="SAVE10"
              maxLength={32}
              autoComplete="off"
            />
          )}
        </Field>

        <Field label={`Discount — ${percent}%`} hint={`Between 1% and ${MAX_PERCENT}%.`}>
          {(props) => (
            <input
              {...props}
              type="range"
              min={1}
              max={MAX_PERCENT}
              step={1}
              value={percent}
              onChange={(e) => setPercent(Number(e.target.value))}
              className="mt-3 h-2 w-full cursor-pointer appearance-none rounded-full bg-ink-400
                         accent-brand-500"
            />
          )}
        </Field>

        <Field label="Description" hint="For your reference. Customers never see it.">
          {(props) => (
            <Input
              {...props}
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="Weekend league promo"
              maxLength={140}
            />
          )}
        </Field>

        <Field label="Total uses" hint="Leave blank for unlimited.">
          {(props) => (
            <Input
              {...props}
              value={maxRedemptions}
              onChange={(e) => setMaxRedemptions(e.target.value.replace(/\D/g, ''))}
              placeholder="100"
              inputMode="numeric"
            />
          )}
        </Field>

        <Field label="Uses per customer" hint="Usually 1. Requires a signed-in account.">
          {(props) => (
            <Input
              {...props}
              value={maxPerAccount}
              onChange={(e) => setMaxPerAccount(e.target.value.replace(/\D/g, ''))}
              placeholder="1"
              inputMode="numeric"
            />
          )}
        </Field>

        <Field label="Expires" hint="Leave blank to never expire.">
          {(props) => (
            <Input
              {...props}
              type="date"
              value={expiresAt}
              onChange={(e) => setExpiresAt(e.target.value)}
            />
          )}
        </Field>
      </div>

      <Button className="mt-6" size="md" disabled={busy} onClick={() => void submit()}>
        {busy ? 'Creating…' : 'Create coupon'}
      </Button>
    </div>
  )
}

/* ---------------------------------------------------------------------- row ------- */

function CouponRow({ coupon, onChanged }: { coupon: Coupon; onChanged: () => void }) {
  const [busy, setBusy] = useState(false)

  async function toggle() {
    setBusy(true)
    try {
      await api.post(`/api/v1/admin/coupons/${coupon.id}`, { active: !coupon.active })
      onChanged()
    } finally {
      setBusy(false)
    }
  }

  const expired = coupon.expiresAt !== null && new Date(coupon.expiresAt) < new Date()

  return (
    <tr className="transition-colors hover:bg-ink-500/40">
      <td className="px-5 py-3.5">
        <span className="tnum font-semibold text-chalk">{coupon.code}</span>
        {coupon.description && (
          <span className="mt-0.5 block text-[12px] text-chalk-faint">{coupon.description}</span>
        )}
      </td>
      <td className="tnum px-5 py-3.5 font-semibold text-brand-400">{coupon.discountPercent}%</td>
      <td className="tnum px-5 py-3.5 text-chalk-muted">
        {coupon.redeemedCount}
        {coupon.maxRedemptions !== null && ` / ${coupon.maxRedemptions}`}
      </td>
      <td className="tnum px-5 py-3.5 text-chalk-muted">{coupon.maxPerAccount}</td>
      <td className="px-5 py-3.5 text-[12.5px] text-chalk-muted">
        {coupon.expiresAt ? dateTime(coupon.expiresAt) : '—'}
      </td>
      <td className="px-5 py-3.5">
        {/* Three states, not two. "Off" is a decision somebody made; "spent" and
            "expired" are things that happened, and an operator wondering why a code
            stopped working needs to know which. */}
        {!coupon.active ? (
          <Badge tone="neutral">Off</Badge>
        ) : coupon.exhausted ? (
          <Badge tone="warn">Fully claimed</Badge>
        ) : expired ? (
          <Badge tone="warn">Expired</Badge>
        ) : (
          <Badge tone="ok">Live</Badge>
        )}
      </td>
      <td className="px-5 py-3.5 text-right">
        <Button variant="ghost" size="sm" disabled={busy} onClick={() => void toggle()}>
          {coupon.active ? 'Switch off' : 'Switch on'}
        </Button>
      </td>
    </tr>
  )
}
