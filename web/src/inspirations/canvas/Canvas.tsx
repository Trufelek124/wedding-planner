import {
  useCallback,
  useEffect,
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type KeyboardEvent as ReactKeyboardEvent,
  type PointerEvent as ReactPointerEvent,
} from 'react'
import { placeInspiration, type Inspiration } from '../inspirationsApi.ts'
import { nameOf } from '../labels.ts'
import { CanvasCard } from './CanvasCard.tsx'
import {
  BIG_PAN_STEP,
  BIG_STEP,
  PAN_STEP,
  SLACK,
  STEP,
  ZOOM_STEP,
  distance,
  draggedPlace,
  middle,
  panned,
  readingOrder,
  scaledAround,
  showingAll,
  steppedPlace,
  type Point,
  type Size,
  type View,
} from './layout.ts'

/** How long the arrow keys have to rest before the card's place is saved. */
export const KEYS_REST_MS = 600
/** What one notch of the wheel, in pixels, comes to when the browser counts in lines. */
const LINE_PIXELS = 16
/** How strongly the wheel scales while Ctrl or ⌘ is held: the scale is multiplied by e to the power of this times the notch. */
const WHEEL_ZOOM = 0.0025

/** Where a card lies while it is being moved, or has been and the server has not yet answered. */
interface Move {
  x: number
  y: number
  placedAt: string
  /** The number of the request that carries this place; null while no request does. */
  sent: number | null
}

type Gesture =
  | { kind: 'idle' }
  | { kind: 'surface'; from: Point; view: View }
  | { kind: 'card'; id: string; from: Point; view: View; start: Point; placedAt: string; moved: boolean }
  | { kind: 'pinch'; view: View; distance: number; middle: Point }

interface CanvasProps {
  /** The ideas to show, with the places the server has. */
  inspirations: Inspiration[]
  /** Where an idea's own page returns to. */
  back: string
  /** Whether the ideas were narrowed down by board or tag. */
  narrowed: boolean
  /** An idea after the server has moved its card. */
  onPlaced: (inspiration: Inspiration) => void
}

/** The surface's own size, the one thing about the page that the canvas has to know. */
function useSize(element: { current: HTMLElement | null }): Size {
  const [size, setSize] = useState<Size>({ width: 0, height: 0 })
  useLayoutEffect(() => {
    const surface = element.current
    if (surface === null) {
      return
    }
    const read = () =>
      setSize((was) => (was.width === surface.clientWidth && was.height === surface.clientHeight ? was : { width: surface.clientWidth, height: surface.clientHeight }))
    read()
    if (typeof ResizeObserver === 'undefined') {
      return
    }
    const watcher = new ResizeObserver(read)
    watcher.observe(surface)
    return () => watcher.disconnect()
  }, [element])
  return size
}

/** A moment later than every card has been moved to, so that the card that gets it lies on top. */
function afterAll(stamps: readonly string[]): string {
  const latest = stamps.reduce((most, stamp) => Math.max(most, Date.parse(stamp) || 0), 0)
  return new Date(latest + 1).toISOString()
}

/**
 * The inspirations as cards on one surface. Where a card lies, and how the surface is moved and scaled, is
 * arithmetic (`layout.ts`); the page measures nothing but the surface's size. Whatever the pointer can do here the
 * keyboard can do too: the surface itself has the arrow keys and the buttons; a card has the arrow keys.
 */
export function Canvas({ inspirations, back, narrowed, onPlaced }: CanvasProps) {
  const hintId = useId()
  const surface = useRef<HTMLDivElement>(null)
  const size = useSize(surface)

  // How far it is moved and how large it is shown is for this visit only, and starts as "everything".
  const [chosen, setChosen] = useState<View | null>(null)
  const [moves, setMoves] = useState<Record<string, Move>>({})
  const movesNow = useRef<Record<string, Move>>({})
  const [frozen, setFrozen] = useState<string[] | null>(null)
  const [said, setSaid] = useState<{ moved: string; failed: string }>({ moved: '', failed: '' })
  const [dragging, setDragging] = useState<string | null>(null)

  const pointers = useRef(new Map<number, Point>())
  const gesture = useRef<Gesture>({ kind: 'idle' })
  const justDragged = useRef(false)
  const requests = useRef(0)
  const resting = useRef(new Map<string, number>())

  // What the server has says where each card lies; a card that is being moved lies where it was put.
  const placed = inspirations.map((one) => {
    const move = moves[one.id]
    return { inspiration: one, x: move?.x ?? one.x, y: move?.y ?? one.y, placedAt: move?.placedAt ?? one.placedAt }
  })
  const fitted = showingAll(
    inspirations.map((one) => ({ x: one.x, y: one.y })),
    size,
  )
  const view = chosen ?? fitted

  // The latest of what the handlers below need; they are called by the browser, not by a render.
  const latest = useRef({ view, inspirations, placed, size })
  useEffect(() => {
    latest.current = { view, inspirations, placed, size }
  })

  const show = useCallback((next: View) => {
    latest.current.view = next
    setChosen(next)
  }, [])

  const setMove = useCallback((id: string, move: Move | null) => {
    const next = { ...movesNow.current }
    if (move === null) {
      delete next[id]
    } else {
      next[id] = move
    }
    movesNow.current = next
    setMoves(next)
  }, [])

  const nameFor = useCallback((id: string): string => {
    const found = latest.current.inspirations.find((one) => one.id === id)
    return found === undefined ? 'The idea' : nameOf(found)
  }, [])

  /** Saves where a card lies now: one request, whatever came before it. */
  const send = useCallback(
    (id: string) => {
      const move = movesNow.current[id]
      if (move === undefined) {
        return
      }
      requests.current += 1
      const number = requests.current
      const next = { ...movesNow.current, [id]: { ...move, sent: number } }
      movesNow.current = next
      setMoves(next)
      const settle = () => {
        if (movesNow.current[id]?.sent === number) {
          const rest = { ...movesNow.current }
          delete rest[id]
          movesNow.current = rest
          setMoves(rest)
        }
      }
      placeInspiration(id, { x: move.x, y: move.y })
        .then((answer) => {
          onPlaced(answer)
          settle()
          setSaid({ moved: `Moved “${nameFor(id)}”.`, failed: '' })
        })
        .catch(() => {
          settle()
          setSaid({ moved: '', failed: `“${nameFor(id)}” could not be moved. It is back where it was. Try again.` })
        })
    },
    [onPlaced, nameFor],
  )

  // A place that was only moved with the keys, and rests unsent, is not lost by leaving the page.
  useEffect(() => {
    const timers = resting.current
    return () => {
      for (const [id, timer] of timers) {
        window.clearTimeout(timer)
        send(id)
      }
      timers.clear()
    }
  }, [send])

  // ------------------------------------------------------------ the pointer

  const pointOf = useCallback((event: { clientX: number; clientY: number }): Point => {
    const box = surface.current?.getBoundingClientRect()
    return { x: event.clientX - (box?.left ?? 0), y: event.clientY - (box?.top ?? 0) }
  }, [])

  function takeHold(event: ReactPointerEvent) {
    if (event.button !== 0 || pointers.current.size >= 2) {
      return
    }
    justDragged.current = false
    setSaid({ moved: '', failed: '' })
    pointers.current.set(event.pointerId, pointOf(event))
    const now = latest.current
    if (pointers.current.size === 2) {
      // A second finger turns whatever the first was doing into scaling; a card dragged so far goes back.
      const held = gesture.current
      if (held.kind === 'card' && held.moved) {
        setMove(held.id, null)
        setDragging(null)
      }
      const [first, second] = [...pointers.current.values()] as [Point, Point]
      gesture.current = { kind: 'pinch', view: now.view, distance: Math.max(distance(first, second), 1), middle: middle(first, second) }
      return
    }
    const card = event.target instanceof Element ? event.target.closest<HTMLElement>('[data-card]') : null
    const from = pointOf(event)
    if (card === null) {
      gesture.current = { kind: 'surface', from, view: now.view }
      return
    }
    const id = card.dataset.card as string
    const one = now.placed.find((each) => each.inspiration.id === id)
    if (one === undefined) {
      return
    }
    gesture.current = {
      kind: 'card',
      id,
      from,
      view: now.view,
      start: { x: one.x, y: one.y },
      placedAt: movesNow.current[id]?.placedAt ?? afterAll(now.placed.map((each) => each.placedAt)),
      moved: false,
    }
  }

  const follow = useCallback(
    (event: PointerEvent) => {
      if (!pointers.current.has(event.pointerId)) {
        return
      }
      pointers.current.set(event.pointerId, pointOf(event))
      const held = gesture.current
      if (held.kind === 'surface') {
        const at = pointers.current.get(event.pointerId) as Point
        show(panned(held.view, at.x - held.from.x, at.y - held.from.y))
      } else if (held.kind === 'card') {
        const at = pointers.current.get(event.pointerId) as Point
        const dx = at.x - held.from.x
        const dy = at.y - held.from.y
        if (!held.moved && Math.hypot(dx, dy) < SLACK) {
          return
        }
        held.moved = true
        setDragging(held.id)
        const to = draggedPlace(held.start, dx, dy, held.view.scale)
        const was = movesNow.current[held.id]
        setMove(held.id, { ...to, placedAt: held.placedAt, sent: was?.sent ?? null })
      } else if (held.kind === 'pinch' && pointers.current.size === 2) {
        const [first, second] = [...pointers.current.values()] as [Point, Point]
        const mid = middle(first, second)
        const scaled = scaledAround(held.view, held.view.scale * (distance(first, second) / held.distance), held.middle)
        show(panned(scaled, mid.x - held.middle.x, mid.y - held.middle.y))
      }
    },
    [pointOf, show, setMove],
  )

  const letGo = useCallback(
    (event: PointerEvent) => {
      if (!pointers.current.delete(event.pointerId)) {
        return
      }
      const held = gesture.current
      if (pointers.current.size === 0) {
        gesture.current = { kind: 'idle' }
      } else if (held.kind === 'pinch') {
        // One finger is left from two: it does nothing more until it is lifted.
        gesture.current = { kind: 'idle' }
      }
      if (held.kind === 'card' && held.moved && pointers.current.size === 0) {
        setDragging(null)
        justDragged.current = true
        window.setTimeout(() => {
          justDragged.current = false
        }, 0)
        if (event.type === 'pointerup') {
          send(held.id)
        } else {
          // Taken away, as by the system: the card goes back.
          if (movesNow.current[held.id]?.sent === null) {
            setMove(held.id, null)
          }
        }
      }
    },
    [send, setMove],
  )

  useEffect(() => {
    window.addEventListener('pointermove', follow)
    window.addEventListener('pointerup', letGo)
    window.addEventListener('pointercancel', letGo)
    return () => {
      window.removeEventListener('pointermove', follow)
      window.removeEventListener('pointerup', letGo)
      window.removeEventListener('pointercancel', letGo)
    }
  }, [follow, letGo])

  // The wheel has to be able to keep the page behind from scrolling, which a listener of React's cannot.
  useEffect(() => {
    const element = surface.current
    if (element === null) {
      return
    }
    const wheel = (event: WheelEvent) => {
      event.preventDefault()
      const lines = event.deltaMode === 1 ? LINE_PIXELS : 1
      const now = latest.current.view
      if (event.ctrlKey || event.metaKey) {
        show(scaledAround(now, now.scale * Math.exp(-event.deltaY * lines * WHEEL_ZOOM), pointOf(event)))
      } else {
        show(panned(now, -event.deltaX * lines, -event.deltaY * lines))
      }
    }
    element.addEventListener('wheel', wheel, { passive: false })
    return () => element.removeEventListener('wheel', wheel)
  }, [pointOf, show])

  // ------------------------------------------------------------ the keyboard

  function arrowOf(event: ReactKeyboardEvent): Point | null {
    if (event.ctrlKey || event.metaKey || event.altKey) {
      return null
    }
    switch (event.key) {
      case 'ArrowLeft':
        return { x: -1, y: 0 }
      case 'ArrowRight':
        return { x: 1, y: 0 }
      case 'ArrowUp':
        return { x: 0, y: -1 }
      case 'ArrowDown':
        return { x: 0, y: 1 }
      default:
        return null
    }
  }

  function press(event: ReactKeyboardEvent) {
    const arrow = arrowOf(event)
    if (arrow === null) {
      return
    }
    const card = event.target instanceof Element ? event.target.closest<HTMLElement>('[data-card]') : null
    if (card !== null) {
      event.preventDefault()
      const id = card.dataset.card as string
      const one = latest.current.placed.find((each) => each.inspiration.id === id)
      if (one === undefined) {
        return
      }
      const step = event.shiftKey ? BIG_STEP : STEP
      const to = steppedPlace({ x: one.x, y: one.y }, arrow.x * step, arrow.y * step)
      setSaid({ moved: '', failed: '' })
      setMove(id, { ...to, placedAt: movesNow.current[id]?.placedAt ?? afterAll(latest.current.placed.map((each) => each.placedAt)), sent: null })
      window.clearTimeout(resting.current.get(id))
      resting.current.set(
        id,
        window.setTimeout(() => {
          resting.current.delete(id)
          send(id)
        }, KEYS_REST_MS),
      )
    } else if (event.target === event.currentTarget) {
      event.preventDefault()
      const step = event.shiftKey ? BIG_PAN_STEP : PAN_STEP
      show(panned(latest.current.view, -arrow.x * step, -arrow.y * step))
    }
  }

  // ------------------------------------------------------------ the order of things

  const order = readingOrder(placed.map((one) => ({ x: one.x, y: one.y, one })))
    .map((one) => one.one)
  // While a card has the keyboard the cards keep their order in the page, or moving one would take it away.
  const ids = new Set(order.map((one) => one.inspiration.id))
  const inOrder =
    frozen === null
      ? order
      : [
          ...frozen.flatMap((id) => order.filter((one) => one.inspiration.id === id)),
          ...order.filter((one) => !frozen.includes(one.inspiration.id) && ids.has(one.inspiration.id)),
        ]
  const layers = new Map(
    [...placed]
      .sort((a, b) => Date.parse(a.placedAt) - Date.parse(b.placedAt) || a.inspiration.id.localeCompare(b.inspiration.id))
      .map((one, at) => [one.inspiration.id, at + 1]),
  )

  function focused(event: React.FocusEvent) {
    if (event.target instanceof Element && event.target.closest('[data-card]') !== null) {
      setFrozen(inOrder.map((one) => one.inspiration.id))
    }
  }

  function left(event: React.FocusEvent) {
    const next = event.relatedTarget
    if (!(next instanceof Element && next.closest('[data-card]') !== null)) {
      setFrozen(null)
    }
  }

  const percent = Math.round(view.scale * 100)

  return (
    <div className="canvas-wrap">
      <div className="canvas-tools" role="group" aria-label="Size of the canvas">
        <button type="button" className="button button-quiet" onClick={() => show(scaledAround(latest.current.view, latest.current.view.scale * ZOOM_STEP, centre(size)))}>
          Larger
        </button>
        <button type="button" className="button button-quiet" onClick={() => show(scaledAround(latest.current.view, latest.current.view.scale / ZOOM_STEP, centre(size)))}>
          Smaller
        </button>
        <button type="button" className="button button-quiet" onClick={() => show(scaledAround(latest.current.view, 1, centre(size)))}>
          Natural size
        </button>
        <button type="button" className="button button-quiet" onClick={() => setChosen(null)}>
          Show everything
        </button>
        <output className="canvas-scale">Shown at {percent}%</output>
      </div>
      <p className="hint" id={hintId}>
        Drag a card to move it, or the space between the cards to move around. Ctrl or ⌘ with the wheel, or two fingers, make it larger or smaller.
        With the keyboard: Tab goes from card to card, and the arrow keys move the card (Shift for larger steps). On the canvas itself they move around.
      </p>
      <div
        ref={surface}
        className="canvas"
        role="group"
        aria-label="Canvas of inspirations"
        aria-describedby={hintId}
        tabIndex={0}
        onPointerDown={takeHold}
        onKeyDown={press}
        onFocus={focused}
        onBlur={left}
        onDragStart={(event) => event.preventDefault()}
      >
        <div className="canvas-plane" style={{ transform: `translate(${view.offsetX}px, ${view.offsetY}px) scale(${view.scale})` }}>
          {inOrder.map((one) => (
            <CanvasCard
              key={one.inspiration.id}
              inspiration={one.inspiration}
              x={one.x}
              y={one.y}
              layer={layers.get(one.inspiration.id) ?? 1}
              back={back}
              dragged={() => justDragged.current}
              moving={dragging === one.inspiration.id}
            />
          ))}
        </div>
        {inspirations.length === 0 && (
          <p className="empty canvas-empty">
            {narrowed
              ? 'Nothing here matches. Choose another board or tag.'
              : 'Nothing kept yet. Paste a link above, or drop a few pictures on this page.'}
          </p>
        )}
      </div>
      <p role="status" className={said.moved === '' ? 'visually-hidden' : 'said'}>
        {said.moved}
      </p>
      <p role="alert" className={said.failed === '' ? 'visually-hidden' : 'alert'}>
        {said.failed}
      </p>
    </div>
  )
}

function centre(size: Size): Point {
  return { x: size.width / 2, y: size.height / 2 }
}
