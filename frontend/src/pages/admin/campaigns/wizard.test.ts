import { describe, expect, it } from 'vitest'
import {
  LIMITS, emptyStepOne, firstInvalid, lengthOf, quotaCheck, toDetailsRequest, toPreviewRequest,
  todayInIndia, validateStepOne, validateStepTwo, type StepOneFields,
} from './wizard'

const TODAY = '2026-09-26'

function filled(overrides: Partial<StepOneFields> = {}): StepOneFields {
  return {
    ...emptyStepOne(),
    title: 'TOTY Special Offer',
    subject: 'TOTY is here',
    promoTitle: 'TOTY COINS SALE',
    description: 'Team of the Year is here.',
    ...overrides,
  }
}

describe('validateStepOne', () => {
  it('accepts a complete step', () => {
    expect(validateStepOne(filled(), TODAY)).toEqual({})
  })

  it('names every starred field that is empty', () => {
    const errors = validateStepOne(emptyStepOne(), TODAY)
    expect(Object.keys(errors).sort()).toEqual(['description', 'promoTitle', 'subject', 'title'])
  })

  it('treats whitespace as empty', () => {
    expect(validateStepOne(filled({ title: '   ' }), TODAY).title).toBeDefined()
  })

  it('stops the subject at exactly 100, as the counter reads', () => {
    expect(validateStepOne(filled({ subject: 's'.repeat(100) }), TODAY).subject).toBeUndefined()
    expect(validateStepOne(filled({ subject: 's'.repeat(101) }), TODAY).subject)
      .toBe('Keep the subject to 100 characters.')
  })

  it('stops the description at exactly 500, as the counter reads', () => {
    expect(validateStepOne(filled({ description: 'd'.repeat(500) }), TODAY).description).toBeUndefined()
    expect(validateStepOne(filled({ description: 'd'.repeat(501) }), TODAY).description)
      .toBe('Keep the description to 500 characters.')
  })

  it('caps the offer and the code at 40', () => {
    const errors = validateStepOne(filled({ offerText: 'o'.repeat(41), promoCode: 'c'.repeat(41) }), TODAY)
    expect(errors.offerText).toBeDefined()
    expect(errors.promoCode).toBeDefined()
  })

  it('accepts today as the last day and refuses yesterday', () => {
    expect(validateStepOne(filled({ offerValidUntil: TODAY }), TODAY).offerValidUntil).toBeUndefined()
    expect(validateStepOne(filled({ offerValidUntil: '2026-09-25' }), TODAY).offerValidUntil)
      .toMatch(/already passed/)
  })

  it('leaves the date optional', () => {
    expect(validateStepOne(filled({ offerValidUntil: '' }), TODAY)).toEqual({})
  })

  it('reports one message per field, the missing one before the long one', () => {
    // Empty is also "not too long"; saying both would be noise.
    expect(validateStepOne(filled({ subject: '' }), TODAY).subject).toBe('An email subject is required.')
  })
})

describe('the limits match the server', () => {
  it('uses the numbers DetailsRequest enforces', () => {
    expect(LIMITS).toEqual({
      title: 120, subject: 100, promoTitle: 200, offerText: 40, promoCode: 40, description: 500,
    })
  })

  it('counts an emoji as two, as the server does', () => {
    expect(lengthOf('Coins 🔥')).toBe(8)
  })
})

describe('firstInvalid', () => {
  it('returns the first problem in the order the fields appear', () => {
    expect(firstInvalid({ description: 'x', subject: 'y' })).toBe('subject')
    expect(firstInvalid({})).toBeUndefined()
  })
})

describe('todayInIndia', () => {
  it('is already tomorrow in India late in the UTC evening', () => {
    // 20:00 UTC is 01:30 the next day in India.
    expect(todayInIndia(new Date('2026-09-25T20:00:00Z'))).toBe('2026-09-26')
    expect(todayInIndia(new Date('2026-09-25T10:00:00Z'))).toBe('2026-09-25')
  })
})

describe('request bodies', () => {
  it('trims and nulls optional fields when saving', () => {
    expect(toDetailsRequest(filled({ title: '  T  ', offerText: '  ', promoCode: '', offerValidUntil: '' })))
      .toMatchObject({ title: 'T', offerText: null, promoCode: null, offerValidUntil: null })
  })

  it('sends the preview what is on screen, and drops a half-typed date', () => {
    const body = toPreviewRequest(filled({ subject: ' spaced ', offerValidUntil: '2026-1' }), 'camp_x')
    expect(body.subject).toBe(' spaced ')
    expect(body.offerValidUntil).toBeNull()
    expect(body.campaignId).toBe('camp_x')
  })
})

describe('step 2 and the allowance', () => {
  it('holds the lines to 40 and 60', () => {
    expect(validateStepTwo({ kicker: 'k'.repeat(40), subline: 's'.repeat(60) })).toEqual({})
    const errors = validateStepTwo({ kicker: 'k'.repeat(41), subline: 's'.repeat(61) })
    expect(errors.kicker).toBeDefined()
    expect(errors.subline).toBeDefined()
  })

  it('says exactly how many are over, and never a negative number', () => {
    expect(quotaCheck(42, 97)).toEqual({ over: 0, fits: true })
    expect(quotaCheck(42, 42)).toEqual({ over: 0, fits: true })
    expect(quotaCheck(42, 10)).toEqual({ over: 32, fits: false })
    expect(quotaCheck(5, 0)).toEqual({ over: 5, fits: false })
  })

  it('sends the hero lines to the preview, and an empty one as absent', () => {
    expect(toPreviewRequest({ ...filled(), kicker: 'TOTY', subline: '' }, null))
      .toMatchObject({ kicker: 'TOTY', subline: null })
  })
})
