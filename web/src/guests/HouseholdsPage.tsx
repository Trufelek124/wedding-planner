import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link, useLocation } from 'react-router'
import { ApiError } from '../api/client.ts'
import { useLoaded } from '../planning/useLoaded.ts'
import { Field } from '../ui/Field.tsx'
import { PageRetry, PageWaiting } from '../ui/Screen.tsx'
import { addHousehold, deleteHousehold, listHouseholds, renameHousehold, type Household } from './guestsApi.ts'

function nameProblem(error: unknown, otherwise: string): string {
  return (error instanceof ApiError ? error.fieldMessage('name') : undefined) ?? otherwise
}

function howMany(guests: number): string {
  return guests === 0 ? 'Nobody in it yet' : guests === 1 ? '1 guest' : `${guests} guests`
}

/** The households: people who are invited together. Add one, rename one, remove one. */
export function HouseholdsPage() {
  const households = useLoaded('households', listHouseholds)
  const location = useLocation()
  const state: unknown = location.state
  const back = typeof state === 'object' && state !== null && 'back' in state && typeof state.back === 'string' ? state.back : '/guests'

  const [name, setName] = useState('')
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const [said, setSaid] = useState('')
  const saidLine = useRef<HTMLParagraphElement>(null)

  if (households.state.status === 'failed') {
    return <PageRetry message="The households could not be loaded. Try again." onRetry={households.retry} />
  }
  if (households.state.status === 'loading') {
    return <PageWaiting what="Loading…" />
  }

  function add(event: FormEvent) {
    event.preventDefault()
    if (name.trim() === '') {
      setProblem('Enter a name.')
      return
    }
    setBusy(true)
    setProblem(null)
    addHousehold(name.trim())
      .then((added) => {
        setName('')
        setSaid(`“${added.name}” was added.`)
        households.reload()
      })
      .catch((error: unknown) => setProblem(nameProblem(error, 'The household could not be added. Try again.')))
      .finally(() => setBusy(false))
  }

  return (
    <div className="form-page">
      <h1>Households</h1>
      <p className="muted">People who are invited together: a family, a couple. A guest is put into one on the guest’s own page.</p>

      <p role="status" ref={saidLine} tabIndex={-1} className={said === '' ? 'visually-hidden' : 'said'}>
        {said}
      </p>

      {households.state.data.length === 0 ? (
        <p className="empty">No households yet. Add the first one below.</p>
      ) : (
        <ul className="boards">
          {households.state.data.map((household) => (
            <HouseholdRow
              key={household.id}
              household={household}
              onRenamed={(renamed) => {
                setSaid(`“${household.name}” is now “${renamed.name}”.`)
                households.reload()
              }}
              onRemoved={() => {
                // A removed row takes the keyboard's place with it: the line that says so takes it over.
                setSaid(`“${household.name}” was removed.`)
                households.reload()
                saidLine.current?.focus()
              }}
            />
          ))}
        </ul>
      )}

      <form className="stack" onSubmit={add} noValidate>
        <Field label="New household" error={problem ?? undefined}>
          {(control) => <input {...control} name="name" maxLength={120} autoComplete="off" value={name} onChange={(event) => setName(event.target.value)} />}
        </Field>
        <div>
          <button type="submit" className="button" disabled={busy}>
            Add household
          </button>
        </div>
      </form>

      <p>
        <Link to={back}>Back to the guest list</Link>
      </p>
    </div>
  )
}

type Doing = 'nothing' | 'renaming' | 'removing'

function HouseholdRow({ household, onRenamed, onRemoved }: { household: Household; onRenamed: (household: Household) => void; onRemoved: () => void }) {
  const [doing, setDoing] = useState<Doing>('nothing')
  const [name, setName] = useState(household.name)
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)

  const renameButton = useRef<HTMLButtonElement>(null)
  const removeButton = useRef<HTMLButtonElement>(null)
  const nameInput = useRef<HTMLInputElement>(null)
  const question = useRef<HTMLDivElement>(null)
  const was = useRef<Doing>('nothing')

  // The control that was pressed is replaced by what it opened, and comes back when that closes:
  // the keyboard goes along both ways.
  useEffect(() => {
    if (doing === 'renaming') {
      nameInput.current?.focus()
      nameInput.current?.select()
    } else if (doing === 'removing') {
      question.current?.focus()
    } else if (was.current === 'renaming') {
      renameButton.current?.focus()
    } else if (was.current === 'removing') {
      removeButton.current?.focus()
    }
    was.current = doing
  }, [doing])

  function close() {
    setDoing('nothing')
    setProblem(null)
    setName(household.name)
  }

  function rename(event: FormEvent) {
    event.preventDefault()
    if (name.trim() === household.name) {
      close()
      return
    }
    setBusy(true)
    setProblem(null)
    renameHousehold(household.id, name.trim())
      .then((renamed) => {
        setDoing('nothing')
        onRenamed(renamed)
      })
      .catch((error: unknown) => setProblem(nameProblem(error, 'The household could not be renamed. Try again.')))
      .finally(() => setBusy(false))
  }

  function remove() {
    setBusy(true)
    setProblem(null)
    deleteHousehold(household.id)
      .then(onRemoved)
      .catch(() => {
        setProblem('The household could not be removed. Try again.')
        setBusy(false)
      })
  }

  return (
    <li className="board-row">
      {doing === 'renaming' ? (
        <form className="stack-tight field" onSubmit={rename} noValidate>
          <label htmlFor={`household-${household.id}`}>New name for “{household.name}”</label>
          <input
            id={`household-${household.id}`}
            ref={nameInput}
            maxLength={120}
            autoComplete="off"
            value={name}
            aria-invalid={problem !== null}
            aria-describedby={problem === null ? undefined : `household-${household.id}-error`}
            onChange={(event) => setName(event.target.value)}
          />
          {problem !== null && (
            <p className="error" id={`household-${household.id}-error`}>
              {problem}
            </p>
          )}
          <div className="actions">
            <button type="submit" className="button" disabled={busy}>
              Save name
            </button>
            <button type="button" className="button button-quiet" disabled={busy} onClick={close}>
              Cancel
            </button>
          </div>
        </form>
      ) : (
        <>
          <div className="board-name">
            <span>{household.name}</span>
            <span className="hint">{howMany(household.guests)}</span>
          </div>
          {doing === 'nothing' && (
            <div className="actions">
              <button type="button" className="button button-quiet" ref={renameButton} aria-label={`Rename “${household.name}”`} onClick={() => setDoing('renaming')}>
                Rename
              </button>
              <button type="button" className="button button-danger" ref={removeButton} aria-label={`Remove “${household.name}”`} onClick={() => setDoing('removing')}>
                Remove
              </button>
            </div>
          )}
        </>
      )}
      {doing === 'removing' && (
        <div className="stack board-question" role="group" aria-label={`Remove “${household.name}”`} ref={question} tabIndex={-1}>
          <p>
            Remove “{household.name}”?{' '}
            {household.guests === 0
              ? 'Nobody is in it.'
              : household.guests === 1
                ? 'Its guest stays on the list, on their own.'
                : `Its ${household.guests} guests stay on the list, on their own.`}
          </p>
          <div className="actions">
            <button type="button" className="button button-danger" disabled={busy} onClick={remove}>
              Yes, remove it
            </button>
            <button type="button" className="button button-quiet" disabled={busy} onClick={close}>
              Do not remove
            </button>
          </div>
          {problem !== null && (
            <p role="alert" className="alert">
              {problem}
            </p>
          )}
        </div>
      )}
    </li>
  )
}
