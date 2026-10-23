import { useEffect, useSyncExternalStore } from 'react'
import { api } from './api'

/**
 * tawk.to's ids -- the property, the Embed widget (order support pages) and the Floating
 * widget (the chat button) -- read from the API when the site starts.
 *
 * <p><b>From the API, not the build.</b> They were once `VITE_*` variables baked into the
 * bundle, but the storefront's Docker build only receives the variables its Dockerfile
 * declares, so on the live site they came out empty and the chat never appeared. The API
 * serves them from its own environment (`GFS_TAWK_*`), the way it serves the Turnstile
 * site key, so a missing build argument can no longer switch the chat off.
 *
 * <p>The `VITE_TAWK_*` variables are kept only as a fallback, field by field, for local
 * development without them set on the API. All three ids are public.
 */
export interface ChatIds {
  propertyId: string | null
  embedWidgetId: string | null
  floatingWidgetId: string | null
}

type Env = Record<string, string | undefined>

function blankToNull(value: string | null | undefined): string | null {
  const v = value?.trim()
  return v ? v : null
}

/** The build's values: empty in a production build, set for `npm run dev`. */
export function buildTimeIds(env: Env = import.meta.env): ChatIds {
  return {
    propertyId: blankToNull(env.VITE_TAWK_PROPERTY_ID),
    embedWidgetId: blankToNull(env.VITE_TAWK_EMBED_WIDGET_ID),
    floatingWidgetId: blankToNull(env.VITE_TAWK_FLOATING_WIDGET_ID),
  }
}

/** The API's ids where it has them, the build's where it does not. */
export function mergeIds(server: Partial<ChatIds> | null | undefined, fallback: ChatIds): ChatIds {
  return {
    propertyId: blankToNull(server?.propertyId) ?? fallback.propertyId,
    embedWidgetId: blankToNull(server?.embedWidgetId) ?? fallback.embedWidgetId,
    floatingWidgetId: blankToNull(server?.floatingWidgetId) ?? fallback.floatingWidgetId,
  }
}

let ids: ChatIds | null = null
let loading: Promise<ChatIds> | null = null
const listeners = new Set<() => void>()

/** Asks the API once per page load; a failed request falls back to the build's ids. */
export function loadChatIds(): Promise<ChatIds> {
  if (!loading) {
    // Never allowed to break a page: anything but an answer is the build's ids.
    loading = Promise.resolve()
      .then(() => api.get<Partial<ChatIds>>('/api/v1/chat/config'))
      .then((server) => mergeIds(server, buildTimeIds()))
      .catch(() => buildTimeIds())
      .then((loaded) => {
        ids = loaded
        listeners.forEach((l) => l())
        return loaded
      })
  }
  return loading
}

/** The ids once known; null while they load. Asks for them on first use. */
export function useChatIds(): ChatIds | null {
  const current = useSyncExternalStore(
    (listener) => {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
    () => ids,
    () => null,
  )
  useEffect(() => {
    void loadChatIds()
  }, [])
  return current
}

/** For tests: forget the ids, as a fresh page load would. */
export function resetChatIdsForTests(): void {
  ids = null
  loading = null
}
