import createClient from 'openapi-fetch'
import type { components, paths } from './generated/schema.ts'

// The types in ./generated come from the backend's API description (backend/api/openapi.json).
// After the API changes: `npm run generate:api`. Never edit the generated file.

export type Schemas = components['schemas']
export type Problem = Schemas['Problem']
export type ProblemType = Problem['type']

/** An error the server answered with. Decide what to do from `problem.type`, never from the wording. */
export class ApiError extends Error {
  readonly problem: Problem

  constructor(problem: Problem) {
    super(problem.detail ?? problem.title)
    this.name = 'ApiError'
    this.problem = problem
  }

  is(type: ProblemType): boolean {
    return this.problem.type === type
  }

  /** What is wrong with one field of the request, if the server said so. */
  fieldMessage(field: string): string | undefined {
    return this.problem.fieldProblems?.find((problem) => problem.field === field)?.message
  }
}

/** What the session gives the client: the current access token, and a way to get a new one. */
export interface AuthHooks {
  accessToken(): string | null
  /** Resolves to true when a new access token is available. */
  refresh(): Promise<boolean>
}

let auth: AuthHooks = { accessToken: () => null, refresh: () => Promise.resolve(false) }

export function setAuthHooks(hooks: AuthHooks): void {
  auth = hooks
}

function withToken(request: Request): Request {
  const token = auth.accessToken()
  if (token === null) {
    return request
  }
  const headers = new Headers(request.headers)
  headers.set('Authorization', `Bearer ${token}`)
  return new Request(request, { headers })
}

/**
 * Sends a request with the access token. When the server says the token is no longer good,
 * gets a new one once and sends the request again; if that fails the first answer stands.
 */
async function fetchSignedIn(request: Request): Promise<Response> {
  const again = request.clone()
  const first = await globalThis.fetch(withToken(request))
  if (first.status !== 401) {
    return first
  }
  return (await auth.refresh()) ? globalThis.fetch(withToken(again)) : first
}

const baseUrl = window.location.origin

/** For everything that needs a signed-in person. */
export const api = createClient<paths>({ baseUrl, fetch: fetchSignedIn })

/** For the sign-in endpoints themselves, which must not start a refresh of their own. */
export const publicApi = createClient<paths>({
  baseUrl,
  // Looked up on every call rather than once, so that tests can replace the global fetch.
  fetch: (request) => globalThis.fetch(request),
})

/** Turns the `{ data, error }` of a call into the data, or throws what went wrong. */
export function unwrap<T>(result: { data?: T; error?: Problem }): T {
  if (result.error !== undefined) {
    throw new ApiError(result.error)
  }
  return result.data as T
}
