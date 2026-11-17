import { Link, useLocation, useSearchParams } from 'react-router'
import { PageRetry, PageWaiting } from '../ui/Screen.tsx'
import { todayIn } from '../wedding/dates.ts'
import { useCurrentWedding } from '../wedding/WeddingContext.ts'
import { Chart, type DatedEntry } from './chart/Chart.tsx'
import { dayNumber } from './chart/days.ts'
import { spanOf, type Scale } from './chart/layout.ts'
import { FilterBar } from './FilterBar.tsx'
import { useFilters } from './filters.ts'
import { listCategories, listEntries, type Entry } from './planningApi.ts'
import { PlanViews } from './PlanViews.tsx'
import { useLoaded } from './useLoaded.ts'

function hasDates(entry: Entry): entry is DatedEntry {
  return entry.dueDate !== null
}

/** Whether the screen is as narrow as a phone's; no, where the browser cannot say. */
function narrowScreen(): boolean {
  return typeof (window as Partial<Window>).matchMedia === 'function' && window.matchMedia('(max-width: 40rem)').matches
}

/** Rows run from the plan item that starts first to the one that starts last. */
function byStart(a: DatedEntry, b: DatedEntry): number {
  return spanOf(a).start - spanOf(b).start || dayNumber(a.dueDate) - dayNumber(b.dueDate) || a.title.localeCompare(b.title)
}

/** The plan as a chart: the plan items with dates on a timeline, and those without dates listed under it. */
export function ChartPage() {
  const wedding = useCurrentWedding()
  const { search } = useLocation()
  const [params, setParams] = useSearchParams()
  const { filters, set, query, key } = useFilters()
  // A phone shows nine days by week: there the chart opens by month unless the address says otherwise.
  const usual: Scale = narrowScreen() ? 'month' : 'week'
  const asked = params.get('scale')
  const scale: Scale = asked === 'week' || asked === 'month' ? asked : usual

  const items = useLoaded(key, (signal) => listEntries({ ...query, kind: 'PLAN_ITEM', sort: 'DUE_DATE' }, signal))
  const tasks = useLoaded('tasks', (signal) => listEntries({ kind: 'TASK', scheduled: true, sort: 'DUE_DATE' }, signal))
  const categories = useLoaded('categories', listCategories)

  function setScale(value: string) {
    const next = new URLSearchParams(params)
    if (value === usual) {
      next.delete('scale')
    } else {
      next.set('scale', value)
    }
    setParams(next, { replace: true })
  }

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
  const dated = all.filter(hasDates).sort(byStart)
  const undated = all.filter((item) => !hasDates(item))
  const back = `/plan/chart${search}`
  const narrowed = filters.status !== 'open' || filters.who !== '' || filters.category !== '' || filters.priority !== ''

  return (
    <div className="chart-page">
      <div className="page-head">
        <h1>Plan</h1>
        <Link className="button" to="/entries/new?kind=PLAN_ITEM" state={{ back }}>
          Add a plan item
        </Link>
      </div>
      <PlanViews />
      <FilterBar filters={filters} categories={categories.state.data} onChange={set} ordered={false}>
        <div className="filter">
          <label htmlFor="chart-scale">Scale</label>
          <select id="chart-scale" value={scale} onChange={(event) => setScale(event.target.value)}>
            <option value="week">Weeks</option>
            <option value="month">Months</option>
          </select>
        </div>
      </FilterBar>

      {dated.length === 0 ? (
        <p className="empty">
          {all.length === 0
            ? narrowed
              ? 'Nothing matches these filters.'
              : 'Nothing in the plan yet. Add the first step.'
            : 'No plan item here has dates yet. Give one an end date and it becomes a bar on the chart.'}
        </p>
      ) : (
        <Chart
          items={dated}
          tasks={tasks.state.data}
          scale={scale}
          today={todayIn(wedding.timeZone)}
          weddingDay={wedding.date}
          back={back}
          onChanged={() => {
            items.reload()
            tasks.reload()
          }}
        />
      )}

      {undated.length > 0 && (
        <section className="group" aria-label="Not scheduled">
          <h2>Not scheduled</h2>
          <p className="muted">Without dates, so not on the chart. Open one and give it an end date to place it.</p>
          <ul className="undated">
            {undated.map((item) => (
              <li key={item.id}>
                <Link to={`/entries/${item.id}`} state={{ back }}>
                  {item.title}
                </Link>
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  )
}
