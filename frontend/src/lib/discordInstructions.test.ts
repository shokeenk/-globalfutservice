import { describe, expect, it } from 'vitest'
import indexHtml from '../../index.html?raw'
import en from '../i18n/en'
import es from '../i18n/es'
import fr from '../i18n/fr'

/*
 * Customers are never told to run /verify. Discord refuses to register the command, so the
 * instruction led nowhere; they message us directly instead, with their order reference.
 *
 * Checked over everything a customer can be shown: every page, component, translation and
 * the HTML shell. The admin console is left out (its API paths end in /verify for an
 * operator's payment check), and so are comments, which nobody sees.
 */
const SOURCES: Record<string, string> = import.meta.glob<string>(
  ['/src/**/*.{ts,tsx}', '!/src/**/*.test.{ts,tsx}', '!/src/pages/admin/**'],
  { query: '?raw', import: 'default', eager: true },
)

/** Source without its comments: block and JSX comments, line comments, HTML comments. */
function withoutComments(text: string): string {
  return text
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/(^|[^:\\])\/\/.*$/gm, '$1')
}

/** The command, and the ways each language told people to run it. */
const VERIFY_INSTRUCTION = new RegExp([
  '\\/verify\\b',
  'run this command', 'copy command',
  'ejecuta allí este comando', 'copiar comando',
  'lance cette commande', 'copier la commande',
].join('|'), 'i')

describe('the /verify command is gone from everything a customer reads', () => {
  it('no page, component, translation or the HTML shell mentions it', () => {
    const files: Record<string, string> = { ...SOURCES, 'index.html': indexHtml }
    expect(Object.keys(files).length).toBeGreaterThan(50)
    const hits = Object.entries(files).flatMap(([path, text]) =>
      withoutComments(text).split('\n').flatMap((line, i) =>
        VERIFY_INSTRUCTION.test(line) ? [`${path}:${i + 1}: ${line.trim()}`] : []))
    expect(hits).toEqual([])
  })

  it('the check itself catches the old wording', () => {
    expect(VERIFY_INSTRUCTION.test("command: '/verify GFS-26-ABC'")).toBe(true)
    expect(VERIFY_INSTRUCTION.test('Join our Discord server, then run this command there.')).toBe(true)
    expect(VERIFY_INSTRUCTION.test('Entra en nuestro servidor de Discord y ejecuta allí este comando.')).toBe(true)
    expect(VERIFY_INSTRUCTION.test('Rejoins notre serveur Discord, puis lance cette commande là-bas.')).toBe(true)
    // Ordinary words about checking a payment are not the command.
    expect(VERIFY_INSTRUCTION.test('We verify each payment by hand.')).toBe(false)
    expect(VERIFY_INSTRUCTION.test('Your payment is being verified.')).toBe(false)
  })
})

/*
 * Support is the live chat only (2026-10-20). No checkout or order page sends a customer to
 * Discord -- not a server invite, not a direct message -- and none of the words they show
 * names it. "Sign in with Discord" and the community link in the header and footer stay;
 * they are not on these pages.
 */
const CHECKOUT_AND_ORDER_PAGES = [
  'pages/Order.tsx', 'pages/BoostingCheckout.tsx', 'pages/Track.tsx', 'pages/GuestOrderChat.tsx',
  'pages/OrderSupport.tsx', 'pages/CoachingBook.tsx', 'components/CredentialForm.tsx',
  'components/CompletePayment.tsx', 'components/ManualPayment.tsx', 'components/support/SupportCard.tsx',
  'components/support/SupportChat.tsx', 'components/listing/ListingDetails.tsx',
]

/** A link or a way to one that takes a customer to Discord. */
const DISCORD_LINK = /discord\.gg|discord\.com\/(users|invite)|discordDm|discordInvite|BUSINESS\.discord/

describe('no checkout or order page sends a customer to Discord', () => {
  it('none of them links to a Discord server or a Discord direct message', () => {
    for (const path of CHECKOUT_AND_ORDER_PAGES) {
      const text = SOURCES[`/src/${path}`]
      expect(text, path).toBeDefined()
      expect(withoutComments(text!), path).not.toMatch(DISCORD_LINK)
    }
  })

  it('the words those pages show never name Discord, in any language', () => {
    for (const dict of [en, es, fr]) {
      for (const section of ['order', 'boostingCheckout', 'track', 'orderSupport'] as const) {
        expect(JSON.stringify((dict as Record<string, unknown>)[section]), section).not.toMatch(/discord/i)
      }
      // The guest's line to the chat is a function: called, it still names no Discord.
      expect(dict.orderSupport.guestBody('GFS-26-ABC')).not.toMatch(/discord/i)
      expect(dict.orderSupport.guestChatLead('GFS-26-ABC')).toContain('GFS-26-ABC')
    }
  })

  it('the check itself catches each kind of Discord link', () => {
    expect(DISCORD_LINK.test('href="https://discord.gg/abc"')).toBe(true)
    expect(DISCORD_LINK.test('href="https://discord.com/users/1300551868174569595"')).toBe(true)
    expect(DISCORD_LINK.test('href="https://discord.com/invite/8FeP7C6tXt"')).toBe(true)
    expect(DISCORD_LINK.test('href={BUSINESS.discordDm}')).toBe(true)
    expect(DISCORD_LINK.test('Chat with us')).toBe(false)
  })
})
