import { vi } from 'vitest'
import type { Schemas } from './api/client.ts'

type Entry = Schemas['Entry']
type Home = Schemas['Home']
type Budget = Schemas['Budget']
type Vendor = Schemas['Vendor']
type Guest = Schemas['Guest']
type GuestCounts = Schemas['GuestCounts']
type HeadCount = Schemas['HeadCount']
type Inspiration = Schemas['Inspiration']
type Picture = Schemas['Picture']
type Board = Schemas['Board']

export type Handler = Response | Error | ((request: Request) => Response | Promise<Response>)

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
    vendorId: null,
    amount: null,
    paymentKind: null,
    paidOn: null,
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
  return Response.json({
    today: '2026-11-17',
    weddingDate: '2027-06-19',
    daysLeft: 214,
    overdue: [],
    next: [],
    budget: { total: null, planned: 0, committed: 0, paid: 0 },
    nextPayments: [],
    ...overrides,
  })
}

export function planningServer(entries: Entry[], extra: Record<string, Handler> = {}): FakeServer {
  return fakeServer({
    'GET /api/home': home(),
    'POST /api/auth/refresh': session(),
    'GET /api/wedding': weddingOfAnnaAndPiotr(),
    'GET /api/categories': Response.json([VENUE, FLOWERS]),
    'GET /api/vendors': Response.json([]),
    'GET /api/suggestions': Response.json([]),
    'GET /api/entries': (request) => {
      const kind = new URL(request.url).searchParams.get('kind')
      return Response.json(entries.filter((candidate) => kind === null || candidate.kind === kind))
    },
    ...extra,
  })
}

export const DRESS: Board = { id: 'dddddddd-dddd-4ddd-8ddd-dddddddddd01', name: 'Dress', inspirationCount: 2 }
export const CAKE: Board = { id: 'dddddddd-dddd-4ddd-8ddd-dddddddddd02', name: 'Cake', inspirationCount: 0 }

let inspirationNumber = 0
let pictureNumber = 0

/** A picture's record as the server sends it. */
export function picture(overrides: Partial<Picture> = {}): Picture {
  pictureNumber += 1
  return {
    id: `ffffffff-ffff-4fff-8fff-${String(pictureNumber).padStart(12, '0')}`,
    contentType: 'image/jpeg',
    width: 800,
    height: 1000,
    preview: false,
    ...overrides,
  }
}

/** An inspiration as the server sends it; name only what the test is about. */
export function inspiration(overrides: Partial<Inspiration> = {}): Inspiration {
  inspirationNumber += 1
  return {
    id: `99999999-9999-4999-8999-${String(inspirationNumber).padStart(12, '0')}`,
    url: `https://example.com/idea-${inspirationNumber}`,
    source: 'WEB',
    title: '',
    note: '',
    tags: [],
    boardId: null,
    planItemId: null,
    vendorId: null,
    addedById: ANNA_MEMBER,
    previewStatus: 'NONE',
    previewTitle: '',
    pictures: [],
    version: 0,
    createdAt: '2026-11-18T10:00:00Z',
    updatedAt: '2026-11-18T10:00:00Z',
    ...overrides,
  }
}

/** The bytes of a picture, as far as a test needs them: jsdom shows no picture anyway. */
export function pictureBytes(): Response {
  return new Response(new Uint8Array([0xff, 0xd8, 0xff, 0xe0]), { headers: { 'Content-Type': 'image/jpeg' } })
}

/**
 * A server with a signed-in owner, a wedding, two boards and the given inspirations. Every picture of
 * theirs can be fetched. The list is not narrowed down: what was asked for is for the test to check.
 */
export function inspirationsServer(inspirations: Inspiration[], extra: Record<string, Handler> = {}): FakeServer {
  const tags = new Map<string, number>()
  const pictures: Record<string, Handler> = {}
  for (const one of inspirations) {
    for (const tag of one.tags) {
      tags.set(tag, (tags.get(tag) ?? 0) + 1)
    }
    for (const its of one.pictures) {
      pictures[`GET /api/inspirations/${one.id}/pictures/${its.id}`] = pictureBytes
    }
  }
  return fakeServer({
    'GET /api/home': home(),
    'POST /api/auth/refresh': session(),
    'GET /api/wedding': weddingOfAnnaAndPiotr(),
    'GET /api/boards': Response.json([CAKE, DRESS]),
    'GET /api/inspirations': Response.json(inspirations),
    'GET /api/inspirations/tags': Response.json([...tags].map(([tag, count]) => ({ tag, count }))),
    'GET /api/entries': Response.json([]),
    'GET /api/vendors': Response.json([]),
    'GET /api/suggestions': Response.json([]),
    ...pictures,
    ...extra,
  })
}

/** A budget as the server sends it: both test categories with nothing in them, unless the test says otherwise. */
export function budget(overrides: Partial<Budget> = {}): Budget {
  return {
    total: null,
    planned: 0,
    committed: 0,
    paid: 0,
    categories: [
      { categoryId: VENUE.id, name: VENUE.name, planned: null, committed: 0, paid: 0, payments: 0 },
      { categoryId: FLOWERS.id, name: FLOWERS.name, planned: null, committed: 0, paid: 0, payments: 0 },
    ],
    uncategorised: { committed: 0, paid: 0, payments: 0 },
    version: 0,
    ...overrides,
  }
}

let vendorNumber = 0

/** A vendor as the server sends it, with made-up details; name only what the test is about. */
export function vendor(overrides: Partial<Vendor> = {}): Vendor {
  vendorNumber += 1
  return {
    id: `aaaaaaaa-0000-4000-8000-${String(vendorNumber).padStart(12, '0')}`,
    name: `Vendor ${vendorNumber}`,
    categoryId: null,
    status: 'CONSIDERING',
    contactPerson: '',
    phone: '',
    email: '',
    website: '',
    address: '',
    agreedPrice: null,
    notes: '',
    planItems: 0,
    tasks: 0,
    payments: 0,
    committed: 0,
    paid: 0,
    inspirations: 0,
    version: 0,
    createdAt: '2026-11-18T10:00:00Z',
    updatedAt: '2026-11-18T10:00:00Z',
    ...overrides,
  }
}

let guestNumber = 0

/** A guest as the server sends it, made up; name only what the test is about. */
export function guest(overrides: Partial<Guest> = {}): Guest {
  guestNumber += 1
  const plusOne = overrides.plusOne ?? false
  const children = overrides.children ?? 0
  return {
    id: `bbbbbbbb-0000-4000-8000-${String(guestNumber).padStart(12, '0')}`,
    name: `Guest ${guestNumber}`,
    sideMemberId: null,
    householdId: null,
    status: 'NOT_INVITED',
    plusOne,
    children,
    dietaryNeeds: '',
    needsAccommodation: false,
    phone: '',
    email: '',
    notes: '',
    heads: 1 + (plusOne ? 1 : 0) + children,
    version: 0,
    createdAt: '2026-11-18T10:00:00Z',
    updatedAt: '2026-11-18T10:00:00Z',
    ...overrides,
  }
}

const GUEST_STATUSES = ['NOT_INVITED', 'INVITED', 'CONFIRMED', 'DECLINED', 'MAYBE'] as const

/** The head count the server would give for these guests: every status and every side, also with nobody in it. */
export function countsOf(guests: Guest[]): GuestCounts {
  const count = (some: Guest[]): HeadCount => {
    const plusOnes = some.filter((one) => one.plusOne).length
    const children = some.reduce((sum, one) => sum + one.children, 0)
    return { guests: some.length, plusOnes, children, heads: some.length + plusOnes + children }
  }
  return {
    total: count(guests),
    byStatus: GUEST_STATUSES.map((status) => ({ status, count: count(guests.filter((one) => one.status === status)) })),
    bySide: [ANNA_MEMBER, PIOTR_MEMBER, null].map((sideMemberId) => {
      const theirs = guests.filter((one) => one.sideMemberId === sideMemberId)
      return {
        sideMemberId,
        count: count(theirs),
        byStatus: GUEST_STATUSES.map((status) => ({ status, count: count(theirs.filter((one) => one.status === status)) })),
      }
    }),
  }
}

let suggestionNumber = 0

/** A suggestion as the server sends it, from a made-up sender at an `.example` address; name only what the test is about. */
export function suggestion(overrides: Partial<Schemas['Suggestion']> = {}): Schemas['Suggestion'] {
  suggestionNumber += 1
  return {
    id: `55555555-5555-4555-8555-${String(suggestionNumber).padStart(12, '0')}`,
    kind: 'TASK',
    state: 'WAITING',
    title: `Suggestion ${suggestionNumber}`,
    notes: '',
    startDate: null,
    dueDate: null,
    amount: null,
    paymentKind: null,
    categoryId: null,
    vendorId: null,
    vendorName: null,
    vendorEmail: null,
    vendorPhone: null,
    vendorWebsite: null,
    senderName: 'Jan from Palace on the Hill',
    senderAddress: 'jan@palace.example',
    subject: 'Your booking',
    receivedAt: '2026-11-12T13:05:00Z',
    inboxMemberId: ANNA_MEMBER,
    mailLink: 'https://mail.google.com/mail/u/0/#all/abc123',
    passage: 'Please send the signed form by the end of the month.',
    confidence: 62,
    decidedBy: null,
    decidedAt: null,
    madeEntryId: null,
    madeVendorId: null,
    createdAt: '2026-11-12T13:06:00Z',
    ...overrides,
  }
}
