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

type Reason = Entry['priorityReason']

/** Why an entry has its level, to follow the level after a dash. `{due}` is "due" for a task and "ends" for a plan item. */
const REASON_WORDS: Record<Reason, string> = {
  OVERDUE: 'overdue',
  DUE_WITHIN_3_DAYS: '{due} within 3 days',
  PAYMENT_DUE_WITHIN_7_DAYS: 'a payment due within 7 days',
  DUE_WITHIN_14_DAYS: '{due} within 14 days',
  NEEDED_BY_ANOTHER: 'something due soon waits for it',
  DUE_WITHIN_60_DAYS: '{due} within 60 days',
  DUE_LATER: '{due} in more than 60 days',
  NO_DATE: 'no date',
  SHOULD_HAVE_STARTED: 'should have started',
  FINISHED: 'needs no more attention',
  SET_BY_HAND: 'set by hand',
}

/** "due within 3 days", "set by hand"; null for a reason this version of the app does not know. */
export function priorityReason(entry: Pick<Entry, 'kind' | 'priorityReason'>): string | null {
  const words = (REASON_WORDS as Partial<Record<string, string>>)[entry.priorityReason]
  return words === undefined ? null : words.replace('{due}', entry.kind === 'TASK' ? 'due' : 'ends')
}

/** "Critical — due within 3 days": the level and why, as one sentence part. */
export function priorityInWords(entry: Pick<Entry, 'kind' | 'priority' | 'priorityReason'>): string {
  const reason = priorityReason(entry)
  return reason === null ? PRIORITY_LABEL[entry.priority] : `${PRIORITY_LABEL[entry.priority]} — ${reason}`
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
