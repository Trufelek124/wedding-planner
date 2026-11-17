/** Today's date ("2027-06-19") as people in the given time zone see it. */
export function todayIn(timeZone: string, now: Date = new Date()): string {
  // The Canadian English format is year-month-day, which is exactly the ISO form.
  return new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(now)
}

/** Whole days from today in the wedding's time zone to the given date; negative when it has passed. */
export function daysUntil(date: string, timeZone: string, now: Date = new Date()): number {
  const day = 24 * 60 * 60 * 1000
  return Math.round((Date.parse(`${date}T00:00:00Z`) - Date.parse(`${todayIn(timeZone, now)}T00:00:00Z`)) / day)
}

/** "Saturday 19 June 2027" */
export function longDate(date: string): string {
  // Put together from its parts: the punctuation of a ready-made format differs between browsers.
  const parts = new Intl.DateTimeFormat('en-GB', {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
    year: 'numeric',
    timeZone: 'UTC',
  }).formatToParts(new Date(`${date}T00:00:00Z`))
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find((entry) => entry.type === type)?.value ?? ''
  return `${part('weekday')} ${part('day')} ${part('month')} ${part('year')}`
}

/** "214 days to go", "Tomorrow", "Today is the day", "3 days ago" */
export function countdown(days: number): string {
  if (days > 1) return `${days} days to go`
  if (days === 1) return 'Tomorrow'
  if (days === 0) return 'Today is the day'
  if (days === -1) return 'Yesterday'
  return `${-days} days ago`
}
