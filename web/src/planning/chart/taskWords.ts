import { shortDate, when } from '../labels.ts'
import { money } from '../../money/money.ts'
import type { Entry } from '../planningApi.ts'

/** What a task's state is called on the chart. A state is always said in words, never by its colour alone. */
export function taskStates(task: Pick<Entry, 'status' | 'dueDate'>, today: string): string[] {
  const states: string[] = []
  if (task.status === 'DONE') {
    states.push('done')
  } else if (task.status === 'DROPPED') {
    states.push('dropped')
  } else {
    if (task.status === 'IN_PROGRESS') {
      states.push('in progress')
    }
    if (task.dueDate !== null && task.dueDate < today) {
      states.push('overdue')
    }
  }
  return states
}

/** "Pay the deposit, 2,500.00 zł, due 5 Dec 2026, overdue": what a screen reader hears of a task's marker. */
export function markerName(task: Entry, today: string, currency: string): string {
  return [task.title, task.amount === null ? null : money(task.amount, currency), when(task), ...taskStates(task, today)]
    .filter((part) => part !== null)
    .join(', ')
}

/** One thing a row says after the title; the warning one is drawn as a warning, and says "overdue" in words. */
export interface Fact {
  text: string
  warning: boolean
}

/**
 * What a task says of itself in a row, after its title: first how it stands (done, overdue…), so that a narrow
 * row that cuts its end off still says it, then the amount of a payment, then the day.
 */
export function rowFacts(task: Entry, today: string, currency: string): Fact[] {
  return [
    ...taskStates(task, today).map((text) => ({ text, warning: text === 'overdue' })),
    ...(task.amount === null ? [] : [{ text: money(task.amount, currency), warning: false }]),
    ...(task.dueDate === null ? [] : [{ text: `due ${shortDate(task.dueDate)}`, warning: false }]),
  ]
}
