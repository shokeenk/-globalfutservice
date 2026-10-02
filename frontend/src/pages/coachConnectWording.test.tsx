import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import en from '../i18n/en'
import es from '../i18n/es'
import fr from '../i18n/fr'

/*
 * Connect with Coach is named the same way wherever a coaching customer meets it: the
 * Coaching page quotes the button by its exact label, and the terms say the same thing
 * the email and the page do.
 */

vi.mock('../state/CatalogContext', () => ({ useCatalog: () => ({ catalog: null, policy: null }) }))

// jsdom has no matchMedia; the page header's animations ask it about reduced motion.
window.matchMedia = ((query: string) => ({
  matches: query.includes('reduce'), media: query, onchange: null,
  addEventListener: () => {}, removeEventListener: () => {}, addListener: () => {}, removeListener: () => {},
  dispatchEvent: () => false,
})) as unknown as typeof window.matchMedia

const { default: Legal } = await import('./Legal')

describe('the Connect with Coach button, by name', () => {
  it.each([
    ['en', en, (label: string) => label],
    ['es', es, (label: string) => `«${label}»`],
    ['fr', fr, (label: string) => `« ${label} »`],
  ] as const)('%s: the Coaching page quotes the button exactly as the card shows it', (_, dict, quoted) => {
    expect(dict.coaching.coachConnect).toContain(quoted(dict.orderSupport.ctaCoaching))
  })

  it('Spanish says "entrenador", as the Coaching page does, on the card, its button and the support page', () => {
    expect(es.orderSupport.cardTitleCoach).toBe('Conecta con tu entrenador')
    expect(es.orderSupport.ctaCoaching).toBe('Conectar con el entrenador')
    expect(es.orderSupport.summaryCoach).toBe('Entrenador')
    expect(es.orderSupport.cardBodyCoaching).toContain('Conecta con tu entrenador')
    expect(JSON.stringify(es.orderSupport)).not.toMatch(/\bcoach\b/i)
  })
})

describe('the terms, clause 6', () => {
  it('sends a coaching customer to Connect with Coach in their account, not to Discord', () => {
    render(<MemoryRouter><Legal doc="terms" /></MemoryRouter>)

    expect(screen.getByText('Last updated 2 October 2026')).toBeInTheDocument()
    const clause = document.getElementById('clause-6')
    expect(clause).toHaveTextContent(
      'After ordering, customers connect with their coach from their GFS account: each coaching order has a '
      + 'Connect with Coach page on this website for scheduling and session-related communication.')
    expect(clause?.textContent).not.toMatch(/discord/i)
  })
})
