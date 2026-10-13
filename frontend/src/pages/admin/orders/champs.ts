/** The admin's view of a Champions result, as GET /api/v1/admin/orders/{ref}/result returns it. */
export interface ResultOption {
  level: number
  achievedPriceMinor: number
  achievedPriceFormatted: string
  differenceFormatted: string
  settlementMinor: number
  settlementFormatted: string
  refundAllowed: boolean
}

export interface ResultProof {
  contentType: string
  sizeBytes: number
  uploadedAt: string
}

export interface RecordedResult {
  achievedLevel: number
  belowLowest: boolean
  achievedPriceMinor: number | null
  achievedPriceFormatted: string | null
  settlementMinor: number
  settlementFormatted: string
  method: 'REFUND' | 'STORE_CREDIT' | null
  methodSetBy: 'CUSTOMER' | 'ADMIN' | 'AUTOMATIC' | null
  methodSetAt: string | null
  status: 'NONE' | 'PENDING' | 'PAID' | 'ISSUED' | 'CANCELLED'
  chooseBy: string | null
  recordedAt: string
  settledAt: string | null
  settledBy: string | null
  refundMethod: string | null
  refundReference: string | null
  pointsRemoved: number
  refundAllowed: boolean
  locked: boolean
  proof: ResultProof | null
}

export interface ResultView {
  applies: boolean
  why: string | null
  kind: string | null
  currency: string
  targetLevel: number | null
  lowestLevel: number | null
  options: ResultOption[]
  orderedPriceMinor: number
  orderedPriceFormatted: string | null
  servicePaidMinor: number
  servicePaidFormatted: string
  storeCreditUsedMinor: number
  result: RecordedResult | null
}

/**
 * Wins Achieved as an admin is entering it, shared by the Actions dropdown and the
 * Champions card so it is entered once. `level` is a number of wins on the ladder, or
 * BELOW for fewer than the lowest tier -- then `actualWins` and `amount` (major units)
 * are typed by hand.
 */
export interface WinsDraft {
  level: number | 'BELOW' | null
  actualWins: string
  amount: string
  note: string
}

export const EMPTY_DRAFT: WinsDraft = { level: null, actualWins: '', amount: '', note: '' }

/** The draft as the API takes it, or why it cannot be sent yet. */
export function resultInput(draft: WinsDraft, view: ResultView):
  { ok: true; input: { achievedLevel: number; belowLowest: boolean; manualAmountMinor: number | null; note: string | null } }
  | { ok: false; error: string } {
  const note = draft.note.trim() || null
  if (draft.level === null) {
    return { ok: false, error: 'Choose Wins Achieved before marking this order delivered.' }
  }
  if (draft.level !== 'BELOW') {
    return { ok: true, input: { achievedLevel: draft.level, belowLowest: false, manualAmountMinor: null, note } }
  }
  const wins = Number(draft.actualWins)
  if (draft.actualWins.trim() === '' || !Number.isInteger(wins) || wins < 0
    || (view.lowestLevel != null && wins >= view.lowestLevel)) {
    return { ok: false, error: `Enter the actual wins: fewer than ${view.lowestLevel ?? 'the lowest tier'}.` }
  }
  const amount = Number(draft.amount)
  if (draft.amount.trim() === '' || !Number.isFinite(amount) || amount < 0) {
    return { ok: false, error: 'Enter the settlement amount.' }
  }
  const minor = Math.round(amount * 100)
  if (minor > view.servicePaidMinor) {
    return { ok: false, error: `The settlement can be at most ${view.servicePaidFormatted}, what was paid for the service.` }
  }
  if (!note) {
    return { ok: false, error: 'Below the lowest tier, say why in the admin note.' }
  }
  return { ok: true, input: { achievedLevel: wins, belowLowest: true, manualAmountMinor: minor, note } }
}

export function winsLabel(n: number): string {
  return n === 1 ? '1 Win' : `${n} Wins`
}
