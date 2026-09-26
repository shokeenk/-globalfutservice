import { useEffect, useRef, useState } from 'react'
import { LuMonitor, LuSmartphone } from 'react-icons/lu'

/**
 * The desktop frame is wider than the email, on purpose.
 *
 * <p>The email is a 600px table inside 24px margins, and its phone layout switches on at
 * max-width 600px — which matches a frame of exactly 600, so a 600px "desktop" preview
 * showed the phone layout. A desktop mail client always has room around the email; this
 * gives the frame that room.
 */
const DESKTOP_WIDTH = 648
/** The email's own width. Desktop view scales this to the panel, so the margins fall outside it. */
const EMAIL_WIDTH = 600
const PHONE_WIDTH = 375
/** The reference's preview panel height. Longer emails scroll inside it. */
const PANEL_MAX_HEIGHT = 400

/**
 * The rendered email, as the server's own template produced it.
 *
 * <p>Not a mock-up in React. The HTML comes from the same renderer a real send uses, so
 * what is approved here is byte for byte what goes out — the reason this is an iframe
 * rather than components that imitate the email.
 *
 * <p>The iframe is sandboxed with no scripts. Links open in a new tab rather than inside
 * the preview, so clicking "ORDER NOW" to check where it goes does not replace the
 * preview with the storefront.
 *
 * <p>Desktop view draws the email in a desktop-width frame and scales it down to the
 * column; Mobile view draws it at 375px, where its phone-width layout takes over. The
 * panel is the reference's height, and a longer email scrolls inside it rather than
 * pushing the banner, options and Next Step below the fold.
 */
export function EmailPreview({
  html, error, loading,
}: {
  html: string | null
  error: string | null
  loading: boolean
}) {
  // On a phone, a desktop-width email scaled to the screen is too small to read, so the
  // preview opens on the phone view there. Either is one press away.
  const [mode, setMode] = useState<'desktop' | 'mobile'>(() =>
    typeof window !== 'undefined' && window.innerWidth < 640 ? 'mobile' : 'desktop')
  const box = useRef<HTMLDivElement>(null)
  const frame = useRef<HTMLIFrameElement>(null)
  const [boxWidth, setBoxWidth] = useState(DESKTOP_WIDTH)
  const [contentHeight, setContentHeight] = useState(640)

  useEffect(() => {
    const el = box.current
    if (!el || typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(([entry]) => {
      if (entry) setBoxWidth(entry.contentRect.width)
    })
    observer.observe(el)
    return () => observer.disconnect()
  }, [])

  const width = mode === 'desktop' ? DESKTOP_WIDTH : PHONE_WIDTH
  // Desktop scales the email, not the frame: the frame's spare width sits outside the
  // panel, so the email fills it edge to edge as in the reference.
  const scale = Math.min(1, boxWidth / (mode === 'desktop' ? EMAIL_WIDTH : PHONE_WIDTH))

  const measure = () => {
    const doc = frame.current?.contentDocument
    if (doc?.body) setContentHeight(doc.body.scrollHeight)
  }
  // The layout reflows between the two widths, so the height has to be read again.
  useEffect(() => { measure() }, [mode])

  const withBase = html
    ? html.replace('<head>', '<head><base target="_blank"/>')
    : null

  return (
    <section
      aria-labelledby="email-preview-title"
      className="rounded-admin-card border border-admin-line bg-white p-4 shadow-admin-card"
    >
      <div className="mb-3 flex flex-wrap items-start justify-between gap-3 sm:flex-nowrap">
        <div className="min-w-0">
          <h2 id="email-preview-title" className="text-[17px] font-bold text-admin-ink">Email Preview</h2>
          <p className="mt-0.5 text-[12px] text-admin-muted">
            This is how your email will look for your customers.
          </p>
        </div>
        <div role="group" aria-label="Preview width" className="flex shrink-0 gap-1.5">
          <ViewButton active={mode === 'desktop'} onClick={() => setMode('desktop')} Icon={LuMonitor}>
            Desktop View
          </ViewButton>
          <ViewButton active={mode === 'mobile'} onClick={() => setMode('mobile')} Icon={LuSmartphone}>
            Mobile View
          </ViewButton>
        </div>
      </div>

      {/* Focusable so the panel can be scrolled from the keyboard as well as the wheel. */}
      <div
        tabIndex={0}
        role="region"
        aria-label="Email preview, scrollable"
        className="overflow-y-auto rounded-admin-control border border-admin-line bg-admin-page
                   focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-admin-red"
        style={{ maxHeight: PANEL_MAX_HEIGHT }}
      >
      <div
        ref={box}
        className="relative overflow-hidden"
        style={{ height: Math.ceil(contentHeight * scale) }}
      >
        {withBase ? (
          <iframe
            ref={frame}
            title="Email preview"
            srcDoc={withBase}
            sandbox="allow-same-origin allow-popups allow-popups-to-escape-sandbox"
            onLoad={measure}
            className="absolute left-1/2 top-0 border-0 bg-white"
            style={{
              width,
              height: contentHeight,
              transform: `translateX(-50%) scale(${scale})`,
              transformOrigin: 'top center',
            }}
          />
        ) : (
          <div className="grid h-full place-items-center p-6 text-center text-[13px] text-admin-muted">
            {error ?? 'Rendering the preview…'}
          </div>
        )}
        {loading && withBase && (
          <span className="absolute right-2 top-2 rounded-full bg-white/90 px-2 py-0.5 text-[11px]
                           text-admin-muted shadow-sm">
            Updating…
          </span>
        )}
      </div>
      </div>
      {error && withBase && (
        <p role="status" className="mt-2 text-[12px] text-admin-red-text">{error}</p>
      )}
    </section>
  )
}

function ViewButton({
  active, onClick, Icon, children,
}: {
  active: boolean
  onClick: () => void
  Icon: typeof LuMonitor
  children: string
}) {
  return (
    <button
      type="button"
      aria-pressed={active}
      onClick={onClick}
      className={`inline-flex h-[31px] items-center gap-1.5 rounded-admin-control px-3 text-[11.5px]
                  font-medium transition-colors focus-visible:outline-none focus-visible:ring-2
                  focus-visible:ring-admin-red focus-visible:ring-offset-2
                  ${active
                    ? 'bg-admin-red text-white'
                    : 'border border-admin-line bg-[#F7F8FA] text-admin-ink hover:bg-admin-page'}`}
    >
      <Icon aria-hidden="true" className="h-4 w-4" />
      {children}
    </button>
  )
}
