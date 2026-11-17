export type BackendStatus = 'up' | 'down'

/** Asks the backend whether it is healthy. Anything short of a clear "UP" counts as down. */
export async function fetchBackendStatus(signal?: AbortSignal): Promise<BackendStatus> {
  try {
    const response = await fetch('/actuator/health', {
      signal,
      headers: { Accept: 'application/json' },
    })
    if (!response.ok) {
      return 'down'
    }
    const body: unknown = await response.json()
    return hasStatusUp(body) ? 'up' : 'down'
  } catch {
    return 'down'
  }
}

function hasStatusUp(body: unknown): boolean {
  return typeof body === 'object' && body !== null && 'status' in body && body.status === 'UP'
}
