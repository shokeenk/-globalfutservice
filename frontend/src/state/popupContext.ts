import { useEffect, useSyncExternalStore } from 'react'
import type { PopupPage } from '../lib/popups'

/**
 * What the page in view tells the popup host: which page key it is, when the route alone
 * does not say (the coin order past its first step is COINS_CHECKOUT), and when no popup
 * may show at all -- the payment step, the gateway, the UPI proof upload.
 *
 * <p>A tiny store rather than a provider, so any component can say "not here" without
 * being threaded through the layout. Blocks are counted, so two components on one page
 * can each ask and either leaving does not lift the other's.
 */
type Snapshot = { page: PopupPage | null; blocked: boolean }

let page: PopupPage | null = null
let blocks = 0
let snapshot: Snapshot = { page: null, blocked: false }
const listeners = new Set<() => void>()

function emit() {
  snapshot = { page, blocked: blocks > 0 }
  listeners.forEach((l) => l())
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export function usePopupContext(): Snapshot {
  return useSyncExternalStore(subscribe, () => snapshot, () => snapshot)
}

/** This page is {@code key} for popups while mounted (null: let the route decide). */
export function usePopupPage(key: PopupPage | null): void {
  useEffect(() => {
    page = key
    emit()
    return () => {
      if (page === key) {
        page = null
        emit()
      }
    }
  }, [key])
}

/** No popup while {@code active}: the payment step, or anything like it. */
export function useNoPopups(active = true): void {
  useEffect(() => {
    if (!active) return
    blocks++
    emit()
    return () => {
      blocks--
      emit()
    }
  }, [active])
}

/** For tests. */
export function resetPopupContextForTests(): void {
  page = null
  blocks = 0
  emit()
}
