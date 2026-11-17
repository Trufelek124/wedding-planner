import { useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { ApiError } from '../api/client.ts'
import { listCategories, type Category, type Entry, type Status } from '../planning/planningApi.ts'
import { TaskRow } from '../planning/TaskRow.tsx'
import { useLoaded } from '../planning/useLoaded.ts'
import { useStatusChange, type PendingStatus } from '../planning/useStatusChange.ts'
import { Field } from '../ui/Field.tsx'
import { PageRetry, PageWaiting } from '../ui/Screen.tsx'
import { countdown, longDate } from '../wedding/dates.ts'
import { useCurrentWedding, useWeddingUpdate } from '../wedding/WeddingContext.ts'
import { changeWedding } from '../wedding/weddingApi.ts'
import { fetchHome, type Home } from './homeApi.ts'

/** Where a signed-in owner lands: how long until the wedding, what is overdue, and what to turn to next. */
export function HomePage() {
  const wedding = useCurrentWedding()
  const names = wedding.members.map((member) => member.displayName).join(' & ')
  const home = useLoaded('home', fetchHome)
  const categories = useLoaded('categories', listCategories)
  return (
    <div className="stack-wide">
      <div className="hero">
        <h1>{names}</h1>
        {wedding.date === null ? (
          <AskForTheDay onSaved={home.reload} />
        ) : (
          <>
            <p className="muted">{longDate(wedding.date)}</p>
            {/* The server says how many days are left; this device's clock may be in another time zone. */}
            {home.state.status === 'ready' && home.state.data.daysLeft !== null && (
              <p className="countdown">{countdown(home.state.data.daysLeft)}</p>
            )}
          </>
        )}
      </div>

      {home.state.status === 'failed' || categories.state.status === 'failed' ? (
        <PageRetry
          message="What is next could not be loaded. Try again."
          onRetry={() => {
            home.retry()
            categories.retry()
          }}
        />
      ) : home.state.status === 'loading' || categories.state.status === 'loading' ? (
        <PageWaiting what="Loading…" />
      ) : (
        <WhatIsNext home={home.state.data} categories={categories.state.data} onChanged={home.reload} />
      )}
    </div>
  )
}

/** Everything overdue, then the five entries to turn to next. */
function WhatIsNext({ home, categories, onChanged }: { home: Home; categories: Category[]; onChanged: () => void }) {
  const status = useStatusChange(onChanged)

  if (home.overdue.length === 0 && home.next.length === 0) {
    return (
      <p className="empty">
        Nothing is waiting for you. What has to happen goes into the <Link to="/plan">plan</Link>; single things to do go
        under <Link to="/tasks">tasks</Link>.
      </p>
    )
  }
  return (
    <div>
      {status.failed && (
        <p role="alert" className="alert">
          That change was not saved. Try again.
        </p>
      )}
      <Entries title="Overdue" entries={home.overdue} categories={categories} pending={status.pending} shown={status.shown} onStatus={status.change} />
      <Entries title="Next" entries={home.next} categories={categories} pending={status.pending} shown={status.shown} onStatus={status.change} />
      <p className="see-all">
        <Link to="/plan">The whole plan</Link>
        <Link to="/tasks">All tasks</Link>
      </p>
    </div>
  )
}

interface EntriesProps {
  title: string
  entries: Entry[]
  categories: Category[]
  pending: PendingStatus | null
  shown: (entry: Entry) => Status
  onStatus: (entry: Entry, status: Status) => void
}

/** A short list of plan items and tasks. Each opens for editing and can be ticked off right here. */
function Entries({ title, entries, categories, pending, shown, onStatus }: EntriesProps) {
  if (entries.length === 0) {
    return null
  }
  return (
    <section className="group" aria-label={title}>
      <h2>{title}</h2>
      <ul className="entries">
        {entries.map((entry) => (
          <TaskRow
            key={entry.id}
            task={entry}
            categories={categories}
            status={shown(entry)}
            busy={pending?.id === entry.id}
            back="/"
            onStatus={onStatus}
          />
        ))}
      </ul>
    </section>
  )
}

/** While the day is not fixed there is nothing to count down to: the page asks for the day instead. */
function AskForTheDay({ onSaved }: { onSaved: () => void }) {
  const wedding = useCurrentWedding()
  const update = useWeddingUpdate()
  const [date, setDate] = useState('')
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)

  function save(event: FormEvent) {
    event.preventDefault()
    if (date === '') {
      setProblem('Choose the day first.')
      return
    }
    setBusy(true)
    setProblem(null)
    changeWedding({ date, currency: wedding.currency, timeZone: wedding.timeZone })
      .then((changed) => {
        update(changed)
        onSaved()
      })
      .catch((error: unknown) => {
        setProblem((error instanceof ApiError ? error.fieldMessage('date') : undefined) ?? 'The day could not be saved. Try again.')
        setBusy(false)
      })
  }

  return (
    <form className="ask-day stack" onSubmit={save} noValidate>
      <p className="muted">The day is not fixed yet. Once it is, this page counts down to it.</p>
      <Field label="Wedding day" error={problem ?? undefined}>
        {(control) => <input {...control} type="date" name="date" value={date} onChange={(event) => setDate(event.target.value)} />}
      </Field>
      <div>
        <button type="submit" className="button" disabled={busy}>
          {busy ? 'Saving…' : 'Save the day'}
        </button>
      </div>
    </form>
  )
}
