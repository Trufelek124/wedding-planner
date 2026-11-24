import {
  Fragment,
  useEffect,
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type KeyboardEvent,
  type PointerEvent as ReactPointerEvent,
} from 'react'
import { Link, useNavigate } from 'react-router'
import { ApiError } from '../../api/client.ts'
import { shortDate, STATUS_LABEL, when } from '../labels.ts'
import { rowFacts, markerName } from './taskWords.ts'
import { addDependency, removeDependency, type Entry } from '../planningApi.ts'
import { dayNumber } from './days.ts'
import {
  barOf,
  diamondAt,
  frameOf,
  HEAD_HEIGHT,
  HEADING_HEIGHT,
  heightOf,
  markerOf,
  linkPath,
  LOWER_HEIGHT,
  rowAt,
  ROW_HEIGHT,
  scaleOf,
  shifted,
  spanOf,
  UPPER_HEIGHT,
  xOf,
  type Box,
  type Dates,
  type Grip,
  type Point,
  type Scale,
} from './layout.ts'
import { useDateChanges } from './useDateChanges.ts'

/** A plan item that has its place on the chart. */
export type DatedEntry = Entry & { dueDate: string }

interface ChartProps {
  /** The plan items with dates, one row each, in the order of the rows. */
  items: DatedEntry[]
  /** Tasks with a due date. Those that belong to a plan item are marked on its bar. */
  tasks: Entry[]
  /** The tasks that have a row of their own, in the order of the rows: each has a due date and none is marked on a bar. */
  taskRows: Entry[]
  /** The wedding's currency, for the amount of a payment. */
  currency: string
  scale: Scale
  /** Today's date where the wedding takes place. */
  today: string
  weddingDay: string | null
  /** Where an entry's form returns to. */
  back: string
  /** Something was saved: what the chart was given should be read again. */
  onChanged: () => void
}

interface ChosenLink {
  id: string
  dependsOnId: string
}

const CYCLE = 'urn:wedding-planner:problem:dependency-cycle'
const CHANGED_MEANWHILE = 'urn:wedding-planner:problem:changed-meanwhile'
/** How far from the left edge today stands when the chart opens. */
const LEAD = 96
const GRIP = 8
const MARK = 7

/**
 * The plan on a timeline: a bar per plan item, lines between the ones that wait for each other, the tasks of
 * a plan item as marks on its bar, and lines for today and the wedding day.
 *
 * Everything a pointer can do here, the keyboard can do too: the arrow keys move a bar, with Shift its end and
 * with Alt its start; L and Enter link two bars. A bar's place comes from its dates alone (see `layout.ts`).
 */
export function Chart({ items, tasks, taskRows, currency, scale, today, weddingDay, back, onChanged }: ChartProps) {
  const navigate = useNavigate()
  const hint = useId()
  const markerHint = useId()
  const [problem, setProblem] = useState<string | null>(null)
  const [current, setCurrent] = useState<string | null>(null)
  const [linkFrom, setLinkFrom] = useState<string | null>(null)
  const [chosen, setChosen] = useState<ChosenLink | null>(null)

  const scroller = useRef<HTMLDivElement>(null)
  const body = useRef<SVGSVGElement>(null)
  const band = useRef<SVGPathElement>(null)
  const bars = useRef(new Map<string, SVGGElement>())
  const stopTracking = useRef<(() => void) | null>(null)
  const opened = useRef<{ scale: Scale; first: number } | null>(null)
  /** The stop of the keyboard that has to keep it while the rows are put in their new order. */
  const keep = useRef<string | null>(null)

  const changes = useDateChanges(items, {
    saved: onChanged,
    failed: (error) => {
      setProblem(
        error instanceof ApiError && error.is(CHANGED_MEANWHILE)
          ? 'Someone else changed that plan item in the meantime, so your change was not saved. The chart shows the latest version now.'
          : 'The new dates could not be saved. Try again.',
      )
      onChanged()
    },
  })

  const taskChanges = useDateChanges(taskRows, {
    saved: onChanged,
    failed: (error) => {
      setProblem(
        error instanceof ApiError && error.is(CHANGED_MEANWHILE)
          ? 'Someone else changed that task in the meantime, so your change was not saved. The chart shows the latest version now.'
          : 'The new day could not be saved. Try again.',
      )
      onChanged()
    },
  })

  // The frame follows what is saved, not what is being dragged: it must not move under the pointer.
  const marked = tasks.filter((task) => task.dueDate !== null && items.some((item) => item.id === task.parentId))
  const frame = frameOf(scale, [
    dayNumber(today),
    ...(weddingDay === null ? [] : [dayNumber(weddingDay)]),
    ...items.flatMap((item) => [spanOf(item).start, spanOf(item).end]),
    ...marked.map((task) => dayNumber(task.dueDate ?? today)),
    ...taskRows.map((task) => dayNumber(task.dueDate ?? today)),
  ])
  const { upper, lower } = scaleOf(frame, scale)

  const rows = items.map((item) => changes.shown(item))
  const boxes = new Map<string, Box>(rows.map((row, index) => [row.id, barOf(frame, spanOf(row), index)]))
  const titles = new Map(rows.map((row) => [row.id, row.title]))
  const titleOf = (id: string) => titles.get(id) ?? 'another plan item'
  const links = rows.flatMap((row) =>
    row.dependsOnIds
      .filter((dependsOnId) => boxes.has(dependsOnId))
      .map((dependsOnId) => ({ id: row.id, dependsOnId, conflict: row.conflictsWithIds.includes(dependsOnId) })),
  )
  const chosenLink = links.find((link) => link.id === chosen?.id && link.dependsOnId === chosen.dependsOnId) ?? null
  // The tasks' rows keep the order of what is saved, so that a marker does not change rows under the pointer.
  const placed = taskRows.map((task) => taskChanges.shown(task))
  const stops = [...rows.map((row) => row.id), ...placed.map((task) => task.id)]
  const currentId = stops.includes(current ?? '') ? current : (stops[0] ?? null)
  const linkingFrom = rows.some((row) => row.id === linkFrom) ? linkFrom : null
  const height = heightOf(rows.length, placed.length)
  const todayX = xOf(frame, dayNumber(today)) + frame.dayWidth / 2
  const weddingX = weddingDay === null ? null : xOf(frame, dayNumber(weddingDay)) + frame.dayWidth / 2

  // The chart opens with today near its left edge. When the frame grows to the left, what was in view stays there.
  useLayoutEffect(() => {
    const element = scroller.current
    if (element === null) {
      return
    }
    const before = opened.current
    if (before === null || before.scale !== scale) {
      element.scrollLeft = Math.max(0, (dayNumber(today) - frame.first) * frame.dayWidth - LEAD)
    } else if (before.first !== frame.first) {
      element.scrollLeft += (before.first - frame.first) * frame.dayWidth
    }
    opened.current = { scale, first: frame.first }
  }, [scale, today, frame.first, frame.dayWidth])

  // A row that is put in its new place is taken out and put back: the keyboard must not be lost on the way.
  useLayoutEffect(() => {
    if (keep.current !== null && document.activeElement === document.body) {
      bars.current.get(keep.current)?.focus({ preventScroll: true })
    }
  })

  useEffect(() => {
    const letGo = () => {
      keep.current = null
    }
    window.addEventListener('pointerdown', letGo)
    return () => {
      window.removeEventListener('pointerdown', letGo)
      stopTracking.current?.()
    }
  }, [])

  function conflictOf(row: Entry): string | null {
    if (row.conflictsWithIds.length === 0) {
      return null
    }
    const names = row.conflictsWithIds.map((id) => titles.get(id)).filter((title) => title !== undefined)
    return names.length === 0
      ? 'Starts before something it waits for is finished'
      : `Starts before ${names.map((title) => `“${title}”`).join(' and ')} ${names.length === 1 ? 'is' : 'are'} finished`
  }

  function nameOf(row: DatedEntry): string {
    const own = marked.filter((task) => task.parentId === row.id).length
    return [
      row.title,
      when(row),
      row.status === 'NOT_STARTED' ? null : STATUS_LABEL[row.status],
      own === 0 ? null : own === 1 ? '1 task' : `${own} tasks`,
      conflictOf(row),
    ]
      .filter((part) => part !== null)
      .join(', ')
  }

  /** Where a pointer is on the chart, whatever has been scrolled. */
  function pointOf(event: { clientX: number; clientY: number }): Point {
    const box = body.current?.getBoundingClientRect()
    return { x: event.clientX - (box?.left ?? 0), y: event.clientY - (box?.top ?? 0) }
  }

  /** Follows the pointer until it is lifted. `end` gets no point when the gesture was taken away, as by scrolling. */
  function track(move: (point: Point) => void, end: (point: Point | null) => void) {
    const moved = (event: PointerEvent) => move(pointOf(event))
    const ended = (event: PointerEvent) => {
      stop()
      end(event.type === 'pointerup' ? pointOf(event) : null)
    }
    const stop = () => {
      window.removeEventListener('pointermove', moved)
      window.removeEventListener('pointerup', ended)
      window.removeEventListener('pointercancel', ended)
      stopTracking.current = null
    }
    stopTracking.current?.()
    stopTracking.current = stop
    window.addEventListener('pointermove', moved)
    window.addEventListener('pointerup', ended)
    window.addEventListener('pointercancel', ended)
  }

  function focusBar(id: string) {
    bars.current.get(id)?.focus({ preventScroll: true })
  }

  /** A bar, or one of its ends, was taken hold of. */
  function grab(event: ReactPointerEvent, row: DatedEntry, grip: Grip) {
    if (event.button !== 0) {
      return
    }
    if (event.pointerType === 'touch' && currentId !== row.id) {
      // A finger first chooses a bar. Only the chosen bar can be dragged, so swiping across the chart still scrolls it.
      focusBar(row.id)
      return
    }
    event.preventDefault()
    focusBar(row.id)
    setProblem(null)
    const origin = pointOf(event).x
    const original: Dates = { startDate: row.startDate, dueDate: row.dueDate }
    let latest = original
    track(
      (point) => {
        const next = shifted(original, grip, Math.round((point.x - origin) / frame.dayWidth))
        if (next.startDate !== latest.startDate || next.dueDate !== latest.dueDate) {
          latest = next
          changes.show(row.id, next)
        }
      },
      (point) => {
        if (point === null || (latest.startDate === original.startDate && latest.dueDate === original.dueDate)) {
          changes.forget(row.id)
        } else {
          void changes.save(row.id)
        }
      },
    )
  }

  /** A link is pulled out of the end of a bar; the plan item it is dropped on will wait for this one. */
  function pull(event: ReactPointerEvent, row: DatedEntry) {
    const box = boxes.get(row.id)
    if (event.button !== 0 || box === undefined) {
      return
    }
    event.preventDefault()
    event.stopPropagation()
    setProblem(null)
    const from = { x: box.x + box.width, y: box.y + box.height / 2 }
    track(
      (point) => band.current?.setAttribute('d', `M ${from.x} ${from.y} L ${point.x} ${point.y}`),
      (point) => {
        band.current?.setAttribute('d', '')
        const target = point === null ? undefined : rows[rowAt(point.y)]
        if (target !== undefined && target.id !== row.id) {
          void link(target.id, row.id)
        }
      },
    )
  }

  async function link(id: string, dependsOnId: string) {
    setProblem(null)
    try {
      await addDependency(id, dependsOnId)
      onChanged()
    } catch (error) {
      setProblem(
        error instanceof ApiError && error.is(CYCLE)
          ? `“${titleOf(dependsOnId)}” already waits for “${titleOf(id)}”, directly or through others, so the two cannot wait for each other.`
          : 'The link could not be saved. Try again.',
      )
    }
  }

  async function unlink(link: ChosenLink) {
    setProblem(null)
    try {
      await removeDependency(link.id, link.dependsOnId)
      setChosen(null)
      onChanged()
    } catch {
      setProblem('The link could not be removed. Try again.')
    }
  }

  function open(id: string) {
    void navigate(`/entries/${id}`, { state: { back } })
  }

  function barKey(event: KeyboardEvent, row: DatedEntry, index: number) {
    if (event.ctrlKey || event.metaKey) {
      return
    }
    switch (event.key) {
      case 'ArrowLeft':
      case 'ArrowRight': {
        event.preventDefault()
        setProblem(null)
        const grip: Grip = event.shiftKey ? 'end' : event.altKey ? 'start' : 'move'
        changes.show(row.id, shifted(row, grip, event.key === 'ArrowRight' ? 1 : -1))
        void changes.save(row.id)
        break
      }
      case 'ArrowUp':
      case 'ArrowDown': {
        event.preventDefault()
        const next = stops[index + (event.key === 'ArrowDown' ? 1 : -1)]
        if (next !== undefined) {
          bars.current.get(next)?.focus()
        }
        break
      }
      case 'Enter':
      case ' ':
        event.preventDefault()
        if (linkingFrom === null) {
          open(row.id)
        } else {
          setLinkFrom(null)
          if (linkingFrom !== row.id) {
            void link(row.id, linkingFrom)
          }
        }
        break
      case 'l':
      case 'L':
        if (!event.altKey) {
          event.preventDefault()
          setLinkFrom(row.id)
        }
        break
      case 'Escape':
        if (linkingFrom !== null) {
          event.preventDefault()
          setLinkFrom(null)
        }
        break
    }
  }

  /** A task's marker was taken hold of: it moves along its row, and a click that moves nothing opens the task. */
  function grabMarker(event: ReactPointerEvent, task: Entry & { dueDate: string }) {
    if (event.button !== 0) {
      return
    }
    if (event.pointerType === 'touch' && currentId !== task.id) {
      focusBar(task.id)
      return
    }
    event.preventDefault()
    focusBar(task.id)
    setProblem(null)
    const origin = pointOf(event).x
    const original: Dates = { startDate: task.startDate, dueDate: task.dueDate }
    let latest = original
    let moved = false
    track(
      (point) => {
        const next = shifted(original, 'move', Math.round((point.x - origin) / frame.dayWidth))
        if (next.startDate !== latest.startDate || next.dueDate !== latest.dueDate) {
          latest = next
          moved = true
          taskChanges.show(task.id, next)
        }
      },
      (point) => {
        if (point === null || (latest.startDate === original.startDate && latest.dueDate === original.dueDate)) {
          taskChanges.forget(task.id)
          if (point !== null && !moved) {
            open(task.id)
          }
        } else {
          void taskChanges.save(task.id)
        }
      },
    )
  }

  function markerKey(event: KeyboardEvent, task: Entry & { dueDate: string }, index: number) {
    if (event.ctrlKey || event.metaKey || event.altKey) {
      return
    }
    switch (event.key) {
      case 'ArrowLeft':
      case 'ArrowRight': {
        event.preventDefault()
        setProblem(null)
        keep.current = task.id
        const days = (event.shiftKey ? 7 : 1) * (event.key === 'ArrowRight' ? 1 : -1)
        taskChanges.show(task.id, shifted(task, 'move', days))
        void taskChanges.save(task.id)
        break
      }
      case 'ArrowUp':
      case 'ArrowDown': {
        event.preventDefault()
        const next = stops[rows.length + index + (event.key === 'ArrowDown' ? 1 : -1)]
        if (next !== undefined) {
          bars.current.get(next)?.focus()
        }
        break
      }
      case 'Enter':
      case ' ':
        event.preventDefault()
        if (linkingFrom === null) {
          open(task.id)
        }
        break
      case 'Escape':
        if (linkingFrom !== null) {
          event.preventDefault()
          setLinkFrom(null)
        }
        break
    }
  }

  function linkKey(event: KeyboardEvent, link: ChosenLink) {
    if (event.key === 'Delete' || event.key === 'Backspace') {
      event.preventDefault()
      void unlink(link)
    } else if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault()
      setChosen(link)
    }
  }

  return (
    <div className="stack">
      {problem !== null && (
        <p role="alert" className="alert">
          {problem}
        </p>
      )}
      <p role="status" className="gantt-status">
        {linkingFrom !== null &&
          `Choose the plan item that waits for “${titleOf(linkingFrom)}”: go to it with the up and down arrows and press Enter. Escape cancels.`}
      </p>
      {chosenLink !== null && (
        <div className="gantt-chosen" role="group" aria-label="The chosen link">
          <span>
            “{titleOf(chosenLink.id)}” waits for “{titleOf(chosenLink.dependsOnId)}”.
          </span>
          <button type="button" className="button button-quiet" onClick={() => void unlink(chosenLink)}>
            Remove this link
          </button>
          <button type="button" className="button button-quiet" onClick={() => setChosen(null)}>
            Keep it
          </button>
        </div>
      )}
      <p id={hint} hidden>
        Left and right arrows move it by a day; with Shift they move its end, with Alt its start. Up and down go to the
        next plan item. Enter opens it. L, then Enter on another plan item, makes that one wait for this one.
      </p>

      {placed.length > 0 && (
        <p id={markerHint} hidden>
          Left and right arrows move it by a day, with Shift by a week. Up and down go to the next row. Enter opens it.
        </p>
      )}

      <div className="gantt-scroll" ref={scroller} role="group" aria-label="The plan on a timeline">
        <div className="gantt" style={{ width: `calc(var(--gantt-label) + ${frame.width}px)` }}>
          <div className="gantt-corner" style={{ height: HEAD_HEIGHT }}>
            {placed.length === 0 ? 'Plan item' : 'Plan item or task'}
          </div>

          <div className="gantt-scale" style={{ width: frame.width, height: HEAD_HEIGHT }} aria-hidden="true">
            {/* The name of a month stays in view for as long as any of the month is. */}
            <div className="gantt-upper" style={{ height: UPPER_HEIGHT }}>
              {upper.map((tick) => (
                <div key={tick.x} style={{ width: tick.width }}>
                  {tick.width >= tick.label.length * 8 + 12 && <span>{tick.label}</span>}
                </div>
              ))}
            </div>
            <svg width={frame.width} height={LOWER_HEIGHT}>
              {lower.map((tick) => (
                <g key={tick.x}>
                  <line className="gantt-line" x1={tick.x} x2={tick.x} y1={0} y2={20} />
                  <text x={tick.x + 4} y={14}>
                    {tick.label}
                  </text>
                </g>
              ))}
              <line className="gantt-line" x1={0} x2={frame.width} y1={20} y2={20} />
              <text className="gantt-today-name" x={todayX} y={34} textAnchor="middle">
                Today
              </text>
              {weddingX !== null && (
                <text className="gantt-wedding-name" x={weddingX} y={34} textAnchor="middle">
                  Wedding day
                </text>
              )}
            </svg>
          </div>

          <div className="gantt-labels">
            <ul>
            {rows.map((row) => {
              const conflict = conflictOf(row)
              return (
                <li key={row.id} style={{ height: ROW_HEIGHT }} data-current={row.id === currentId || undefined}>
                  {/* Out of the tab order: the bar of the row is the one stop for the keyboard, and Enter there opens it. */}
                  <Link className="gantt-title" to={`/entries/${row.id}`} state={{ back }} tabIndex={-1} title={row.title}>
                    {row.title}
                  </Link>
                  {conflict === null ? (
                    <span className="gantt-meta">
                      {when(row)}
                      {row.status !== 'NOT_STARTED' && ` · ${STATUS_LABEL[row.status]}`}
                    </span>
                  ) : (
                    <span className="gantt-meta gantt-warning" title={conflict}>
                      {conflict}
                    </span>
                  )}
                </li>
              )
            })}
            </ul>
            {placed.length > 0 && (
              <>
                <h2 className="gantt-heading" style={{ height: HEADING_HEIGHT }}>
                  Tasks
                </h2>
                <ul>
                  {placed.map((task) => {
                    const facts = rowFacts(task, today, currency)
                    return (
                      <li key={task.id} style={{ height: ROW_HEIGHT }} data-current={task.id === currentId || undefined} data-done={task.status === 'DONE' || undefined}>
                        <Link className="gantt-title" to={`/entries/${task.id}`} state={{ back }} tabIndex={-1} title={task.title}>
                          {task.title}
                        </Link>
                        <span className="gantt-meta">
                          {facts.map((fact, at) => (
                            <Fragment key={fact.text}>
                              {at > 0 && ' · '}
                              {fact.warning ? <span className="gantt-warning">{fact.text}</span> : fact.text}
                            </Fragment>
                          ))}
                        </span>
                      </li>
                    )
                  })}
                </ul>
              </>
            )}
          </div>

          <svg className="gantt-body" ref={body} width={frame.width} height={height} role="group" aria-label="Bars">
            {lower.map((tick) => (
              <line key={tick.x} className="gantt-line" x1={tick.x} x2={tick.x} y1={0} y2={height} />
            ))}
            {rows.map((row, index) => (
              <line key={row.id} className="gantt-line" x1={0} x2={frame.width} y1={(index + 1) * ROW_HEIGHT} y2={(index + 1) * ROW_HEIGHT} />
            ))}
            {placed.length > 0 && (
              <>
                <rect className="gantt-heading-band" x={0} y={rows.length * ROW_HEIGHT} width={frame.width} height={HEADING_HEIGHT} />
                {placed.map((task, index) => {
                  const y = markerOf(frame, 0, rows.length, index).y + ROW_HEIGHT / 2
                  return <line key={task.id} className="gantt-line" x1={0} x2={frame.width} y1={y} y2={y} />
                })}
              </>
            )}
            <line className="gantt-today" x1={todayX} x2={todayX} y1={0} y2={height} />
            {weddingX !== null && <line className="gantt-wedding" x1={weddingX} x2={weddingX} y1={0} y2={height} />}

            {links.map((link) => {
              const from = boxes.get(link.dependsOnId)
              const to = boxes.get(link.id)
              if (from === undefined || to === undefined) {
                return null
              }
              const end = { x: to.x, y: to.y + to.height / 2 }
              const path = linkPath({ x: from.x + from.width, y: from.y + from.height / 2 }, end)
              return (
                <g
                  key={`${link.id}-${link.dependsOnId}`}
                  className="gantt-link"
                  role="button"
                  tabIndex={-1}
                  aria-label={`“${titleOf(link.id)}” waits for “${titleOf(link.dependsOnId)}”${link.conflict ? ', which is not finished when it starts' : ''}`}
                  data-conflict={link.conflict || undefined}
                  data-chosen={link === chosenLink || undefined}
                  onClick={() => setChosen(link)}
                  onKeyDown={(event) => linkKey(event, link)}
                >
                  <path className="gantt-link-reach" d={path} />
                  <path className="gantt-link-line" d={path} />
                  <path className="gantt-link-head" d={`M ${end.x} ${end.y} l -7 -4 v 8 z`} />
                </g>
              )
            })}

            {rows.map((row, index) => {
              const box = boxes.get(row.id)
              if (box === undefined) {
                return null
              }
              const middle = box.y + box.height / 2
              return (
                <g
                  key={row.id}
                  ref={(node) => {
                    if (node === null) {
                      bars.current.delete(row.id)
                    } else {
                      bars.current.set(row.id, node)
                    }
                  }}
                  className="gantt-bar"
                  role="button"
                  tabIndex={row.id === currentId ? 0 : -1}
                  aria-label={nameOf(row)}
                  aria-describedby={hint}
                  data-status={row.status}
                  data-conflict={row.conflictsWithIds.length > 0 || undefined}
                  data-current={row.id === currentId || undefined}
                  data-linking={row.id === linkingFrom || undefined}
                  onFocus={() => setCurrent(row.id)}
                  onKeyDown={(event) => barKey(event, row, index)}
                  onDoubleClick={() => open(row.id)}
                >
                  <rect
                    className="gantt-bar-body"
                    x={box.x}
                    y={box.y}
                    width={box.width}
                    height={box.height}
                    rx={5}
                    onPointerDown={(event) => grab(event, row, 'move')}
                  />
                  {marked
                    .filter((task) => task.parentId === row.id)
                    .map((task) => {
                      const x = xOf(frame, dayNumber(task.dueDate ?? today)) + frame.dayWidth / 2
                      return (
                        <path
                          key={task.id}
                          className="gantt-task"
                          data-done={task.status === 'DONE' || undefined}
                          d={`M ${x} ${middle - MARK} l ${MARK} ${MARK} l ${-MARK} ${MARK} l ${-MARK} ${-MARK} z`}
                          onPointerDown={(event) => grab(event, row, 'move')}
                        >
                          <title>{`${task.title}, due ${shortDate(task.dueDate ?? today)}${task.status === 'DONE' ? ', done' : ''}`}</title>
                        </path>
                      )
                    })}
                  <rect
                    className="gantt-grip"
                    data-grip="start"
                    x={box.x - GRIP / 2}
                    y={box.y}
                    width={GRIP}
                    height={box.height}
                    onPointerDown={(event) => grab(event, row, 'start')}
                  />
                  <rect
                    className="gantt-grip"
                    data-grip="end"
                    x={box.x + box.width - GRIP / 2}
                    y={box.y}
                    width={GRIP}
                    height={box.height}
                    onPointerDown={(event) => grab(event, row, 'end')}
                  />
                  <circle
                    className="gantt-link-grip"
                    data-grip="link"
                    cx={box.x + box.width + 12}
                    cy={middle}
                    r={5}
                    onPointerDown={(event) => pull(event, row)}
                  />
                  {row.conflictsWithIds.length > 0 && (
                    <text className="gantt-conflict" x={box.x + Math.min(10, box.width / 2)} y={middle} textAnchor="middle" dominantBaseline="central">
                      !
                    </text>
                  )}
                </g>
              )
            })}
            {placed.map((task, index) => {
              if (task.dueDate === null) {
                return null
              }
              const dated = { ...task, dueDate: task.dueDate }
              const at = markerOf(frame, dayNumber(task.dueDate), rows.length, index)
              const states = markerName(task, today, currency)
              return (
                <g
                  key={task.id}
                  ref={(node) => {
                    if (node === null) {
                      bars.current.delete(task.id)
                    } else {
                      bars.current.set(task.id, node)
                    }
                  }}
                  className="gantt-marker"
                  role="button"
                  tabIndex={task.id === currentId ? 0 : -1}
                  aria-label={states}
                  aria-describedby={markerHint}
                  data-status={task.status}
                  data-done={task.status === 'DONE' || undefined}
                  data-overdue={(task.status !== 'DONE' && task.status !== 'DROPPED' && task.dueDate < today) || undefined}
                  data-current={task.id === currentId || undefined}
                  onFocus={() => setCurrent(task.id)}
                  onBlur={(event) => {
                    if (event.relatedTarget !== null) {
                      keep.current = null
                    }
                  }}
                  onKeyDown={(event) => markerKey(event, dated, index)}
                  onPointerDown={(event) => grabMarker(event, dated)}
                >
                  <rect className="gantt-marker-reach" x={at.x - 14} y={at.y - 14} width={28} height={28} />
                  <path className="gantt-marker-shape" d={diamondAt(at)} />
                </g>
              )
            })}
            <path className="gantt-band" ref={band} d="" />
          </svg>
        </div>
      </div>
    </div>
  )
}
