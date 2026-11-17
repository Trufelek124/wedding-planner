import { describe, expect, it } from 'vitest'
import { problem, serverAnswers } from '../test-server.ts'
import { fetchAppInfo } from './appInfo.ts'
import { ApiError } from './client.ts'

describe('fetchAppInfo', () => {
  it("returns the server's name and version", async () => {
    const fetchMock = serverAnswers({
      '/api/app-info': Response.json({ name: 'wedding-planner', version: '0.1.0' }),
    })

    await expect(fetchAppInfo()).resolves.toEqual({ name: 'wedding-planner', version: '0.1.0' })

    const request = fetchMock.mock.calls[0]?.[0]
    expect(request).toBeInstanceOf(Request)
    expect((request as Request).method).toBe('GET')
    expect(new URL((request as Request).url).pathname).toBe('/api/app-info')
  })

  it('turns an error answer into an ApiError that carries the problem', async () => {
    serverAnswers({
      '/api/app-info': problem(500, 'urn:wedding-planner:problem:internal-error', 'Something went wrong on the server'),
    })

    const error = await fetchAppInfo().catch((caught: unknown) => caught)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).problem.type).toBe('urn:wedding-planner:problem:internal-error')
    expect((error as ApiError).problem.status).toBe(500)
    expect((error as ApiError).message).toBe('Something went wrong on the server')
  })

  it("uses the problem's detail as the message when there is one", async () => {
    serverAnswers({
      '/api/app-info': problem(404, 'urn:wedding-planner:problem:not-found', 'Not found', 'No such thing.'),
    })

    const error = await fetchAppInfo().catch((caught: unknown) => caught)

    expect((error as ApiError).message).toBe('No such thing.')
  })

  it('fails when the server cannot be reached', async () => {
    serverAnswers({})

    await expect(fetchAppInfo()).rejects.toThrow('Failed to fetch')
  })
})
