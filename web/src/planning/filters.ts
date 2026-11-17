import { useSearchParams } from 'react-router'
import type { EntryFilter, Priority, Status } from './planningApi.ts'

/** What the filter bar offers for "status"; "open" is what still needs doing. */
export type StatusChoice = 'open' | 'all' | Status
export type SortChoice = 'date' | 'priority' | 'title'

export interface Filters {
  status: StatusChoice
  who: string
  category: string
  /** One level, or '' for any. */
  priority: Priority | ''
  sort: SortChoice
}

const STATUS_CHOICES: readonly string[] = ['open', 'all', 'NOT_STARTED', 'IN_PROGRESS', 'DONE', 'DROPPED']
const PRIORITY_CHOICES: readonly string[] = ['CRITICAL', 'HIGH', 'NORMAL', 'LOW']
const SORT_CHOICES: readonly string[] = ['date', 'priority', 'title']
const SORT: Record<SortChoice, NonNullable<EntryFilter['sort']>> = { date: 'DUE_DATE', priority: 'PRIORITY', title: 'TITLE' }

/** The filters live in the page's address, so a reload or the back button keeps them. */
export function useFilters(): { filters: Filters; set: (name: keyof Filters, value: string) => void; query: EntryFilter; key: string } {
  const [params, setParams] = useSearchParams()
  const status = params.get('status') ?? 'open'
  const sort = params.get('sort') ?? 'date'
  const priority = params.get('priority') ?? ''
  const filters: Filters = {
    status: (STATUS_CHOICES.includes(status) ? status : 'open') as StatusChoice,
    who: params.get('who') ?? '',
    category: params.get('category') ?? '',
    priority: (PRIORITY_CHOICES.includes(priority) ? priority : '') as Priority | '',
    sort: (SORT_CHOICES.includes(sort) ? sort : 'date') as SortChoice,
  }

  const query: EntryFilter = { sort: SORT[filters.sort] }
  if (filters.status === 'open') {
    query.status = ['NOT_STARTED', 'IN_PROGRESS']
  } else if (filters.status !== 'all') {
    query.status = [filters.status]
  }
  if (filters.who !== '') {
    query.assigneeId = filters.who
  }
  if (filters.category !== '') {
    query.categoryId = filters.category
  }
  if (filters.priority !== '') {
    query.priority = [filters.priority]
  }

  function set(name: keyof Filters, value: string) {
    const next = new URLSearchParams(params)
    const isDefault = value === '' || (name === 'status' && value === 'open') || (name === 'sort' && value === 'date')
    if (isDefault) {
      next.delete(name)
    } else {
      next.set(name, value)
    }
    setParams(next, { replace: true })
  }

  return { filters, set, query, key: JSON.stringify(query) }
}
