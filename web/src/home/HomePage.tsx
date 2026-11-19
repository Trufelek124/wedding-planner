import { useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { ApiError } from '../api/client.ts'
import { Meter } from '../budget/Meter.tsx'
import { money } from '../money/money.ts'
import { shortDate } from '../planning/labels.ts'
import { listCategories, type Category, type Entry, type Status } from '../planning/planningApi.ts'
import { TaskRow } from '../planning/TaskRow.tsx'
import { useLoaded } from '../planning/useLoaded.ts'
import { useStatusChange, type PendingStatus } from '../planning/useStatusChange.ts'
import { Field } from '../ui/Field.tsx'
import { PageRetry, PageWaiting } from '../ui/Screen.tsx'
import { useWaiting, waitingInWords } from '../suggestions/WaitingContext.ts'
import { countdown, longDate } from '../wedding/dates.ts'
import { useCurrentWedding, useWeddingUpdate } from '../wedding/WeddingContext.ts'
import { changeWedding } from '../wedding/weddingApi.ts'
import { fetchMail, needsConnecting } from '../mail/mailApi.ts'
import { fetchHome, type Home } from './homeApi.ts'

/** Where a signed-in owner lands: how long until the wedding, what is overdue, and what to turn to next. */
export function HomePage() {
  const wedding = useCurrentWedding()
  const names = wedding.members.map((member) => member.displayName).join(' & ')
  const home = useLoaded('home', fetchHome)
  const categories = useLoaded('categories', listCategories)
  const waiting = useWaiting()
  // Only to know whether to ask for the e-mail; if it cannot be read, nothing is asked.
  const mail = useLoaded('mail', fetchMail)
  const connectMail = mail.state.status === 'ready' && needsConnecting(mail.state.data)
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

      {waiting.count !== null && waiting.count > 0 && (
        <p className="home-suggestions">
          <Link to="/suggestions">{waitingInWords(waiting.count)}</Link>
        </p>
      )}

      {connectMail && mail.state.status === 'ready' && (
        <p className="home-suggestions">
          <Link to="/mail">
            {mail.state.data.yours.state === 'NEEDS_RECONNECTING'
              ? 'Your e-mail has to be connected again'
              : 'Connect your e-mail, so that the planner can find what the wedding brings in'}
          </Link>
        </p>
      )}

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

/** Everything overdue, the five entries to turn to next, and the money: the budget at a glance and what to pay next. */
function WhatIsNext({ home, categories, onChanged }: { home: Home; categories: Category[]; onChanged: () => void }) {
  const status = useStatusChange(onChanged)
  const lists = { categories, pending: status.pending, shown: status.shown, onStatus: status.change }
  const nothingToDo = home.overdue.length === 0 && home.next.length === 0

  return (
    <div>
      {status.failed && (
        <p role="alert" className="alert">
          That change was not saved. Try again.
        </p>
      )}
      {nothingToDo ? (
        <p className="empty">
          No task is waiting for you. What has to happen goes into the <Link to="/plan">plan</Link>; single things to do go
          under <Link to="/tasks">tasks</Link>.
        </p>
      ) : (
        <>
          <Entries title="Overdue" entries={home.overdue} {...lists} />
          <Entries title="Next" entries={home.next} {...lists} />
          <p className="see-all">
            <Link to="/plan">The whole plan</Link>
            <Link to="/tasks">All tasks</Link>
          </p>
        </>
      )}
      <BudgetAtAGlance budget={home.budget} />
      <NextPayments payments={home.nextPayments} pending={status.pending} shown={status.shown} onStatus={status.change} />
    </div>
  )
}

interface NextPaymentsProps {
  payments: Entry[]
  pending: PendingStatus | null
  shown: (entry: Entry) => Status
  onStatus: (entry: Entry, status: Status) => void
}

/**
 * What is to be paid next, as short lines: the same entries may stand in full under "Next" already,
 * so here each says only what it costs and when.
 */
function NextPayments({ payments, pending, shown, onStatus }: NextPaymentsProps) {
  const { currency } = useCurrentWedding()
  if (payments.length === 0) {
    return null
  }
  return (
    <section className="group" aria-labelledby="home-payments">
      <h2 id="home-payments">Next payments</h2>
      <ul className="entries">
        {payments.map((payment) => (
          <li key={payment.id} className="entry entry-short" data-done={shown(payment) === 'DONE'}>
            <input
              type="checkbox"
              className="tick"
              aria-label={`Paid: ${payment.title}`}
              checked={shown(payment) === 'DONE'}
              disabled={pending?.id === payment.id}
              onChange={(event) => onStatus(payment, event.target.checked ? 'DONE' : 'NOT_STARTED')}
            />
            <div className="entry-body">
              <Link className="entry-title" to={`/entries/${payment.id}`} state={{ back: '/' }}>
                {payment.title}
              </Link>
              <p className="entry-meta">
                <span className="entry-money">{payment.amount === null ? '' : money(payment.amount, currency)}</span>
                <span>{payment.dueDate === null ? 'no date' : `due ${shortDate(payment.dueDate)}`}</span>
              </p>
            </div>
          </li>
        ))}
      </ul>
    </section>
  )
}

/** The four sums in two lines, and the way to the page that has the rest. Nothing at all while there is no money in the planner. */
function BudgetAtAGlance({ budget }: { budget: Home['budget'] }) {
  const { currency } = useCurrentWedding()
  if (budget.total === null && budget.planned === 0 && budget.committed === 0) {
    return null
  }
  const limit = budget.total ?? (budget.planned > 0 ? budget.planned : null)
  const limitName = budget.total === null ? 'planned' : 'budget'
  return (
    <section className="group glance" aria-labelledby="home-budget">
      <h2 id="home-budget">Budget</h2>
      <p>
        <strong>{money(budget.paid, currency)}</strong> paid, <strong>{money(budget.committed - budget.paid, currency)}</strong> still
        to pay
        {limit !== null && (
          <>
            {' '}
            — {budget.committed > limit
              ? `${money(budget.committed - limit, currency)} more than the ${money(limit, currency)} ${limitName}`
              : `${money(limit - budget.committed, currency)} of the ${money(limit, currency)} ${limitName} not committed`}
          </>
        )}
        .
      </p>
      {budget.committed > 0 && (
        <Meter limit={limit} committed={budget.committed} paid={budget.paid} currency={currency} about="The whole budget" limitName={limitName} />
      )}
      <p className="see-all">
        <Link to="/budget">The budget</Link>
      </p>
    </section>
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
