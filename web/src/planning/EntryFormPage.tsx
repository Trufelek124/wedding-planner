import { useId, useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router'
import { ApiError } from '../api/client.ts'
import { Field } from '../ui/Field.tsx'
import { PageRetry, PageWaiting } from '../ui/Screen.tsx'
import { useCurrentWedding } from '../wedding/WeddingContext.ts'
import { KIND_LABEL, PRIORITY_LABEL, priorityInWords, STATUS_LABEL } from './labels.ts'
import {
  changeEntry,
  convertEntry,
  createEntry,
  deleteEntry,
  getEntry,
  listCategories,
  listEntries,
  type Category,
  type Entry,
  type Kind,
  type Priority,
  type Status,
} from './planningApi.ts'
import { useLoaded } from './useLoaded.ts'
import { WaitsFor } from './WaitsFor.tsx'

const STATUSES: Status[] = ['NOT_STARTED', 'IN_PROGRESS', 'DONE', 'DROPPED']
const PRIORITIES: Priority[] = ['CRITICAL', 'HIGH', 'NORMAL', 'LOW']

/** The page for adding an entry (`/entries/new?kind=…`) and for changing one (`/entries/:id`). */
export function EntryFormPage() {
  const { id } = useParams()
  const [params] = useSearchParams()
  const kindAsked: Kind = params.get('kind') === 'PLAN_ITEM' ? 'PLAN_ITEM' : 'TASK'

  const entry = useLoaded(id ?? 'new', (signal) => (id === undefined ? Promise.resolve(null) : getEntry(id, signal)))
  const categories = useLoaded('categories', listCategories)
  const planItems = useLoaded('plan-items', (signal) => listEntries({ kind: 'PLAN_ITEM', sort: 'TITLE' }, signal))

  if (entry.state.status === 'failed' || categories.state.status === 'failed' || planItems.state.status === 'failed') {
    return (
      <PageRetry
        message="This could not be loaded. It may have been deleted. Try again, or go back to the list."
        onRetry={() => {
          entry.retry()
          categories.retry()
          planItems.retry()
        }}
      />
    )
  }
  if (entry.state.status === 'loading' || categories.state.status === 'loading' || planItems.state.status === 'loading') {
    return <PageWaiting what="Loading…" />
  }

  const existing = entry.state.data
  return (
    <EntryForm
      // A different entry, or the same one loaded afresh, starts the form again from what the server holds.
      key={existing === null ? `new-${kindAsked}` : `${existing.id}-${existing.version}-${existing.kind}`}
      existing={existing}
      kind={existing?.kind ?? kindAsked}
      parentAsked={params.get('parent')}
      categories={categories.state.data}
      planItems={planItems.state.data}
      onLoaded={entry.set}
      onReload={entry.retry}
    />
  )
}

interface EntryFormProps {
  existing: Entry | null
  kind: Kind
  parentAsked: string | null
  categories: Category[]
  planItems: Entry[]
  onLoaded: (entry: Entry) => void
  onReload: () => void
}

type Asking = null | 'delete'

function cameFrom(state: unknown): string | null {
  return typeof state === 'object' && state !== null && 'back' in state && typeof state.back === 'string' ? state.back : null
}

function EntryForm({ existing, kind, parentAsked, categories, planItems, onLoaded, onReload }: EntryFormProps) {
  const wedding = useCurrentWedding()
  const navigate = useNavigate()
  const location = useLocation()
  // Back to the page the form was opened from, when that page said which one it is.
  const back = cameFrom(location.state) ?? (kind === 'PLAN_ITEM' ? '/plan' : '/tasks')

  const datesHint = useId()
  const [title, setTitle] = useState(existing?.title ?? '')
  const [notes, setNotes] = useState(existing?.notes ?? '')
  const [categoryId, setCategoryId] = useState(existing?.categoryId ?? '')
  const [startDate, setStartDate] = useState(existing?.startDate ?? '')
  const [dueDate, setDueDate] = useState(existing?.dueDate ?? '')
  const [status, setStatus] = useState<Status>(existing?.status ?? 'NOT_STARTED')
  const [assigneeIds, setAssigneeIds] = useState<string[]>(existing?.assigneeIds ?? [])
  const [byHand, setByHand] = useState(existing?.priorityMode === 'MANUAL')
  const decidedHint = useId()
  // What the planner makes of the entry as it was saved; it works the level out again at every save.
  const decidedNow = existing?.priorityMode === 'AUTO' && !byHand ? priorityInWords(existing) : null
  const [priority, setPriority] = useState<Priority>(existing?.priority ?? 'NORMAL')
  const [parentId, setParentId] = useState(existing?.parentId ?? parentAsked ?? '')

  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState<ApiError | 'unreachable' | null>(null)
  const [asking, setAsking] = useState<Asking>(null)

  function fieldError(field: string): string | undefined {
    return failure instanceof ApiError ? failure.fieldMessage(field) : undefined
  }

  function fields() {
    return {
      title: title.trim(),
      notes: notes.trim(),
      categoryId: categoryId === '' ? null : categoryId,
      startDate: kind === 'TASK' || startDate === '' ? null : startDate,
      dueDate: dueDate === '' ? null : dueDate,
      status,
      assigneeIds,
      priorityMode: byHand ? ('MANUAL' as const) : ('AUTO' as const),
      priority: byHand ? priority : null,
      parentId: kind === 'PLAN_ITEM' || parentId === '' ? null : parentId,
      // The money of a payment is said again as it is: a save replaces the whole entry. A paid date belongs
      // to a payment that is done; for one that is done only now, the server takes today.
      amount: existing?.amount ?? null,
      paymentKind: existing?.paymentKind ?? null,
      paidOn: existing?.amount != null && status === 'DONE' ? existing.paidOn : null,
    }
  }

  /** Runs a change against the server; on success `then` decides where to go. */
  async function attempt(action: () => Promise<void>) {
    setBusy(true)
    setFailure(null)
    try {
      await action()
    } catch (error) {
      setFailure(error instanceof ApiError ? error : 'unreachable')
      setBusy(false)
    }
  }

  function save(event: FormEvent) {
    event.preventDefault()
    void attempt(async () => {
      if (existing === null) {
        await createEntry({ kind, ...fields() })
      } else {
        await changeEntry(existing.id, { version: existing.version, ...fields() })
      }
      await navigate(back)
    })
  }

  function remove(tasks?: 'KEEP' | 'DELETE') {
    if (existing === null) return
    void attempt(async () => {
      await deleteEntry(existing.id, tasks)
      await navigate(back)
    })
  }

  function convert() {
    if (existing === null) return
    void attempt(async () => {
      onLoaded(await convertEntry(existing.id, kind === 'TASK' ? 'PLAN_ITEM' : 'TASK'))
    })
  }

  const changedMeanwhile = failure instanceof ApiError && failure.is('urn:wedding-planner:problem:changed-meanwhile')
  const general =
    failure === 'unreachable'
      ? 'The server cannot be reached. Check your connection and try again.'
      : failure !== null && !changedMeanwhile && (failure.problem.fieldProblems ?? []).length === 0
        ? (failure.problem.detail ?? 'That did not work. Try again.')
        : null

  const what = KIND_LABEL[kind]
  const taskCount = existing?.taskCount ?? 0

  return (
    <div className="form-page">
      <h1>{existing === null ? `Add a ${what}` : `Change this ${what}`}</h1>

      {changedMeanwhile && (
        <div role="alert" className="alert stack">
          <p>Someone else changed this in the meantime, so your change was not saved.</p>
          <button type="button" className="button button-quiet" onClick={onReload}>
            Load the latest version
          </button>
        </div>
      )}
      {general !== null && (
        <p role="alert" className="alert">
          {general}
        </p>
      )}

      <form className="stack" onSubmit={save} noValidate>
        <Field label="Title" error={fieldError('title')}>
          {(control) => (
            <input {...control} name="title" maxLength={200} required value={title} onChange={(event) => setTitle(event.target.value)} />
          )}
        </Field>

        {kind === 'PLAN_ITEM' ? (
          <div role="group" aria-label="Dates" aria-describedby={datesHint} className="stack-tight">
            <div className="two-columns">
              <Field label="Starts" optional error={fieldError('startDate')}>
                {(control) => (
                  <input {...control} type="date" name="startDate" value={startDate} onChange={(event) => setStartDate(event.target.value)} />
                )}
              </Field>
              <Field label="Ends" optional error={fieldError('dueDate')}>
                {(control) => (
                  <input {...control} type="date" name="dueDate" value={dueDate} onChange={(event) => setDueDate(event.target.value)} />
                )}
              </Field>
            </div>
            <p className="hint" id={datesHint}>
              Without dates it stays under “Not scheduled”.
            </p>
          </div>
        ) : (
          <Field label="Due" optional error={fieldError('dueDate')}>
            {(control) => (
              <input {...control} type="date" name="dueDate" value={dueDate} onChange={(event) => setDueDate(event.target.value)} />
            )}
          </Field>
        )}

        <fieldset className="choice">
          <legend>Who does it</legend>
          {wedding.members.map((member) => (
            <label key={member.id}>
              <input
                type="checkbox"
                checked={assigneeIds.includes(member.id)}
                onChange={(event) =>
                  setAssigneeIds(event.target.checked ? [...assigneeIds, member.id] : assigneeIds.filter((other) => other !== member.id))
                }
              />
              {member.displayName}
            </label>
          ))}
          {fieldError('assigneeIds') && <p className="error">{fieldError('assigneeIds')}</p>}
        </fieldset>

        <div className="two-columns">
          <Field label="Category" optional error={fieldError('categoryId')}>
            {(control) => (
              <select {...control} name="categoryId" value={categoryId} onChange={(event) => setCategoryId(event.target.value)}>
                <option value="">None</option>
                {categories.map((category) => (
                  <option key={category.id} value={category.id}>
                    {category.name}
                  </option>
                ))}
              </select>
            )}
          </Field>
          <Field label="Status">
            {(control) => (
              <select {...control} name="status" value={status} onChange={(event) => setStatus(event.target.value as Status)}>
                {STATUSES.map((choice) => (
                  <option key={choice} value={choice}>
                    {STATUS_LABEL[choice]}
                  </option>
                ))}
              </select>
            )}
          </Field>
        </div>

        {kind === 'TASK' && (
          <Field label="Part of" optional hint="The plan item this task belongs to." error={fieldError('parentId')}>
            {(control) => (
              <select {...control} name="parentId" value={parentId} onChange={(event) => setParentId(event.target.value)}>
                <option value="">No plan item</option>
                {planItems.map((item) => (
                  <option key={item.id} value={item.id}>
                    {item.title}
                  </option>
                ))}
              </select>
            )}
          </Field>
        )}

        <fieldset className="choice">
          <legend>Priority</legend>
          <label>
            <input
              type="radio"
              name="priorityMode"
              checked={!byHand}
              aria-describedby={decidedNow === null ? undefined : decidedHint}
              onChange={() => setByHand(false)}
            />
            Let the planner decide, from the dates
          </label>
          {decidedNow !== null && (
            <p className="hint choice-hint" id={decidedHint}>
              Right now: {decidedNow}.
            </p>
          )}
          <label>
            <input type="radio" name="priorityMode" checked={byHand} onChange={() => setByHand(true)} />
            Set it myself
          </label>
          {byHand && (
            <Field label="Priority level" error={fieldError('priority')}>
              {(control) => (
                <select {...control} name="priority" value={priority} onChange={(event) => setPriority(event.target.value as Priority)}>
                  {PRIORITIES.map((level) => (
                    <option key={level} value={level}>
                      {PRIORITY_LABEL[level]}
                    </option>
                  ))}
                </select>
              )}
            </Field>
          )}
        </fieldset>

        <Field label="Notes" optional error={fieldError('notes')}>
          {(control) => (
            <textarea {...control} name="notes" rows={4} maxLength={10000} value={notes} onChange={(event) => setNotes(event.target.value)} />
          )}
        </Field>

        <div className="actions">
          <button type="submit" className="button" disabled={busy}>
            {busy ? 'Saving…' : 'Save'}
          </button>
          <Link className="button button-quiet" to={back}>
            Cancel
          </Link>
        </div>
      </form>

      {existing?.kind === 'PLAN_ITEM' && <WaitsFor entry={existing} planItems={planItems} />}

      {existing !== null && (
        <section className="danger-zone stack" aria-label="More">
          <h2>More</h2>
          {kind === 'PLAN_ITEM' && taskCount > 0 ? (
            <p className="muted">A plan item that has tasks cannot be turned into a task.</p>
          ) : (
            <div>
              <button type="button" className="button button-quiet" disabled={busy} onClick={convert}>
                {kind === 'TASK' ? 'Turn into a plan item' : 'Turn into a task'}
              </button>
            </div>
          )}
          {asking !== 'delete' ? (
            <div>
              <button type="button" className="button button-danger" disabled={busy} onClick={() => setAsking('delete')}>
                Delete this {what}
              </button>
            </div>
          ) : taskCount > 0 ? (
            <div className="stack" role="group" aria-label="Delete">
              <p>
                This plan item has {taskCount === 1 ? '1 task' : `${taskCount} tasks`}. What should happen to{' '}
                {taskCount === 1 ? 'it' : 'them'}?
              </p>
              <div className="actions">
                <button type="button" className="button button-danger" disabled={busy} onClick={() => remove('KEEP')}>
                  Delete it, keep the {taskCount === 1 ? 'task' : 'tasks'}
                </button>
                <button type="button" className="button button-danger" disabled={busy} onClick={() => remove('DELETE')}>
                  Delete it and the {taskCount === 1 ? 'task' : 'tasks'}
                </button>
                <button type="button" className="button button-quiet" disabled={busy} onClick={() => setAsking(null)}>
                  Do not delete
                </button>
              </div>
            </div>
          ) : (
            <div className="stack" role="group" aria-label="Delete">
              <p>Delete “{existing.title}”? This cannot be undone.</p>
              <div className="actions">
                <button type="button" className="button button-danger" disabled={busy} onClick={() => remove()}>
                  Yes, delete it
                </button>
                <button type="button" className="button button-quiet" disabled={busy} onClick={() => setAsking(null)}>
                  Do not delete
                </button>
              </div>
            </div>
          )}
        </section>
      )}
    </div>
  )
}
