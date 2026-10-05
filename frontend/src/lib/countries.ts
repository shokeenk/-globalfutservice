/**
 * Every ISO 3166-1 alpha-2 country code, for the country picker at checkout.
 *
 * Codes only: the names come from the browser in the customer's own language
 * (`Intl.DisplayNames`), so "DE" reads Germany, Alemania or Allemagne without a table
 * of names here to keep in step.
 */
export const COUNTRY_CODES: readonly string[] = (
  'AD AE AF AG AI AL AM AO AQ AR AS AT AU AW AX AZ BA BB BD BE BF BG BH BI BJ BL BM BN BO BQ BR BS '
  + 'BT BV BW BY BZ CA CC CD CF CG CH CI CK CL CM CN CO CR CU CV CW CX CY CZ DE DJ DK DM DO DZ EC EE '
  + 'EG EH ER ES ET FI FJ FK FM FO FR GA GB GD GE GF GG GH GI GL GM GN GP GQ GR GS GT GU GW GY HK HM '
  + 'HN HR HT HU ID IE IL IM IN IO IQ IR IS IT JE JM JO JP KE KG KH KI KM KN KP KR KW KY KZ LA LB LC '
  + 'LI LK LR LS LT LU LV LY MA MC MD ME MF MG MH MK ML MM MN MO MP MQ MR MS MT MU MV MW MX MY MZ NA '
  + 'NC NE NF NG NI NL NO NP NR NU NZ OM PA PE PF PG PH PK PL PM PN PR PS PT PW PY QA RE RO RS RU RW '
  + 'SA SB SC SD SE SG SH SI SJ SK SL SM SN SO SR SS ST SV SX SY SZ TC TD TF TG TH TJ TK TL TM TN TO '
  + 'TR TT TV TW TZ UA UG UM US UY UZ VA VC VE VG VI VN VU WF WS YE YT ZA ZM ZW'
).split(' ')

const KNOWN = new Set(COUNTRY_CODES)

/** A two-letter code we know, upper-cased, or null. */
export function countryCode(value: string | null | undefined): string | null {
  const code = (value ?? '').trim().toUpperCase()
  return KNOWN.has(code) ? code : null
}

/** The country in a browser locale such as "es-MX", if it names one. */
export function countryFromLocale(locale: string | null | undefined): string | null {
  const parts = (locale ?? '').split(/[-_]/)
  return parts.length > 1 ? countryCode(parts[parts.length - 1]) : null
}

/** A country's name in `language`, falling back to its code where the browser has none. */
export function countryName(code: string, language: string): string {
  try {
    return new Intl.DisplayNames([language], { type: 'region' }).of(code) ?? code
  } catch {
    return code
  }
}
