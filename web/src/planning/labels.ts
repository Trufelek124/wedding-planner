import type { Entry, Kind, Priority, Status } from './planningApi.ts'

export const STATUS_LABEL: Record<Status, string> = {
  NOT_STARTED: 'Not started',
  IN_PROGRESS: 'In progress',
  DONE: 'Done',
  DROPPED: 'Dropped',
}

export const PRIORITY_LABEL: Record<Priority, string> = {
  CRITICAL: 'Critical',
  HIGH: 'High',
  NORMAL: 'Normal',
  LOW: 'Low',
}

export const KIND_LABEL: Record<Kind, string> = { PLAN_ITEM: 'plan item', TASK: 'task' }

/** "19 Jun 2027" */
export function shortDate(date: string): string {
  const parts = new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' })
    .formatToParts(new Date(`${date}T00:00:00Z`))
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find((entry) => entry.type === type)?.value ?? ''
  return `${part('day')} ${part('month')} ${part('year')}`
}

/** When an entry happens, in words: "1 Dec 2026 – 31 Jan 2027", "by 31 Jan 2027", "due 15 Jan 2027", or nothing. */
export function when(entry: Pick<Entry, 'kind' | 'startDate' | 'dueDate'>): string | null {
  if (entry.dueDate === null) {
    return null
  }
  if (entry.kind === 'TASK') {
    return `due ${shortDate(entry.dueDate)}`
  }
  return entry.startDate === null
    ? `by ${shortDate(entry.dueDate)}`
    : `${shortDate(entry.startDate)} – ${shortDate(entry.dueDate)}`
}
