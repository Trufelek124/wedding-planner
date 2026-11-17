import { useCallback, useEffect, useRef, useState } from 'react'

export type Loaded<T> = { status: 'loading' } | { status: 'failed' } | { status: 'ready'; data: T }

/**
 * Loads something from the server when the page shows and whenever `key` changes.
 * `reload` fetches it again without flashing "loading"; `set` replaces it after a change made here.
 */
export function useLoaded<T>(key: string, load: (signal: AbortSignal) => Promise<T>) {
  const [state, setState] = useState<Loaded<T>>({ status: 'loading' })
  const [attempt, setAttempt] = useState(0)
  const loader = useRef(load)

  useEffect(() => {
    loader.current = load
  })

  useEffect(() => {
    const controller = new AbortController()
    loader
      .current(controller.signal)
      .then((data) => {
        if (!controller.signal.aborted) {
          setState({ status: 'ready', data })
        }
      })
      .catch(() => {
        if (!controller.signal.aborted) {
          setState({ status: 'failed' })
        }
      })
    return () => controller.abort()
  }, [key, attempt])

  const reload = useCallback(() => setAttempt((n) => n + 1), [])
  const retry = useCallback(() => {
    setState({ status: 'loading' })
    setAttempt((n) => n + 1)
  }, [])
  const set = useCallback((data: T) => setState({ status: 'ready', data }), [])

  return { state, reload, retry, set }
}
