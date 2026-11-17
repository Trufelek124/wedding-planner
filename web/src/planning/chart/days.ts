/** Whole days, counted from 1 January 1970. The chart works in these: a day is a column, never a moment. */

const DAY = 24 * 60 * 60 * 1000

/** "2026-11-17" → the number of that day. */
export function dayNumber(date: string): number {
  return Math.round(Date.parse(`${date}T00:00:00Z`) / DAY)
}

/** The number of a day → "2026-11-17". */
export function dateOfDay(day: number): string {
  return new Date(day * DAY).toISOString().slice(0, 10)
}

export function addDays(date: string, days: number): string {
  return dateOfDay(dayNumber(date) + days)
}

/** The Monday on or before the given day. 1 January 1970 was a Thursday. */
export function mondayOf(day: number): number {
  return day - ((((day + 3) % 7) + 7) % 7)
}

/** The first day of the month the given day is in. */
export function firstOfMonth(day: number): number {
  return dayNumber(`${dateOfDay(day).slice(0, 8)}01`)
}

/** The first day of the month after the one the given day is in. */
export function firstOfNextMonth(day: number): number {
  const date = new Date(day * DAY)
  return Math.round(Date.UTC(date.getUTCFullYear(), date.getUTCMonth() + 1, 1) / DAY)
}
