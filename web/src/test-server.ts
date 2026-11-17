import { vi } from 'vitest'

type Handler = Response | Error | ((request: Request) => Response | Promise<Response>)

export interface FakeServer {
  /** Every request the page made, in order, as "METHOD /path". */
  calls: string[]
  /** The requests made to one route, for looking at what was sent. */
  requests(route: string): Request[]
  /** Changes what a route answers from now on. */
  answer(route: string, handler: Handler): void
}

/**
 * Replaces the global fetch for one test. Each "METHOD /path" gets its own answer; a route the
 * test did not name fails the way an unreachable server does.
 */
export function fakeServer(routes: Record<string, Handler>): FakeServer {
  const handlers = new Map(Object.entries(routes))
  const seen: { route: string; request: Request }[] = []

  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>(async (input, init) => {
      const request = input instanceof Request ? input : new Request(new URL(input, window.location.origin), init)
      const route = `${request.method} ${new URL(request.url).pathname}`
      seen.push({ route, request: request.clone() })
      const handler = handlers.get(route) ?? new TypeError('Failed to fetch')
      if (handler instanceof Error) {
        throw handler
      }
      // A Response can be read once, so every call gets its own copy.
      return handler instanceof Response ? handler.clone() : handler(request)
    }),
  )

  return {
    get calls() {
      return seen.map((entry) => entry.route)
    },
    requests: (route) => seen.filter((entry) => entry.route === route).map((entry) => entry.request),
    answer: (route, handler) => void handlers.set(route, handler),
  }
}

export function problem(status: number, code: string, title = 'Problem', extra: Record<string, unknown> = {}): Response {
  return new Response(JSON.stringify({ type: `urn:wedding-planner:problem:${code}`, title, status, ...extra }), {
    status,
    headers: { 'Content-Type': 'application/problem+json' },
  })
}

export const anna = { id: '11111111-1111-4111-8111-111111111111', displayName: 'Anna', email: 'anna@example.com' }

/** What the server answers when someone signs in or refreshes. */
export function session(accessToken = 'access-1', expiresInSeconds = 900): Response {
  return Response.json({
    accessToken,
    accessTokenExpiresAt: new Date(Date.now() + expiresInSeconds * 1000).toISOString(),
    refreshToken: null,
    user: anna,
  })
}

export function wedding(overrides: Record<string, unknown> = {}): Response {
  return Response.json({
    id: '22222222-2222-4222-8222-222222222222',
    date: '2027-06-19',
    currency: 'PLN',
    timeZone: 'Europe/Warsaw',
    members: [
      { id: 'm1', displayName: 'Anna', you: true, signedIn: true },
      { id: 'm2', displayName: 'Piotr', you: false, signedIn: false },
    ],
    ...overrides,
  })
}
