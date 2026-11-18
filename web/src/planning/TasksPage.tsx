import { Link, useLocation } from 'react-router'
import { PageRetry, PageWaiting } from '../ui/Screen.tsx'
import { FilterBar } from './FilterBar.tsx'
import { useFilters } from './filters.ts'
import { listCategories, listEntries } from './planningApi.ts'
import { TaskRow } from './TaskRow.tsx'
import { useLoaded } from './useLoaded.ts'
import { useStatusChange } from './useStatusChange.ts'

/** Single things to do, with or without a date. Ticking one off takes one click. */
export function TasksPage() {
  const { filters, set, query, key } = useFilters()
  // The form returns to this list as it is narrowed down now.
  const back = `/tasks${useLocation().search}`
  const tasks = useLoaded(key, (signal) => listEntries({ ...query, kind: 'TASK' }, signal))
  const planItems = useLoaded('plan-items', (signal) => listEntries({ kind: 'PLAN_ITEM', sort: 'TITLE' }, signal))
  const categories = useLoaded('categories', listCategories)
  const status = useStatusChange(tasks.reload)

  if (tasks.state.status === 'failed' || planItems.state.status === 'failed' || categories.state.status === 'failed') {
    return (
      <PageRetry
        message="The tasks could not be loaded. Try again."
        onRetry={() => {
          tasks.retry()
          planItems.retry()
          categories.retry()
        }}
      />
    )
  }
  if (tasks.state.status === 'loading' || planItems.state.status === 'loading' || categories.state.status === 'loading') {
    return <PageWaiting what="Loading…" />
  }

  const all = tasks.state.data
  const categoryList = categories.state.data
  const planItemTitles = new Map(planItems.state.data.map((item) => [item.id, item.title]))

  return (
    <>
      <div className="page-head">
        <h1>Tasks</h1>
        <Link className="button" to="/entries/new?kind=TASK" state={{ back }}>
          Add a task
        </Link>
      </div>
      <FilterBar filters={filters} categories={categoryList} onChange={set} money />
      {status.failed && (
        <p role="alert" className="alert">
          That change was not saved. Try again.
        </p>
      )}
      {all.length === 0 ? (
        <p className="empty">
          {filters.status === 'open' && filters.who === '' && filters.category === '' && filters.priority === '' && filters.money === ''
            ? 'No tasks to do. Add one when something comes up.'
            : 'Nothing matches these filters.'}
        </p>
      ) : (
        <ul className="entries">
          {all.map((task) => (
            <TaskRow
              key={task.id}
              task={task}
              categories={categoryList}
              planItem={task.parentId === null ? undefined : planItemTitles.get(task.parentId)}
              status={status.shown(task)}
              busy={status.pending?.id === task.id}
              back={back}
              onStatus={status.change}
            />
          ))}
        </ul>
      )}
    </>
  )
}
