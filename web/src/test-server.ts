import { vi } from 'vitest'

type Answer = Response | Error

/**
 * Replaces the global fetch for one test: each path gets its own answer, and a path
 * the test did not name fails the way an unreachable server does.
 */
export function serverAnswers(answers: Record<string, Answer>) {
  const fetchMock = vi.fn<typeof fetch>((input) => {
    const url = new URL(input instanceof Request ? input.url : input, window.location.origin)
    const answer = answers[url.pathname] ?? new TypeError('Failed to fetch')
    // A Response can be read once, so every call gets its own copy.
    return answer instanceof Error ? Promise.reject(answer) : Promise.resolve(answer.clone())
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

export function problem(status: number, type: string, title: string, detail?: string): Response {
  return new Response(JSON.stringify({ type, title, status, detail }), {
    status,
    headers: { 'Content-Type': 'application/problem+json' },
  })
}
