import { useCallback, useEffect, useId, useRef, useState, type FormEvent } from 'react'
import { Link, useLocation, useSearchParams } from 'react-router'
import { ApiError } from '../api/client.ts'
import { newId } from '../api/ids.ts'
import { listEntries } from '../planning/planningApi.ts'
import { useLoaded } from '../planning/useLoaded.ts'
import { PageRetry, PageWaiting } from '../ui/Screen.tsx'
import { Canvas } from './canvas/Canvas.tsx'
import { InspirationCard } from './InspirationCard.tsx'
import { InspirationViews } from './InspirationViews.tsx'
import {
  addPictures,
  createInspiration,
  deleteInspiration,
  listBoards,
  listInspirations,
  listTags,
  uploadProblem,
  whyNot,
  type Inspiration,
  type InspirationFilter,
} from './inspirationsApi.ts'
import { PictureChooser } from './PictureChooser.tsx'
import { usePictureDrop } from './usePictureDrop.ts'

/** How often, and how many times, the board looks again while a preview is being fetched. */
const LOOK_AGAIN_MS = 3000
const LOOK_AGAIN_TIMES = 10

/**
 * Ideas the couple keeps, narrowed down by board and tag: as a grid of cards, or as a canvas where each card
 * lies where it was put. The head, the quick ways to add and the filters are the same in both.
 */
export function InspirationsPage({ view = 'grid' }: { view?: 'grid' | 'canvas' }) {
  const { search } = useLocation()
  const [params, setParams] = useSearchParams()
  const board = params.get('board') ?? ''
  const tag = params.get('tag') ?? ''

  const query: InspirationFilter = {}
  if (board === 'none') {
    query.withoutBoard = true
  } else if (board !== '') {
    query.boardId = board
  }
  if (tag !== '') {
    query.tag = tag
  }

  const inspirations = useLoaded(JSON.stringify(query), (signal) => listInspirations(query, signal))
  const boards = useLoaded('boards', listBoards)
  const tags = useLoaded('tags', listTags)
  const planItems = useLoaded('plan-items', (signal) => listEntries({ kind: 'PLAN_ITEM', sort: 'TITLE' }, signal))

  const linkId = useId()
  const [link, setLink] = useState('')
  const [busy, setBusy] = useState(false)
  const [linkProblem, setLinkProblem] = useState<string | null>(null)
  const [problem, setProblem] = useState<string | null>(null)
  const [said, setSaid] = useState('')
  const looked = useRef(0)
  const savingLink = useRef<{ link: string; id: string } | null>(null)

  // A link's title and picture arrive a moment after it is saved: look again until they are there.
  const list = inspirations.state
  // A card moved on the canvas is replaced by what the server answered, without asking for the whole list again.
  const shown = useRef<Inspiration[]>([])
  useEffect(() => {
    if (list.status === 'ready') {
      shown.current = list.data
    }
  })
  const setList = inspirations.set
  const placed = useCallback(
    (moved: Inspiration) => setList(shown.current.map((one) => (one.id === moved.id ? moved : one))),
    [setList],
  )

  const waiting = list.status === 'ready' && list.data.some((inspiration) => inspiration.previewStatus === 'PENDING')
  const reload = inspirations.reload
  useEffect(() => {
    if (!waiting) {
      looked.current = 0
      return
    }
    if (looked.current >= LOOK_AGAIN_TIMES) {
      return
    }
    const timer = window.setTimeout(() => {
      looked.current += 1
      reload()
    }, LOOK_AGAIN_MS)
    return () => window.clearTimeout(timer)
  }, [waiting, list, reload])

  function show(name: 'board' | 'tag', value: string) {
    const next = new URLSearchParams(params)
    if (value === '') {
      next.delete(name)
    } else {
      next.set(name, value)
    }
    setParams(next, { replace: true })
  }

  function refresh() {
    inspirations.reload()
    boards.reload()
    tags.reload()
  }

  /** What is added from here goes on the board that is being looked at. */
  const ontoBoard = board !== '' && board !== 'none' ? board : null

  function saveLink(event: FormEvent) {
    event.preventDefault()
    if (link.trim() === '') {
      setLinkProblem('Paste a link first.')
      return
    }
    setBusy(true)
    setLinkProblem(null)
    setProblem(null)
    setSaid('')
    // The same link saved again, after no answer came, goes under the same id: the server then has it once.
    const typed = link.trim()
    if (savingLink.current?.link !== typed) {
      savingLink.current = { link: typed, id: newId() }
    }
    createInspiration({ id: savingLink.current.id, url: typed, boardId: ontoBoard, title: '', note: '', tags: [], planItemId: null, vendorId: null })
      .then(() => {
        savingLink.current = null
        setLink('')
        setSaid('The link was saved.')
        looked.current = 0
        refresh()
      })
      .catch((error: unknown) => {
        setLinkProblem(
          (error instanceof ApiError ? error.fieldMessage('url') : undefined) ?? 'The link could not be saved. Try again.',
        )
      })
      .finally(() => setBusy(false))
  }

  /** Pictures dropped or chosen here become one new idea. */
  function savePictures(files: File[]) {
    const refusal = whyNot(files, 0)
    if (refusal !== null) {
      setProblem(refusal)
      return
    }
    setBusy(true)
    setProblem(null)
    setSaid('')
    createInspiration({ id: newId(), url: null, boardId: ontoBoard, title: '', note: '', tags: [], planItemId: null, vendorId: null })
      .then((created) =>
        addPictures(created.id, files).catch(async (error: unknown) => {
          // Without its pictures the new idea would be an empty card: take it back.
          await deleteInspiration(created.id).catch(() => undefined)
          throw error
        }),
      )
      .then(() => {
        setSaid(files.length === 1 ? 'The picture was saved.' : `${files.length} pictures were saved as one idea.`)
        refresh()
      })
      .catch((error: unknown) => setProblem(uploadProblem(error)))
      .finally(() => setBusy(false))
  }

  const drop = usePictureDrop(savePictures)

  if (list.status === 'failed' || boards.state.status === 'failed' || tags.state.status === 'failed' || planItems.state.status === 'failed') {
    return (
      <PageRetry
        message="The inspirations could not be loaded. Try again."
        onRetry={() => {
          inspirations.retry()
          boards.retry()
          tags.retry()
          planItems.retry()
        }}
      />
    )
  }
  if (list.status === 'loading' || boards.state.status === 'loading' || tags.state.status === 'loading' || planItems.state.status === 'loading') {
    return <PageWaiting what="Loading…" />
  }

  const boardList = boards.state.data
  const boardNames = new Map(boardList.map((one) => [one.id, one.name]))
  const planItemTitles = new Map(planItems.state.data.map((one) => [one.id, one.title]))
  const narrowed = board !== '' || tag !== ''
  const back = `${view === 'canvas' ? '/inspirations/canvas' : '/inspirations'}${search}`

  return (
    <div className="ideas-page" data-view={view} data-dropping={drop.dropping || undefined} {...drop.handlers}>
      <div className="page-head">
        <h1>Inspirations</h1>
        <Link className="button" to="/inspirations/new" state={{ back, boardId: ontoBoard }}>
          Add an inspiration
        </Link>
      </div>

      <InspirationViews />

      <div className="quick-add">
        <form className="field stack-tight" onSubmit={saveLink} noValidate>
          <label htmlFor={linkId}>Add a link</label>
          <div className="quick-link">
            <input
              id={linkId}
              type="url"
              name="link"
              inputMode="url"
              autoComplete="off"
              placeholder="https://"
              value={link}
              aria-describedby={`${linkId}-hint${linkProblem === null ? '' : ` ${linkId}-error`}`}
              aria-invalid={linkProblem !== null}
              onChange={(event) => setLink(event.target.value)}
            />
            <button type="submit" className="button" disabled={busy}>
              Save link
            </button>
          </div>
          <p className="hint" id={`${linkId}-hint`}>
            From Instagram, TikTok, Pinterest or any page.
          </p>
          {linkProblem !== null && (
            <p className="error" id={`${linkId}-error`}>
              {linkProblem}
            </p>
          )}
        </form>
        <div className="quick-pictures stack-tight">
          <PictureChooser label="Add pictures" disabled={busy} onFiles={savePictures} />
          <p className="hint">Or drop pictures anywhere on this page.</p>
        </div>
      </div>

      <p role="status" className={said === '' ? 'visually-hidden' : 'said'}>
        {said}
      </p>
      {problem !== null && (
        <p role="alert" className="alert ideas-alert">
          {problem}
        </p>
      )}

      <form className="filters ideas-filters" aria-label="Narrow down" onSubmit={(event) => event.preventDefault()}>
        <div className="filter">
          <label htmlFor={`${linkId}-board`}>Board</label>
          <select id={`${linkId}-board`} value={board} onChange={(event) => show('board', event.target.value)}>
            <option value="">All boards</option>
            {boardList.map((one) => (
              <option key={one.id} value={one.id}>
                {one.name} ({one.inspirationCount})
              </option>
            ))}
            <option value="none">On no board</option>
          </select>
        </div>
        <div className="filter">
          <label htmlFor={`${linkId}-tag`}>Tag</label>
          <select id={`${linkId}-tag`} value={tag} onChange={(event) => show('tag', event.target.value)}>
            <option value="">Any tag</option>
            {/* A tag from the address that nothing carries any more is still shown as the choice made. */}
            {tag !== '' && !tags.state.data.some((one) => one.tag === tag) && <option value={tag}>{tag} (0)</option>}
            {tags.state.data.map((one) => (
              <option key={one.tag} value={one.tag}>
                {one.tag} ({one.count})
              </option>
            ))}
          </select>
        </div>
        <p className="filter-link">
          <Link to="/inspirations/boards" state={{ back }}>
            Add or change boards
          </Link>
        </p>
      </form>

      {view === 'canvas' ? (
        <Canvas inspirations={list.data} back={back} narrowed={narrowed} onPlaced={placed} />
      ) : list.data.length === 0 ? (
        <p className="empty">
          {narrowed
            ? 'Nothing here matches. Choose another board or tag.'
            : 'Nothing kept yet. Paste a link above, or drop a few pictures on this page.'}
        </p>
      ) : (
        <ul className="ideas">
          {list.data.map((inspiration) => (
            <li key={inspiration.id}>
              <InspirationCard
                inspiration={inspiration}
                board={inspiration.boardId === null ? undefined : boardNames.get(inspiration.boardId)}
                planItem={inspiration.planItemId === null ? undefined : planItemTitles.get(inspiration.planItemId)}
                back={back}
                onTag={(chosen) => show('tag', chosen)}
              />
            </li>
          ))}
        </ul>
      )}
      {drop.dropping && (
        <p className="drop-here" aria-hidden="true">
          Drop the pictures to keep them as a new idea.
        </p>
      )}
    </div>
  )
}
