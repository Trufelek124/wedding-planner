import { useState } from 'react'
import { setEntryStatus, type Entry, type Status } from './planningApi.ts'

/** A status change that is on its way to the server. */
export interface PendingStatus {
  id: string
  status: Status
}

/**
 * Changes an entry's status from a list (ticking a task off, moving a plan item on). The new
 * status shows at once; then the lists are loaded again, so that what stays on the screen is what
 * the server holds - also when the change did not go through.
 */
export function useStatusChange(reload: () => void) {
  const [pending, setPending] = useState<PendingStatus | null>(null)
  const [failed, setFailed] = useState(false)

  function change(entry: Entry, status: Status) {
    setPending({ id: entry.id, status })
    setFailed(false)
    setEntryStatus(entry.id, status)
      .catch(() => setFailed(true))
      .finally(() => {
        setPending(null)
        reload()
      })
  }

  /** The status to show for an entry: the one on its way, or the one it has. */
  function shown(entry: Entry): Status {
    return pending?.id === entry.id ? pending.status : entry.status
  }

  return { pending, failed, change, shown }
}
