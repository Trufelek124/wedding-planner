import { dateOfDay, dayNumber, firstOfMonth, firstOfNextMonth, mondayOf } from './days.ts'

/**
 * Where things go on the chart. Everything is arithmetic on day numbers: nothing is measured on the page,
 * so the same dates always give the same picture.
 */

export type Scale = 'week' | 'month'

/** How wide a day is, in pixels. */
export const DAY_WIDTH: Record<Scale, number> = { week: 24, month: 6 }
export const ROW_HEIGHT = 52
export const BAR_HEIGHT = 22
/** The upper row of the scale: months, or years. */
export const UPPER_HEIGHT = 22
/** Under it, a row of weeks or months and one for the names of the marked days. */
export const LOWER_HEIGHT = 40
export const HEAD_HEIGHT = UPPER_HEIGHT + LOWER_HEIGHT
/** A bar is never narrower than this, so that a single day can still be grabbed on the month scale. */
export const MIN_BAR_WIDTH = 10

/** Days, both included. */
export interface Span {
  start: number
  end: number
}

export interface Dates {
  startDate: string | null
  dueDate: string
}

/** The days a plan item covers. One with only an end date covers that day. */
export function spanOf(dates: Dates): Span {
  const end = dayNumber(dates.dueDate)
  return { start: dates.startDate === null ? end : Math.min(dayNumber(dates.startDate), end), end }
}

/** The stretch of days the chart shows. */
export interface Frame {
  first: number
  days: number
  dayWidth: number
  width: number
}

/**
 * A frame around all the given days, with room before and after, cut at whole weeks or whole months
 * so that the scale starts and ends on a line.
 */
export function frameOf(scale: Scale, days: readonly number[]): Frame {
  const earliest = Math.min(...days)
  const latest = Math.max(...days)
  const first = scale === 'week' ? mondayOf(earliest - 7) : firstOfMonth(earliest - 14)
  const end = scale === 'week' ? mondayOf(latest + 14) + 7 : firstOfNextMonth(latest + 14)
  const dayWidth = DAY_WIDTH[scale]
  return { first, days: end - first, dayWidth, width: (end - first) * dayWidth }
}

/** The left edge of a day. */
export function xOf(frame: Frame, day: number): number {
  return (day - frame.first) * frame.dayWidth
}

export interface Box {
  x: number
  y: number
  width: number
  height: number
}

/** The bar of a span in the given row. */
export function barOf(frame: Frame, span: Span, row: number): Box {
  return {
    x: xOf(frame, span.start),
    y: row * ROW_HEIGHT + (ROW_HEIGHT - BAR_HEIGHT) / 2,
    width: Math.max((span.end - span.start + 1) * frame.dayWidth, MIN_BAR_WIDTH),
    height: BAR_HEIGHT,
  }
}

export interface Tick {
  x: number
  width: number
  label: string
}

const MONTH_LONG = new Intl.DateTimeFormat('en-GB', { month: 'long', timeZone: 'UTC' })
const MONTH_SHORT = new Intl.DateTimeFormat('en-GB', { month: 'short', timeZone: 'UTC' })

function monthName(day: number, format: Intl.DateTimeFormat): string {
  return format.format(new Date(`${dateOfDay(day)}T00:00:00Z`))
}

/** Consecutive pieces of the frame, each from one boundary to the next. */
function pieces(frame: Frame, next: (day: number) => number, label: (day: number) => string): Tick[] {
  const ticks: Tick[] = []
  const end = frame.first + frame.days
  for (let day = frame.first; day < end; ) {
    const until = Math.min(next(day), end)
    ticks.push({ x: xOf(frame, day), width: (until - day) * frame.dayWidth, label: label(day) })
    day = until
  }
  return ticks
}

/**
 * The two rows of the scale. By week: months above, the day each week starts on below.
 * By month: years above, months below. `lower` is also where the vertical lines go.
 */
export function scaleOf(frame: Frame, scale: Scale): { upper: Tick[]; lower: Tick[] } {
  if (scale === 'week') {
    return {
      upper: pieces(frame, firstOfNextMonth, (day) => `${monthName(day, MONTH_LONG)} ${dateOfDay(day).slice(0, 4)}`),
      lower: pieces(frame, (day) => mondayOf(day) + 7, (day) => String(Number(dateOfDay(day).slice(8)))),
    }
  }
  return {
    upper: pieces(frame, (day) => dayNumber(`${Number(dateOfDay(day).slice(0, 4)) + 1}-01-01`), (day) => dateOfDay(day).slice(0, 4)),
    lower: pieces(frame, firstOfNextMonth, (day) => monthName(day, MONTH_SHORT)),
  }
}

export interface Point {
  x: number
  y: number
}

/** How far a link runs straight out of a bar, and into one, before it turns. */
const STUB = 10

/**
 * The line from the end of one bar to the start of the bar that waits for it: out to the right, down or up,
 * and in from the left. When the second bar starts too early for that, the line goes around between the rows.
 */
export function linkPath(from: Point, to: Point): string {
  const out = from.x + STUB
  const into = to.x - STUB
  if (into >= out) {
    return `M ${from.x} ${from.y} H ${out} V ${to.y} H ${to.x}`
  }
  const between = from.y + (to.y > from.y ? ROW_HEIGHT / 2 : -ROW_HEIGHT / 2)
  return `M ${from.x} ${from.y} H ${out} V ${between} H ${into} V ${to.y} H ${to.x}`
}

/** The row a height on the chart falls in. */
export function rowAt(y: number): number {
  return Math.floor(y / ROW_HEIGHT)
}

export type Grip = 'move' | 'start' | 'end'

/**
 * The dates after a bar was moved, or one of its ends was pulled, by a number of days.
 * An end never crosses the other one. A plan item with only an end date gets a start when it is stretched.
 */
export function shifted(dates: Dates, grip: Grip, days: number): Dates {
  const span = spanOf(dates)
  if (grip === 'move') {
    return {
      startDate: dates.startDate === null ? null : dateOfDay(span.start + days),
      dueDate: dateOfDay(span.end + days),
    }
  }
  const start = grip === 'start' ? Math.min(span.start + days, span.end) : span.start
  const end = grip === 'end' ? Math.max(span.end + days, span.start) : span.end
  return { startDate: dates.startDate === null && start === end ? null : dateOfDay(start), dueDate: dateOfDay(end) }
}
