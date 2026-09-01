import type { PaymentIntent } from './types'

/**
 * Razorpay Checkout, loaded on demand.
 *
 * The script is fetched when a customer actually reaches payment rather than on
 * every page load. A third-party script in the document head runs on every visit,
 * costs every visitor the download, and widens the attack surface of pages that
 * have nothing to do with money.
 */

declare global {
  interface Window {
    Razorpay?: new (options: Record<string, unknown>) => { open: () => void }
  }
}

const SCRIPT_URL = 'https://checkout.razorpay.com/v1/checkout.js'
let scriptPromise: Promise<void> | null = null

export function isStubGateway(intent: PaymentIntent): boolean {
  return intent.publicKey.includes('stub') || intent.providerOrderId.startsWith('order_stub_')
}

function loadScript(): Promise<void> {
  if (window.Razorpay) return Promise.resolve()
  if (scriptPromise) return scriptPromise

  scriptPromise = new Promise<void>((resolve, reject) => {
    const script = document.createElement('script')
    script.src = SCRIPT_URL
    script.async = true
    // Not the same origin as this app, so it gets no ambient credentials.
    script.crossOrigin = 'anonymous'
    script.onload = () => resolve()
    script.onerror = () => {
      scriptPromise = null
      reject(new Error('Could not load the payment window.'))
    }
    document.head.appendChild(script)
  })
  return scriptPromise
}

export type CheckoutCallbacks = {
  onDismiss: () => void
  /**
   * Fired when the browser is told the payment succeeded.
   *
   * This is a UI signal only. The order is not marked paid from here — that
   * happens when the gateway's signed webhook reaches the server. A browser can be
   * closed mid-payment, replayed, or hand-crafted, so treating this as proof would
   * eventually mean delivering against a payment that never arrived.
   */
  onSuccess: () => void
}

export async function openCheckout(
  intent: PaymentIntent,
  orderRef: string,
  callbacks: CheckoutCallbacks,
): Promise<void> {
  await loadScript()
  if (!window.Razorpay) throw new Error('Could not load the payment window.')

  const checkout = new window.Razorpay({
    key: intent.publicKey,
    amount: intent.amountMinor,
    currency: intent.currency,
    name: 'Global FUT Services',
    description: intent.description,
    order_id: intent.providerOrderId,
    prefill: { email: intent.customerEmail },
    notes: { order_ref: orderRef },
    theme: { color: '#C1281B' },
    modal: { ondismiss: callbacks.onDismiss },
    handler: () => callbacks.onSuccess(),
  })

  checkout.open()
}
