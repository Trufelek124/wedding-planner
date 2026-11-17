import { Link } from 'react-router'
import { PageRetry, PageWaiting } from '../ui/Screen.tsx'
import { EntryMeta } from './EntryMeta.tsx'
import { FilterBar } from './FilterBar.tsx'
import { useFilters } from './filters.ts'
import { STATUS_LABEL } from './labels.ts'
import { listCategories, listEntries, type Category, type Entry, type Status } from './planningApi.ts'
import { PlanViews } from './PlanViews.tsx'
import { TaskRow } from './TaskRow.tsx'
import { useLoaded } from './useLoaded.ts'
import { useStatusChange, type PendingStatus } from './useStatusChange.ts'

const STATUSES: Status[] = ['NOT_STARTED', 'IN_PROGRESS', 'DONE', 'DROPPED']

/** Everything that has to happen, step by step. Steps with dates first, then those not yet scheduled. */
export function PlanPage() {
  const { filters, set, query, key } = useFilters()
  const items = useLoaded(key, (signal) => listEntries({ ...query, kind: 'PLAN_ITEM' }, signal))
  const tasks = useLoaded('tasks', (signal) => listEntries({ kind: 'TASK', sort: 'DUE_DATE' }, signal))
  const categories = useLoaded('categories', listCategories)
  const status = useStatusChange(() => {
    items.reload()
    tasks.reload()
  })

  if (items.state.status === 'failed' || tasks.state.status === 'failed' || categories.state.status === 'failed') {
    return (
      <PageRetry
        message="The plan could not be loaded. Try again."
        onRetry={() => {
          items.retry()
          tasks.retry()
          categories.retry()
        }}
      />
    )
  }
  if (items.state.status === 'loading' || tasks.state.status === 'loading' || categories.state.status === 'loading') {
    return <PageWaiting what="Loading…" />
  }

  const all = items.state.data
  const scheduled = all.filter((item) => item.dueDate !== null)
  const notScheduled = all.filter((item) => item.dueDate === null)
  const group = {
    tasks: tasks.state.data,
    categories: categories.state.data,
    pending: status.pending,
    shown: status.shown,
    onStatus: status.change,
  }

  return (
    <>
      <div className="page-head">
        <h1>Plan</h1>
        <Link className="button" to="/entries/new?kind=PLAN_ITEM">
          Add a plan item
        </Link>
      </div>
      <PlanViews />
      <FilterBar filters={filters} categories={categories.state.data} onChange={set} />
      {status.failed && (
        <p role="alert" className="alert">
          That change was not saved. Try again.
        </p>
      )}
      {all.length === 0 ? (
        <p className="empty">
          {filters.status === 'open' && filters.who === '' && filters.category === '' && filters.priority === ''
            ? 'Nothing in the plan yet. Add the first step.'
            : 'Nothing matches these filters.'}
        </p>
      ) : (
        <>
          <PlanGroup title="Scheduled" items={scheduled} {...group} />
          <PlanGroup title="Not scheduled" items={notScheduled} {...group} />
        </>
      )}
    </>
  )
}

interface PlanGroupProps {
  title: string
  items: Entry[]
  tasks: Entry[]
  categories: Category[]
  pending: PendingStatus | null
  shown: (entry: Entry) => Status
  onStatus: (entry: Entry, status: Status) => void
}

function PlanGroup({ title, items, tasks, categories, pending, shown, onStatus }: PlanGroupProps) {
  if (items.length === 0) {
    return null
  }
  return (
    <section className="group" aria-label={title}>
      <h2>{title}</h2>
      <ul className="entries">
        {items.map((item) => {
          const own = tasks.filter((task) => task.parentId === item.id)
          const done = own.filter((task) => shown(task) === 'DONE').length
          const itemStatus = shown(item)
          return (
            <li key={item.id} className="entry entry-plan" data-done={itemStatus === 'DONE' || itemStatus === 'DROPPED'}>
              <div className="entry-body">
                <Link className="entry-title" to={`/entries/${item.id}`}>
                  {item.title}
                </Link>
                <EntryMeta entry={item} categories={categories} />
                {own.length > 0 && (
                  <details className="entry-tasks">
                    <summary>
                      {own.length === 1 ? '1 task' : `${own.length} tasks`}
                      {done > 0 && `, ${done} done`}
                    </summary>
                    <ul className="entries">
                      {own.map((task) => (
                        <TaskRow
                          key={task.id}
                          task={task}
                          categories={categories}
                          status={shown(task)}
                          busy={pending?.id === task.id}
                          onStatus={onStatus}
                        />
                      ))}
                    </ul>
                  </details>
                )}
                <Link className="quiet-link" to={`/entries/new?kind=TASK&parent=${item.id}`}>
                  Add a task to this
                </Link>
              </div>
              <select
                className="status-select"
                aria-label={`Status of ${item.title}`}
                value={itemStatus}
                disabled={pending?.id === item.id}
                onChange={(event) => onStatus(item, event.target.value as Status)}
              >
                {STATUSES.map((status) => (
                  <option key={status} value={status}>
                    {STATUS_LABEL[status]}
                  </option>
                ))}
              </select>
            </li>
          )
        })}
      </ul>
    </section>
  )
}
