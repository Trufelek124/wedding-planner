import createClient from 'openapi-fetch'
import type { components, paths } from './generated/schema.ts'

// The types in ./generated come from the backend's API description (backend/api/openapi.json).
// After the API changes: `npm run generate:api`. Never edit the generated file.

export type AppInfo = components['schemas']['AppInfo']
export type Problem = components['schemas']['Problem']

/** An error the server answered with. Decide what to do from `problem.type`, never from the wording. */
export class ApiError extends Error {
  readonly problem: Problem

  constructor(problem: Problem) {
    super(problem.detail ?? problem.title)
    this.name = 'ApiError'
    this.problem = problem
  }
}

export const api = createClient<paths>({
  baseUrl: window.location.origin,
  // Looked up on every call rather than once, so that tests can replace the global fetch.
  fetch: (request) => globalThis.fetch(request),
})
