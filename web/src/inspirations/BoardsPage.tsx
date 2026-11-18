import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link, useLocation } from 'react-router'
import { ApiError } from '../api/client.ts'
import { useLoaded } from '../planning/useLoaded.ts'
import { Field } from '../ui/Field.tsx'
import { PageRetry, PageWaiting } from '../ui/Screen.tsx'
import { addBoard, deleteBoard, listBoards, renameBoard, type Board } from './inspirationsApi.ts'

function nameProblem(error: unknown, otherwise: string): string {
  return (error instanceof ApiError ? error.fieldMessage('name') : undefined) ?? otherwise
}

function howMany(count: number): string {
  return count === 1 ? '1 inspiration' : `${count} inspirations`
}

/** The list of boards: add one, rename one, remove one. */
export function BoardsPage() {
  const boards = useLoaded('boards', listBoards)
  const location = useLocation()
  const state: unknown = location.state
  const back =
    typeof state === 'object' && state !== null && 'back' in state && typeof state.back === 'string' ? state.back : '/inspirations'

  const [name, setName] = useState('')
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const [said, setSaid] = useState('')
  const saidLine = useRef<HTMLParagraphElement>(null)

  if (boards.state.status === 'failed') {
    return <PageRetry message="The boards could not be loaded. Try again." onRetry={boards.retry} />
  }
  if (boards.state.status === 'loading') {
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
    addBoard(name.trim())
      .then((added) => {
        setName('')
        setSaid(`“${added.name}” was added.`)
        boards.reload()
      })
      .catch((error: unknown) => setProblem(nameProblem(error, 'The board could not be added. Try again.')))
      .finally(() => setBusy(false))
  }

  /** A removed row takes the keyboard's place with it: the line that says so takes it over. */
  function removed(board: Board) {
    setSaid(`“${board.name}” was removed.`)
    boards.reload()
    saidLine.current?.focus()
  }

  return (
    <div className="form-page">
      <h1>Boards</h1>
      <p className="muted">The headings your inspirations are collected under.</p>

      <p role="status" ref={saidLine} tabIndex={-1} className={said === '' ? 'visually-hidden' : 'said'}>
        {said}
      </p>

      {boards.state.data.length === 0 ? (
        <p className="empty">No boards yet. Add the first one below.</p>
      ) : (
        <ul className="boards">
          {boards.state.data.map((board) => (
            <BoardRow
              key={board.id}
              board={board}
              onRenamed={(renamed) => {
                setSaid(`“${board.name}” is now “${renamed.name}”.`)
                boards.reload()
              }}
              onRemoved={() => removed(board)}
            />
          ))}
        </ul>
      )}

      <form className="stack" onSubmit={add} noValidate>
        <Field label="New board" error={problem ?? undefined}>
          {(control) => <input {...control} name="name" maxLength={60} value={name} onChange={(event) => setName(event.target.value)} />}
        </Field>
        <div>
          <button type="submit" className="button" disabled={busy}>
            Add board
          </button>
        </div>
      </form>

      <p>
        <Link to={back}>Back to the inspirations</Link>
      </p>
    </div>
  )
}

interface BoardRowProps {
  board: Board
  onRenamed: (board: Board) => void
  onRemoved: () => void
}

type Doing = 'nothing' | 'renaming' | 'removing'

function BoardRow({ board, onRenamed, onRemoved }: BoardRowProps) {
  const [doing, setDoing] = useState<Doing>('nothing')
  const [name, setName] = useState(board.name)
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  /** True once the server said the board has inspirations although this page counted none. */
  const [hasSome, setHasSome] = useState(false)

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
    setName(board.name)
  }

  function rename(event: FormEvent) {
    event.preventDefault()
    if (name.trim() === board.name) {
      close()
      return
    }
    setBusy(true)
    setProblem(null)
    renameBoard(board.id, name.trim())
      .then((renamed) => {
        setDoing('nothing')
        onRenamed(renamed)
      })
      .catch((error: unknown) => setProblem(nameProblem(error, 'The board could not be renamed. Try again.')))
      .finally(() => setBusy(false))
  }

  function remove(inspirations?: 'KEEP' | 'DELETE') {
    setBusy(true)
    setProblem(null)
    deleteBoard(board.id, inspirations)
      .then(onRemoved)
      .catch((error: unknown) => {
        if (error instanceof ApiError && error.is('urn:wedding-planner:problem:board-has-inspirations')) {
          setHasSome(true)
        } else {
          setProblem('The board could not be removed. Try again.')
        }
        setBusy(false)
      })
  }

  const count = board.inspirationCount

  return (
    <li className="board-row">
      {doing === 'renaming' ? (
        <form className="stack-tight field" onSubmit={rename} noValidate>
          <label htmlFor={`board-${board.id}`}>New name for “{board.name}”</label>
          <input
            id={`board-${board.id}`}
            ref={nameInput}
            maxLength={60}
            value={name}
            aria-invalid={problem !== null}
            aria-describedby={problem === null ? undefined : `board-${board.id}-error`}
            onChange={(event) => setName(event.target.value)}
          />
          {problem !== null && (
            <p className="error" id={`board-${board.id}-error`}>
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
            <span>{board.name}</span>
            <span className="hint">{count === 0 ? 'Nothing on it' : howMany(count)}</span>
          </div>
          {doing === 'nothing' && (
            <div className="actions">
              <button
                type="button"
                className="button button-quiet"
                ref={renameButton}
                aria-label={`Rename “${board.name}”`}
                onClick={() => setDoing('renaming')}
              >
                Rename
              </button>
              <button
                type="button"
                className="button button-danger"
                ref={removeButton}
                aria-label={`Remove “${board.name}”`}
                onClick={() => setDoing('removing')}
              >
                Remove
              </button>
            </div>
          )}
        </>
      )}
      {doing === 'removing' && (
        <div className="stack board-question" role="group" aria-label={`Remove “${board.name}”`} ref={question} tabIndex={-1}>
          {count > 0 || hasSome ? (
            <>
              <p>
                {count > 0 ? `“${board.name}” has ${howMany(count)}.` : `“${board.name}” has inspirations by now.`} What should
                happen to {count === 1 ? 'it' : 'them'}?
              </p>
              <div className="actions">
                <button type="button" className="button button-danger" disabled={busy} onClick={() => remove('KEEP')}>
                  Remove the board, keep the {count === 1 ? 'inspiration' : 'inspirations'}
                </button>
                <button type="button" className="button button-danger" disabled={busy} onClick={() => remove('DELETE')}>
                  Remove the board and the {count === 1 ? 'inspiration' : 'inspirations'}
                </button>
                <button type="button" className="button button-quiet" disabled={busy} onClick={close}>
                  Do not remove
                </button>
              </div>
              <p className="hint">Kept, {count === 1 ? 'it stays' : 'they stay'} in the planner, on no board.</p>
            </>
          ) : (
            <>
              <p>Remove “{board.name}”? Nothing is on it.</p>
              <div className="actions">
                <button type="button" className="button button-danger" disabled={busy} onClick={() => remove()}>
                  Yes, remove it
                </button>
                <button type="button" className="button button-quiet" disabled={busy} onClick={close}>
                  Do not remove
                </button>
              </div>
            </>
          )}
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
