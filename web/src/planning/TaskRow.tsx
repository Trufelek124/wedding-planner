import { Link } from 'react-router'
import { EntryMeta } from './EntryMeta.tsx'
import type { Category, Entry, Status } from './planningApi.ts'

interface TaskRowProps {
  task: Entry
  categories: Category[]
  planItem?: string | undefined
  /** The vendors, where the list names the one a task concerns. */
  vendors?: readonly { id: string; name: string }[] | undefined
  /** The status to show: the task's own, or the one that is being saved. */
  status: Status
  busy: boolean
  /** Where the form returns to, when that is not the list the entry's kind belongs to. */
  back?: string
  onStatus: (task: Entry, status: Status) => void
}

/** A task in a list: a box to tick it off (for a payment: to mark it paid), its title leading to the form, and its facts. */
export function TaskRow({ task, categories, planItem, vendors, status, busy, back, onStatus }: TaskRowProps) {
  const done = status === 'DONE'
  return (
    <li className="entry" data-done={done}>
      <input
        type="checkbox"
        className="tick"
        // Ticking a payment off pays it: the box says so.
        aria-label={`${task.amount === null ? 'Done' : 'Paid'}: ${task.title}`}
        checked={done}
        disabled={busy}
        onChange={(event) => onStatus(task, event.target.checked ? 'DONE' : 'NOT_STARTED')}
      />
      <div className="entry-body">
        <Link className="entry-title" to={`/entries/${task.id}`} state={back === undefined ? undefined : { back }}>
          {task.title}
        </Link>
        <EntryMeta entry={task} categories={categories} planItem={planItem} vendors={vendors} />
      </div>
    </li>
  )
}
