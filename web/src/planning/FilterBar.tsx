import { useId } from 'react'
import { useCurrentWedding } from '../wedding/WeddingContext.ts'
import type { Filters } from './filters.ts'
import { STATUS_LABEL } from './labels.ts'
import type { Category, Status } from './planningApi.ts'

interface FilterBarProps {
  filters: Filters
  categories: Category[]
  onChange: (name: keyof Filters, value: string) => void
}

const STATUSES: Status[] = ['NOT_STARTED', 'IN_PROGRESS', 'DONE', 'DROPPED']

/** Narrows a list down and orders it. Every choice applies at once. */
export function FilterBar({ filters, categories, onChange }: FilterBarProps) {
  const wedding = useCurrentWedding()
  const id = useId()

  return (
    <form className="filters" aria-label="Filter and order" onSubmit={(event) => event.preventDefault()}>
      <div className="filter">
        <label htmlFor={`${id}-status`}>Show</label>
        <select id={`${id}-status`} value={filters.status} onChange={(event) => onChange('status', event.target.value)}>
          <option value="open">Still to do</option>
          <option value="all">Everything</option>
          {STATUSES.map((status) => (
            <option key={status} value={status}>
              {STATUS_LABEL[status]}
            </option>
          ))}
        </select>
      </div>
      <div className="filter">
        <label htmlFor={`${id}-who`}>Who</label>
        <select id={`${id}-who`} value={filters.who} onChange={(event) => onChange('who', event.target.value)}>
          <option value="">Anyone</option>
          {wedding.members.map((member) => (
            <option key={member.id} value={member.id}>
              {member.displayName}
            </option>
          ))}
        </select>
      </div>
      <div className="filter">
        <label htmlFor={`${id}-category`}>Category</label>
        <select id={`${id}-category`} value={filters.category} onChange={(event) => onChange('category', event.target.value)}>
          <option value="">Any</option>
          {categories.map((category) => (
            <option key={category.id} value={category.id}>
              {category.name}
            </option>
          ))}
        </select>
      </div>
      <div className="filter">
        <label htmlFor={`${id}-sort`}>Order</label>
        <select id={`${id}-sort`} value={filters.sort} onChange={(event) => onChange('sort', event.target.value)}>
          <option value="date">By date</option>
          <option value="priority">By priority</option>
          <option value="title">By title</option>
        </select>
      </div>
    </form>
  )
}
