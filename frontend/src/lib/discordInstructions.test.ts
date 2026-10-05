import { describe, expect, it } from 'vitest'
import indexHtml from '../../index.html?raw'
import { BUSINESS } from '../content/business'
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

describe('instead: message us on Discord, with the order reference', () => {
  it('names the account and opens a direct message, never the server', () => {
    expect(BUSINESS.discordName).toBe('globalfutservices')
    expect(BUSINESS.discordDm).toBe(`https://discord.com/users/${BUSINESS.discordUserId}`)
  })

  it('in all three languages', () => {
    expect(en.discordDm.button).toBe('Message us on Discord')
    expect(en.discordDm.messageUs).toBe('Message us on Discord:')
    expect(en.discordDm.includeReference).toBe('Include your order reference:')
    for (const dict of [es, fr]) {
      expect(dict.discordDm.button).not.toBe(en.discordDm.button)
      expect(dict.discordDm.includeReference).not.toBe(en.discordDm.includeReference)
      expect(dict.discordDm.copyLabel('GFS-26-ABC')).toContain('GFS-26-ABC')
    }
  })
})
