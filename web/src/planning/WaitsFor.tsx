import { useId, useState } from 'react'
import { ApiError } from '../api/client.ts'
import { addDependency, removeDependency, type Entry } from './planningApi.ts'

interface WaitsForProps {
  entry: Entry
  /** Every plan item, to choose from and to name the ones already waited for. */
  planItems: Entry[]
}

const CYCLE = 'urn:wedding-planner:problem:dependency-cycle'

/**
 * What a plan item waits for: the plan items that have to be finished before it can start.
 * Each change is saved at once; it is not part of the form above and leaves what was typed there alone.
 * The chart draws the same links as lines and lets them be drawn with a pointer.
 */
export function WaitsFor({ entry, planItems }: WaitsForProps) {
  const choice = useId()
  const [waitsFor, setWaitsFor] = useState(entry.dependsOnIds)
  const [tooEarly, setTooEarly] = useState(entry.conflictsWithIds)
  const [adding, setAdding] = useState('')
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)

  const titleOf = (id: string) => planItems.find((item) => item.id === id)?.title ?? 'A plan item'
  const others = planItems.filter((item) => item.id !== entry.id && !waitsFor.includes(item.id))

  async function change(action: () => Promise<Entry>, failed: (error: unknown) => string) {
    setBusy(true)
    setProblem(null)
    try {
      const answer = await action()
      setWaitsFor(answer.dependsOnIds)
      setTooEarly(answer.conflictsWithIds)
      setAdding('')
    } catch (error) {
      setProblem(failed(error))
    } finally {
      setBusy(false)
    }
  }

  function add() {
    if (adding === '') {
      return
    }
    void change(
      () => addDependency(entry.id, adding),
      (error) =>
        error instanceof ApiError && error.is(CYCLE)
          ? `“${titleOf(adding)}” already waits for this one, directly or through others, so the two cannot wait for each other.`
          : 'That could not be saved. Try again.',
    )
  }

  function remove(id: string) {
    void change(
      () => removeDependency(entry.id, id),
      () => 'That could not be saved. Try again.',
    )
  }

  return (
    <section className="danger-zone stack" aria-label="Waits for">
      <h2>Waits for</h2>
      <p className="muted">The plan items that have to be finished before this one can start. Saved as soon as you change it.</p>
      {problem !== null && (
        <p role="alert" className="alert">
          {problem}
        </p>
      )}
      {waitsFor.length > 0 && (
        <ul className="waits-for">
          {waitsFor.map((id) => (
            <li key={id}>
              <span>
                {titleOf(id)}
                {tooEarly.includes(id) && <span className="gantt-warning"> — not finished when this one starts</span>}
              </span>
              <button
                type="button"
                className="button button-quiet"
                disabled={busy}
                aria-label={`Stop waiting for “${titleOf(id)}”`}
                onClick={() => remove(id)}
              >
                Remove
              </button>
            </li>
          ))}
        </ul>
      )}
      {others.length > 0 && (
        <div className="waits-for-add">
          <div className="field stack-tight">
            <label htmlFor={choice}>Add a plan item to wait for</label>
            <select id={choice} value={adding} onChange={(event) => setAdding(event.target.value)}>
              <option value="">Choose one</option>
              {others.map((item) => (
                <option key={item.id} value={item.id}>
                  {item.title}
                </option>
              ))}
            </select>
          </div>
          <button type="button" className="button button-quiet" disabled={busy || adding === ''} onClick={add}>
            Add
          </button>
        </div>
      )}
    </section>
  )
}
