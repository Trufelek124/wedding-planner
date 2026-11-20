import { money } from '../money/money.ts'
import { PAYMENT_KIND_LABEL, PRIORITY_LABEL, shortDate, STATUS_LABEL } from './labels.ts'
import type { TaskFileReport } from './taskFileApi.ts'

/** How many of each list are shown; the rest is said as a number. */
export const MOST_LISTED = 100

/** Long text is cut to this many characters in a change. */
const MOST_SHOWN = 120

/** The file's own names of fields, in the words of the task's form. */
const FIELD_LABEL: Record<string, string> = {
  key: 'Key',
  title: 'Title',
  notes: 'Notes',
  dueDate: 'Due',
  status: 'Status',
  priority: 'Priority',
  category: 'Category',
  vendor: 'Vendor',
  amount: 'Amount',
  paymentKind: 'Kind of payment',
  paidOn: 'Paid on',
}

/** A field's name for a person; a name the format does not have is shown as it was written. */
export function fieldLabel(field: string): string {
  return FIELD_LABEL[field] ?? field
}

const lookup = (labels: Record<string, string>, value: string) => labels[value] ?? value

/** "1 task", "3 tasks" */
export function tasks(count: number): string {
  return `${count} ${count === 1 ? 'task' : 'tasks'}`
}

function cut(text: string): string {
  const letters = Array.from(text)
  return letters.length > MOST_SHOWN ? `${letters.slice(0, MOST_SHOWN).join('')}…` : text
}

/** One side of a change in the page's own words. */
export function valueInWords(field: string, value: string | null, currency: string): string {
  if (value === null || (value === '' && field !== 'amount')) {
    return field === 'priority' ? 'set by the planner' : 'none'
  }
  switch (field) {
    case 'dueDate':
    case 'paidOn':
      return /^\d{4}-\d{2}-\d{2}$/.test(value) ? shortDate(value) : value
    case 'status':
      return lookup(STATUS_LABEL, value)
    case 'priority':
      return lookup(PRIORITY_LABEL, value)
    case 'paymentKind':
      return lookup(PAYMENT_KIND_LABEL, value)
    case 'amount':
      return /^-?\d{1,15}$/.test(value) ? money(Number(value), currency) : value
    default:
      return cut(value)
  }
}

/** "Status: Not started → Done" */
export function changeInWords(change: { field: string; from: string | null; to: string | null }, currency: string): string {
  return `${fieldLabel(change.field)}: ${valueInWords(change.field, change.from, currency)} → ${valueInWords(change.field, change.to, currency)}`
}

function and(parts: string[]): string {
  return parts.length < 2 ? (parts[0] ?? '') : `${parts.slice(0, -1).join(', ')} and ${parts[parts.length - 1]}`
}

/** "The file has 3 new tasks, 2 tasks that would change and 1 task that stays as it is." */
export function numbersInWords(report: Pick<TaskFileReport, 'added' | 'changed' | 'unchanged'>): string {
  const parts: string[] = []
  if (report.added > 0) {
    parts.push(`${report.added} new ${report.added === 1 ? 'task' : 'tasks'}`)
  }
  if (report.changed > 0) {
    parts.push(`${tasks(report.changed)} that would change`)
  }
  if (report.unchanged > 0) {
    parts.push(`${tasks(report.unchanged)} that ${report.unchanged === 1 ? 'stays' : 'stay'} as ${report.unchanged === 1 ? 'it is' : 'they are'}`)
  }
  return `The file has ${and(parts)}.`
}

/** "Add 3 tasks and change 2", "Add 3 tasks", "Change 2 tasks"; null when there is nothing to do. */
export function buttonInWords(report: Pick<TaskFileReport, 'added' | 'changed'>): string | null {
  if (report.added > 0 && report.changed > 0) {
    return `Add ${tasks(report.added)} and change ${report.changed}`
  }
  if (report.added > 0) {
    return `Add ${tasks(report.added)}`
  }
  if (report.changed > 0) {
    return `Change ${tasks(report.changed)}`
  }
  return null
}

/** What was done, once it is: "Added 3 tasks and changed 2." */
export function doneInWords(report: Pick<TaskFileReport, 'added' | 'changed'>): string {
  if (report.added > 0 && report.changed > 0) {
    return `Added ${tasks(report.added)} and changed ${report.changed}.`
  }
  if (report.added > 0) {
    return `Added ${tasks(report.added)}.`
  }
  if (report.changed > 0) {
    return `Changed ${tasks(report.changed)}.`
  }
  return 'The file changed nothing: every task in it was already as the file says.'
}

export interface Wrong {
  /** Where: the whole file, or a row of it. */
  row: number | null
  key: string | null
  field: string | null
  message: string
}

/** Everything that stops the file, the file's own problems first and then row by row. */
export function whatIsWrong(report: Pick<TaskFileReport, 'problems' | 'rows'>): Wrong[] {
  const wrong: Wrong[] = report.problems.map((problem) => ({ row: null, key: null, field: null, message: problem.message }))
  for (const row of report.rows) {
    if (row.outcome !== 'REFUSED') {
      continue
    }
    if (row.problems.length === 0) {
      wrong.push({ row: row.row, key: row.key, field: null, message: 'This row cannot be taken.' })
    }
    for (const problem of row.problems) {
      wrong.push({ row: row.row, key: row.key, field: problem.field, message: problem.message })
    }
  }
  return wrong
}

/** "Row 3, “venue” — Due: Enter a real day." */
export function wrongInWords(wrong: Wrong): string {
  if (wrong.row === null) {
    return wrong.message
  }
  const where = wrong.key === null ? `Row ${wrong.row}` : `Row ${wrong.row}, “${wrong.key}”`
  return `${where} — ${wrong.field === null ? '' : `${fieldLabel(wrong.field)}: `}${wrong.message}`
}
