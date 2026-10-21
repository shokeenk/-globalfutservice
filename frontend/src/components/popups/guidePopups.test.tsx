import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { useState } from 'react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { PublicPopup } from '../../lib/popups'

/*
 * The guide's popups on the storefront: the live tier ladder and point value, "after N
 * seconds or N% scroll", never during checkout, and the coin page's step surviving its
 * switch to the checkout page key.
 */

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), baseUrl: '' }))
vi.mock('../../lib/api', async (original) => ({ ...(await original<typeof import('../../lib/api')>()), api }))

const catalog = vi.hoisted(() => ({ tierDiscountEnabled: true, pointValueMinor: 100 }))
vi.mock('../../state/CatalogContext', () => ({
  useOptionalCatalog: () => ({
    policy: {
      loyaltyCurrency: 'INR', pointValueMinor: catalog.pointValueMinor, tierDiscountEnabled: catalog.tierDiscountEnabled,
      loyaltyTiers: [
        { name: 'BRONZE', displayName: 'Bronze', thresholdPoints: 0, discountBps: 0 },
        { name: 'SILVER', displayName: 'Silver', thresholdPoints: 500, discountBps: 100 },
        { name: 'GOLD', displayName: 'Gold', thresholdPoints: 2000, discountBps: 200 },
        { name: 'ELITE', displayName: 'Elite', thresholdPoints: 5000, discountBps: 300 },
      ],
    },
  }),
}))

const { PopupHost, resetPopupHostForTests } = await import('./PopupHost')
const { firePopupStep, resetStepsForTests } = await import('../../lib/popups')
const { usePopupPage, resetPopupContextForTests } = await import('../../state/popupContext')

const MEMBERSHIP: PublicPopup = {
  id: 'pop_guide_membership', version: 1, type: 'CENTER_MODAL', preset: 'MEMBERSHIP', desktopImage: null, mobileImage: null,
  copy: { en: { headline: 'UNLOCK MORE BENEFITS',
    body: 'The more you spend, the higher your tier. Get bigger discounts and exclusive perks with GFS Membership.',
    trust: 'Eligible orders earn points. Points are worth {pointValue} each at checkout.' } },
  buttons: [], targetPages: ['REWARDS'], targetPaths: [], triggerType: 'PAGE_LOAD', frequency: 'EVERY_VISIT', priority: 60,
}

function card(over: Partial<PublicPopup> & { id: string }): PublicPopup {
  return { version: 1, type: 'BOTTOM_RIGHT', desktopImage: null, mobileImage: null, copy: { en: { headline: over.id } },
    buttons: [], targetPages: ['HOME'], targetPaths: [], triggerType: 'PAGE_LOAD', frequency: 'EVERY_VISIT', priority: 0,
    ...over }
}

function serve(list: PublicPopup[]) {
  api.get.mockImplementation(async () => list)
}

function at(path: string, page: React.ReactNode = null) {
  window.history.pushState({}, '', path)
  return render(<MemoryRouter initialEntries={[path]}><Routes><Route path="*" element={<>{page}<PopupHost /></>} /></Routes></MemoryRouter>)
}

beforeEach(() => {
  resetPopupHostForTests()
  resetStepsForTests()
  resetPopupContextForTests()
  window.sessionStorage.clear()
  window.localStorage.clear()
  catalog.tierDiscountEnabled = true
  catalog.pointValueMinor = 100
  api.post.mockResolvedValue(undefined)
})

afterEach(() => {
  vi.useRealTimers()
  vi.clearAllMocks()
})

describe('the membership popup', () => {
  it('lists the tiers and their discounts from the live settings, and the live point value', async () => {
    serve([MEMBERSHIP])
    at('/rewards')
    const tiers = await screen.findByTestId('popup-tiers')
    expect(within(tiers).getAllByRole('listitem').map((li) => li.textContent))
      .toEqual(['Bronze', 'Silver1%', 'Gold2%', 'Elite3%'])
    expect(screen.getByText('Eligible orders earn points. Points are worth ₹1 each at checkout.')).toBeInTheDocument()
  })

  it('with tier discounts switched off: the tiers, and no percentages', async () => {
    catalog.tierDiscountEnabled = false
    serve([MEMBERSHIP])
    at('/rewards')
    const tiers = await screen.findByTestId('popup-tiers')
    expect(tiers.textContent).not.toMatch(/%/)
    expect(within(tiers).getAllByRole('listitem')).toHaveLength(4)
  })

  it('follows the point value when it changes', async () => {
    catalog.pointValueMinor = 250
    serve([MEMBERSHIP])
    at('/rewards')
    expect(await screen.findByText(/Points are worth ₹2.50 each/)).toBeInTheDocument()
  })

  it('no other popup shows a tier list', async () => {
    serve([{ ...MEMBERSHIP, preset: 'REWARDS_REMINDER', id: 'pop_other' }])
    at('/rewards')
    await screen.findByTestId('popup-modal')
    expect(screen.queryByTestId('popup-tiers')).toBeNull()
  })
})

describe('after N seconds or N% scroll', () => {
  it('shows at the delay when nobody scrolls', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    serve([card({ id: 'pop_ds', triggerType: 'DELAY_OR_SCROLL', triggerValue: 8, triggerScroll: 40 })])
    at('/')
    await waitFor(() => expect(api.get).toHaveBeenCalled())
    await act(async () => { vi.advanceTimersByTime(7000) })
    expect(screen.queryByTestId('popup-card')).toBeNull()
    await act(async () => { vi.advanceTimersByTime(1500) })
    expect(screen.getByTestId('popup-card')).toBeInTheDocument()
  })

  it('or sooner, at the scroll', async () => {
    serve([card({ id: 'pop_ds2', triggerType: 'DELAY_OR_SCROLL', triggerValue: 600, triggerScroll: 40 })])
    Object.defineProperty(document.documentElement, 'scrollHeight', { configurable: true, value: 2000 })
    at('/')
    await waitFor(() => expect(api.get).toHaveBeenCalled())
    act(() => { window.scrollY = 600; window.dispatchEvent(new Event('scroll')) })
    expect(await screen.findByTestId('popup-card')).toBeInTheDocument()
    window.scrollY = 0
  })
})

describe('never during checkout', () => {
  const reminder = card({ id: 'pop_guide_rewards', preset: 'REWARDS_REMINDER', excludeCheckout: true,
    targetPages: ['HOME', 'CHAMPS_CHECKOUT', 'COINS_CHECKOUT'], targetPaths: ['/coaching/book'] })

  it('is shown on a page it targets', async () => {
    serve([reminder])
    at('/')
    expect(await screen.findByTestId('popup-card')).toBeInTheDocument()
  })

  it.each(['/boosting/checkout', '/coaching/book'])('is not shown on %s, even when targeted', async (path) => {
    serve([reminder])
    at(path)
    await waitFor(() => expect(api.get).toHaveBeenCalled())
    await new Promise((r) => setTimeout(r, 20))
    expect(screen.queryByTestId('popup-card')).toBeNull()
  })

  it('is not shown on the coin checkout step', async () => {
    function CoinCheckout() {
      usePopupPage('COINS_CHECKOUT')
      return null
    }
    serve([reminder])
    at('/order', <CoinCheckout />)
    await waitFor(() => expect(api.get).toHaveBeenCalled())
    await new Promise((r) => setTimeout(r, 20))
    expect(screen.queryByTestId('popup-card')).toBeNull()
  })
})

describe('the coin page step', () => {
  /** Like the coin order: Continue moves to the details step, which is the coin checkout page key. */
  function CoinPage() {
    const [step, setStep] = useState<'configure' | 'details'>('configure')
    usePopupPage(step === 'configure' ? null : 'COINS_CHECKOUT')
    return <button type="button" onClick={() => { setStep('details'); firePopupStep('coins:continue') }}>Continue</button>
  }

  it('coins:continue still reaches the coin checkout popup, though the page key changes in the same moment', async () => {
    api.get.mockImplementation(async (path: string) => (path.includes('COINS_CHECKOUT')
      ? [card({ id: 'pop_guide_coins_checkout', preset: 'COINS_CHECKOUT', targetPages: ['COINS_CHECKOUT'],
        triggerType: 'STEP', triggerEvent: 'coins:continue' })]
      : []))
    at('/order', <CoinPage />)
    await waitFor(() => expect(api.get).toHaveBeenCalled())
    expect(screen.queryByTestId('popup-card')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }))
    expect(await screen.findByTestId('popup-card')).toHaveTextContent('pop_guide_coins_checkout')
  })

  it('the pages fire the steps where the guide says', async () => {
    const order = (await import('../../pages/Order.tsx?raw')).default
    const boosting = (await import('../../pages/BoostingCheckout.tsx?raw')).default
    expect(order).toMatch(/setStep\('details'\); firePopupStep\('coins:continue'\)/)
    expect(boosting).toMatch(/if \(sku === 'BOOST_CHAMPS'\) firePopupStep\('champs:checkout'\)/)
  })
})
