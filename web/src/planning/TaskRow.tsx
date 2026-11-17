import { Link } from 'react-router'
import { EntryMeta } from './EntryMeta.tsx'
import type { Category, Entry, Status } from './planningApi.ts'

interface TaskRowProps {
  task: Entry
  categories: Category[]
  planItem?: string | undefined
  /** The status to show: the task's own, or the one that is being saved. */
  status: Status
  busy: boolean
  onStatus: (task: Entry, status: Status) => void
}

/** A task in a list: a box to tick it off, its title leading to the form, and its facts. */
export function TaskRow({ task, categories, planItem, status, busy, onStatus }: TaskRowProps) {
  const done = status === 'DONE'
  return (
    <li className="entry" data-done={done}>
      <input
        type="checkbox"
        className="tick"
        aria-label={`Done: ${task.title}`}
        checked={done}
        disabled={busy}
        onChange={(event) => onStatus(task, event.target.checked ? 'DONE' : 'NOT_STARTED')}
      />
      <div className="entry-body">
        <Link className="entry-title" to={`/entries/${task.id}`}>
          {task.title}
        </Link>
        <EntryMeta entry={task} categories={categories} planItem={planItem} />
      </div>
    </li>
  )
}
