import { useCallback, useEffect, useState } from 'react'
import { ApiError } from '../api/client.ts'
import { fetchWedding, type Wedding } from './weddingApi.ts'

export type WeddingState =
  | { status: 'loading' }
  | { status: 'notSetUp' }
  | { status: 'ready'; wedding: Wedding }
  /** A third person signed in: the wedding already has its two members. */
  | { status: 'full' }
  | { status: 'failed' }

/** The wedding of the signed-in person, and a way to load it again. */
export function useWedding(): { state: WeddingState; reload: () => void; show: (wedding: Wedding) => void } {
  const [state, setState] = useState<WeddingState>({ status: 'loading' })
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    fetchWedding(controller.signal)
      .then((wedding) => setState({ status: 'ready', wedding }))
      .catch((error: unknown) => {
        if (controller.signal.aborted) {
          return
        }
        if (error instanceof ApiError && error.is('urn:wedding-planner:problem:wedding-not-set-up')) {
          setState({ status: 'notSetUp' })
        } else if (error instanceof ApiError && error.is('urn:wedding-planner:problem:wedding-full')) {
          setState({ status: 'full' })
        } else {
          setState({ status: 'failed' })
        }
      })
    return () => controller.abort()
  }, [attempt])

  const reload = useCallback(() => {
    setState({ status: 'loading' })
    setAttempt((n) => n + 1)
  }, [])
  const show = useCallback((wedding: Wedding) => setState({ status: 'ready', wedding }), [])

  return { state, reload, show }
}
