import { describe, expect, it } from 'vitest'
import { countdown, daysUntil, longDate, todayIn } from './dates.ts'

describe('dates', () => {
  it('knows that today depends on where the wedding is', () => {
    const lateEveningInWarsaw = new Date('2027-06-18T22:30:00Z') // already the 19th in Warsaw

    expect(todayIn('Europe/Warsaw', lateEveningInWarsaw)).toBe('2027-06-19')
    expect(todayIn('UTC', lateEveningInWarsaw)).toBe('2027-06-18')
    expect(todayIn('America/Los_Angeles', lateEveningInWarsaw)).toBe('2027-06-18')
  })

  it('counts whole days to the wedding in its time zone', () => {
    expect(daysUntil('2027-06-19', 'Europe/Warsaw', new Date('2026-11-17T12:00:00Z'))).toBe(214)
    expect(daysUntil('2027-06-19', 'Europe/Warsaw', new Date('2027-06-18T12:00:00Z'))).toBe(1)
    expect(daysUntil('2027-06-19', 'Europe/Warsaw', new Date('2027-06-18T22:30:00Z'))).toBe(0)
    expect(daysUntil('2027-06-19', 'Europe/Warsaw', new Date('2027-06-21T12:00:00Z'))).toBe(-2)
  })

  it('is not thrown off by the clocks changing in between', () => {
    // Summer time ends on 31 October 2027 in Warsaw: that day has 25 hours.
    expect(daysUntil('2027-11-01', 'Europe/Warsaw', new Date('2027-10-30T10:00:00Z'))).toBe(2)
  })

  it('says how long it is in words', () => {
    expect(countdown(214)).toBe('214 days to go')
    expect(countdown(2)).toBe('2 days to go')
    expect(countdown(1)).toBe('Tomorrow')
    expect(countdown(0)).toBe('Today is the day')
    expect(countdown(-1)).toBe('Yesterday')
    expect(countdown(-30)).toBe('30 days ago')
  })

  it('writes a date out in full', () => {
    expect(longDate('2027-06-19')).toBe('Saturday 19 June 2027')
  })
})
