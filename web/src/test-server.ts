import { vi } from 'vitest'
import type { Schemas } from './api/client.ts'

type Entry = Schemas['Entry']
type Home = Schemas['Home']

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

export const ANNA_MEMBER = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
export const PIOTR_MEMBER = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'

/** A wedding whose members have real-looking ids, for pages that refer to people. */
export function weddingOfAnnaAndPiotr(): Response {
  return wedding({
    members: [
      { id: ANNA_MEMBER, displayName: 'Anna', you: true, signedIn: true },
      { id: PIOTR_MEMBER, displayName: 'Piotr', you: false, signedIn: true },
    ],
  })
}

export const VENUE = { id: 'cccccccc-cccc-4ccc-8ccc-cccccccccc01', name: 'Venue' }
export const FLOWERS = { id: 'cccccccc-cccc-4ccc-8ccc-cccccccccc02', name: 'Flowers and decor' }

let entryNumber = 0

/** An entry as the server sends it; name only what the test is about. */
export function entry(overrides: Partial<Entry> = {}): Entry {
  entryNumber += 1
  return {
    id: `eeeeeeee-eeee-4eee-8eee-${String(entryNumber).padStart(12, '0')}`,
    kind: 'TASK',
    title: `Entry ${entryNumber}`,
    notes: '',
    categoryId: null,
    startDate: null,
    dueDate: null,
    status: 'NOT_STARTED',
    completedAt: null,
    priorityMode: 'AUTO',
    priority: 'LOW',
    priorityReason: 'NO_DATE',
    assigneeIds: [],
    parentId: null,
    taskCount: 0,
    dependsOnIds: [],
    conflictsWithIds: [],
    version: 0,
    createdAt: '2026-11-17T10:00:00Z',
    updatedAt: '2026-11-17T10:00:00Z',
    ...overrides,
  }
}

/**
 * A server with a signed-in owner, a wedding and the given entries. GET /api/entries answers
 * them narrowed down by kind, as the real one does; the other filters are for the test to check.
 */
/** What the home page is told: the days left, and what is overdue and next. */
export function home(overrides: Partial<Home> = {}): Response {
  return Response.json({ today: '2026-11-17', weddingDate: '2027-06-19', daysLeft: 214, overdue: [], next: [], ...overrides })
}

export function planningServer(entries: Entry[], extra: Record<string, Handler> = {}): FakeServer {
  return fakeServer({
    'GET /api/home': home(),
    'POST /api/auth/refresh': session(),
    'GET /api/wedding': weddingOfAnnaAndPiotr(),
    'GET /api/categories': Response.json([VENUE, FLOWERS]),
    'GET /api/entries': (request) => {
      const kind = new URL(request.url).searchParams.get('kind')
      return Response.json(entries.filter((candidate) => kind === null || candidate.kind === kind))
    },
    ...extra,
  })
}
