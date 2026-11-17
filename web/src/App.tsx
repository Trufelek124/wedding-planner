import { useEffect, useState } from 'react'
import { fetchAppInfo } from './api/appInfo.ts'
import { fetchBackendStatus, type BackendStatus } from './api/health.ts'

type Status = BackendStatus | 'checking'

const MESSAGE: Record<Status, string> = {
  checking: 'Checking the server…',
  up: 'The server is running.',
  down: 'The server cannot be reached.',
}

export default function App() {
  const [status, setStatus] = useState<Status>('checking')
  const [version, setVersion] = useState<string | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    void fetchBackendStatus(controller.signal).then((result) => {
      if (!controller.signal.aborted) {
        setStatus(result)
      }
    })
    fetchAppInfo(controller.signal)
      .then((info) => {
        if (!controller.signal.aborted) {
          setVersion(info.version)
        }
      })
      .catch(() => {
        // Without an answer there is no version to show; the status line says the rest.
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
      {version !== null && <p className="version">Server version {version}</p>}
    </main>
  )
}
