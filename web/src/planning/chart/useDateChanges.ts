import { useEffect, useRef, useState } from 'react'
import { changeEntry, changeOf, type Entry } from '../planningApi.ts'
import type { Dates } from './layout.ts'

function sameDates(a: Pick<Entry, 'startDate' | 'dueDate'>, b: Dates): boolean {
  return a.startDate === b.startDate && a.dueDate === b.dueDate
}

/**
 * New dates for plan items, shown at once and saved behind the scenes.
 *
 * `show` puts dates on the chart without saving them (while a bar is being dragged). `save` sends what is
 * shown; changes that arrive while one is on its way are sent together afterwards, each based on the version
 * the previous save returned, so quick changes in a row never run into each other.
 */
export function useDateChanges(entries: readonly Entry[], after: { saved: () => void; failed: (error: unknown) => void }) {
  /** What the chart shows instead of the saved dates. */
  const [shownDates, setShownDates] = useState<Record<string, Dates>>({})
  /** What a save returned, for as long as the list has not caught up with it. */
  const [saved, setSaved] = useState<Record<string, Entry>>({})

  const wanted = useRef<Record<string, Dates>>({})
  const newest = useRef(new Map<string, Entry>())
  const saving = useRef(new Set<string>())

  useEffect(() => {
    for (const entry of entries) {
      const known = newest.current.get(entry.id)
      if (known === undefined || known.version <= entry.version) {
        newest.current.set(entry.id, entry)
      }
    }
  }, [entries])

  function show(id: string, dates: Dates) {
    wanted.current = { ...wanted.current, [id]: dates }
    setShownDates(wanted.current)
  }

  function forget(id: string) {
    wanted.current = Object.fromEntries(Object.entries(wanted.current).filter(([key]) => key !== id))
    setShownDates(wanted.current)
  }

  async function save(id: string) {
    if (saving.current.has(id)) {
      return // the save on its way looks again when it is done
    }
    saving.current.add(id)
    try {
      for (;;) {
        const entry = newest.current.get(id)
        const dates = wanted.current[id]
        if (entry === undefined || dates === undefined || sameDates(entry, dates)) {
          break
        }
        const answer = await changeEntry(id, changeOf(entry, dates))
        newest.current.set(id, answer)
        setSaved((before) => ({ ...before, [id]: answer }))
      }
      forget(id)
      after.saved()
    } catch (error) {
      forget(id)
      after.failed(error)
    } finally {
      saving.current.delete(id)
    }
  }

  /** The entry as the chart shows it: the newest saved version, with the dates being tried on top. */
  function shown<T extends Entry>(entry: T): T {
    const newer = saved[entry.id]
    const base = newer !== undefined && newer.version > entry.version ? { ...entry, ...newer } : entry
    const dates = shownDates[entry.id]
    return dates === undefined ? base : { ...base, ...dates }
  }

  return { show, forget, save, shown }
}
