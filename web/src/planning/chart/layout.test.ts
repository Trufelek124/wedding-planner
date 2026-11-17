import { describe, expect, it } from 'vitest'
import { addDays, dateOfDay, dayNumber, firstOfMonth, firstOfNextMonth, mondayOf } from './days.ts'
import { barOf, frameOf, linkPath, rowAt, ROW_HEIGHT, scaleOf, shifted, spanOf, xOf } from './layout.ts'

const day = dayNumber

describe('days', () => {
  it('counts days and turns them back into dates, across months, years and leap days', () => {
    expect(dateOfDay(day('2026-11-17'))).toBe('2026-11-17')
    expect(addDays('2026-12-31', 1)).toBe('2027-01-01')
    expect(addDays('2028-02-28', 1)).toBe('2028-02-29')
    expect(addDays('2027-03-01', -1)).toBe('2027-02-28')
    expect(day('2027-06-19') - day('2026-11-17')).toBe(214)
  })

  it('finds the Monday of a week and the edges of a month', () => {
    expect(dateOfDay(mondayOf(day('2026-11-17')))).toBe('2026-11-16') // a Tuesday
    expect(dateOfDay(mondayOf(day('2026-11-16')))).toBe('2026-11-16') // a Monday
    expect(dateOfDay(mondayOf(day('2026-11-22')))).toBe('2026-11-16') // a Sunday
    expect(dateOfDay(mondayOf(day('1969-12-31')))).toBe('1969-12-29') // before the count starts
    expect(dateOfDay(firstOfMonth(day('2026-11-17')))).toBe('2026-11-01')
    expect(dateOfDay(firstOfNextMonth(day('2026-12-17')))).toBe('2027-01-01')
  })
})

describe('the frame of the chart', () => {
  it('by week: starts on a Monday at least a week before, ends on a Monday at least two weeks after', () => {
    const frame = frameOf('week', [day('2026-11-17'), day('2027-06-19')])

    expect(dateOfDay(frame.first)).toBe('2026-11-09')
    expect(dateOfDay(frame.first + frame.days)).toBe('2027-07-05')
    expect(frame.days % 7).toBe(0)
    expect(frame.width).toBe(frame.days * 24)
  })

  it('by month: starts and ends with whole months', () => {
    const frame = frameOf('month', [day('2026-11-17'), day('2027-06-19')])

    expect(dateOfDay(frame.first)).toBe('2026-11-01')
    expect(dateOfDay(frame.first + frame.days)).toBe('2027-08-01')
    expect(frame.dayWidth).toBe(6)
  })

  it('places a bar from its dates alone', () => {
    const frame = frameOf('week', [day('2026-11-17')])

    const bar = barOf(frame, spanOf({ startDate: '2026-11-12', dueDate: '2026-11-18' }), 2)
    expect(bar.x).toBe(xOf(frame, day('2026-11-12')))
    expect(bar.width).toBe(7 * 24)
    expect(bar.y + bar.height / 2).toBe(2.5 * ROW_HEIGHT)
    expect(rowAt(bar.y)).toBe(2)
  })

  it('gives a plan item with only an end date its one day, wide enough to grab on any scale', () => {
    expect(spanOf({ startDate: null, dueDate: '2026-11-18' })).toEqual({ start: day('2026-11-18'), end: day('2026-11-18') })
    expect(barOf(frameOf('week', [day('2026-11-18')]), spanOf({ startDate: null, dueDate: '2026-11-18' }), 0).width).toBe(24)
    expect(barOf(frameOf('month', [day('2026-11-18')]), spanOf({ startDate: null, dueDate: '2026-11-18' }), 0).width).toBe(10)
  })
})

describe('the scale', () => {
  it('by week names the months and the day each week starts on', () => {
    const frame = frameOf('week', [day('2026-11-25'), day('2026-12-02')])
    const { upper, lower } = scaleOf(frame, 'week')

    expect(upper.map((tick) => tick.label)).toEqual(['November 2026', 'December 2026'])
    expect(lower.map((tick) => tick.label)).toEqual(['16', '23', '30', '7', '14'])
    expect(lower.every((tick) => tick.width === 7 * 24)).toBe(true)
    // The pieces of a row lie side by side and fill the frame.
    expect(upper.reduce((sum, tick) => sum + tick.width, 0)).toBe(frame.width)
    expect(upper[1]?.x).toBe(xOf(frame, day('2026-12-01')))
  })

  it('by month names the years and the months', () => {
    const { upper, lower } = scaleOf(frameOf('month', [day('2026-12-10'), day('2027-01-20')]), 'month')

    expect(upper.map((tick) => tick.label)).toEqual(['2026', '2027'])
    expect(lower.map((tick) => tick.label)).toEqual(['Nov', 'Dec', 'Jan', 'Feb'])
    expect(lower.map((tick) => tick.width)).toEqual([30 * 6, 31 * 6, 31 * 6, 28 * 6])
  })
})

describe('moving a bar and pulling its ends', () => {
  const dates = { startDate: '2026-12-01', dueDate: '2026-12-10' }

  it('moves both dates together', () => {
    expect(shifted(dates, 'move', 3)).toEqual({ startDate: '2026-12-04', dueDate: '2026-12-13' })
    expect(shifted(dates, 'move', -31)).toEqual({ startDate: '2026-10-31', dueDate: '2026-11-09' })
  })

  it('pulls one end and leaves the other', () => {
    expect(shifted(dates, 'end', 5)).toEqual({ startDate: '2026-12-01', dueDate: '2026-12-15' })
    expect(shifted(dates, 'start', -2)).toEqual({ startDate: '2026-11-29', dueDate: '2026-12-10' })
  })

  it('never lets an end cross the other one', () => {
    expect(shifted(dates, 'end', -30)).toEqual({ startDate: '2026-12-01', dueDate: '2026-12-01' })
    expect(shifted(dates, 'start', 30)).toEqual({ startDate: '2026-12-10', dueDate: '2026-12-10' })
  })

  it('keeps a plan item with only an end date that way when moved, and gives it a start when stretched', () => {
    const onlyEnd = { startDate: null, dueDate: '2026-12-10' }

    expect(shifted(onlyEnd, 'move', 2)).toEqual({ startDate: null, dueDate: '2026-12-12' })
    expect(shifted(onlyEnd, 'start', -4)).toEqual({ startDate: '2026-12-06', dueDate: '2026-12-10' })
    expect(shifted(onlyEnd, 'end', 4)).toEqual({ startDate: '2026-12-10', dueDate: '2026-12-14' })
    expect(shifted(onlyEnd, 'end', -4)).toEqual({ startDate: null, dueDate: '2026-12-10' })
  })
})

describe('the line of a dependency', () => {
  it('runs out of the first bar, to the height of the second, and into it', () => {
    expect(linkPath({ x: 100, y: 26 }, { x: 200, y: 78 })).toBe('M 100 26 H 110 V 78 H 200')
  })

  it('goes around between the rows when the second bar starts before the first one ends', () => {
    expect(linkPath({ x: 300, y: 26 }, { x: 200, y: 78 })).toBe('M 300 26 H 310 V 52 H 190 V 78 H 200')
    expect(linkPath({ x: 300, y: 78 }, { x: 200, y: 26 })).toBe('M 300 78 H 310 V 52 H 190 V 26 H 200')
  })
})
