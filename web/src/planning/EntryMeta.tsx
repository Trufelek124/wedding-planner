import { namesOf, useCurrentWedding } from '../wedding/WeddingContext.ts'
import { PRIORITY_LABEL, priorityReason, when } from './labels.ts'
import type { Category, Entry } from './planningApi.ts'

interface EntryMetaProps {
  entry: Entry
  categories: Category[]
  /** For a task: the title of the plan item it belongs to. */
  planItem?: string | undefined
}

/** The line under an entry's title: when, where it belongs, who, and how urgent. */
export function EntryMeta({ entry, categories, planItem }: EntryMetaProps) {
  const wedding = useCurrentWedding()
  const category = categories.find((candidate) => candidate.id === entry.categoryId)?.name
  const people = namesOf(wedding, entry.assigneeIds)
  const reason = priorityReason(entry)
  const facts = [when(entry), planItem, category, people].filter((fact): fact is string => Boolean(fact))

  return (
    <p className="entry-meta">
      {/* The level is a word, never a colour alone, and says why it is what it is. */}
      <span>
        <span className="priority" data-priority={entry.priority}>
          {PRIORITY_LABEL[entry.priority]}
        </span>
        {reason !== null && ` — ${reason}`}
      </span>
      {facts.map((fact) => (
        <span key={fact}>{fact}</span>
      ))}
    </p>
  )
}
