import { useEffect, useState } from 'react'
import { fetchBackendStatus, type BackendStatus } from './api/health.ts'

type Status = BackendStatus | 'checking'

const MESSAGE: Record<Status, string> = {
  checking: 'Checking the server…',
  up: 'The server is running.',
  down: 'The server cannot be reached.',
}

export default function App() {
  const [status, setStatus] = useState<Status>('checking')

  useEffect(() => {
    const controller = new AbortController()
    void fetchBackendStatus(controller.signal).then((result) => {
      if (!controller.signal.aborted) {
        setStatus(result)
      }
    })
    return () => {
      controller.abort()
    }
  }, [])

  return (
    <main className="page">
      <h1>Wedding planner</h1>
      <p>This is the first, empty version. It only checks that the server answers.</p>
      <p role="status" className="status" data-status={status}>
        {MESSAGE[status]}
      </p>
    </main>
  )
}
