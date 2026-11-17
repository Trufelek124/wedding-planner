import { describe, expect, it } from 'vitest'
import { api, ApiError, unwrap } from '../api/client.ts'
import { anna, fakeServer, problem, session, wedding } from '../test-server.ts'
import { refresh, restore, signInForDevelopment, signInWithGoogle, signOut, snapshot } from './session.ts'

const signedOutAnswer = problem(401, 'unauthenticated', 'Sign-in required')

describe('session', () => {
  it('finds the person still signed in when the page loads', async () => {
    fakeServer({ 'POST /api/auth/refresh': session() })

    await expect(restore()).resolves.toBe(true)

    expect(snapshot()).toEqual({ status: 'signedIn', user: anna })
  })

  it('finds nobody signed in', async () => {
    fakeServer({ 'POST /api/auth/refresh': signedOutAnswer })

    await expect(restore()).resolves.toBe(false)

    expect(snapshot()).toEqual({ status: 'signedOut' })
  })

  it('says so when the server does not answer, instead of claiming nobody is signed in', async () => {
    fakeServer({})

    await restore()

    expect(snapshot()).toEqual({ status: 'unreachable' })
  })

  it('signs in with the token Google issued', async () => {
    const server = fakeServer({ 'POST /api/auth/google': session() })

    await signInWithGoogle('id-token-from-google')

    expect(snapshot().status).toBe('signedIn')
    await expect(server.requests('POST /api/auth/google')[0]?.json()).resolves.toEqual({ idToken: 'id-token-from-google' })
  })

  it('passes a refusal on as the problem the server named', async () => {
    fakeServer({ 'POST /api/auth/dev': problem(403, 'account-not-allowed', 'This account may not use this planner') })

    const error = await signInForDevelopment('stranger@example.org').catch((caught: unknown) => caught)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).is('urn:wedding-planner:problem:account-not-allowed')).toBe(true)
    expect(snapshot().status).not.toBe('signedIn')
  })

  it('sends the access token with every call', async () => {
    const server = fakeServer({ 'POST /api/auth/dev': session('access-1'), 'GET /api/wedding': wedding() })
    await signInForDevelopment('anna@example.com')

    await api.GET('/api/wedding')

    expect(server.requests('GET /api/wedding')[0]?.headers.get('Authorization')).toBe('Bearer access-1')
  })

  it('gets a new access token when the old one has expired, and sends the call again', async () => {
    const server = fakeServer({
      'POST /api/auth/dev': session('access-1'),
      'POST /api/auth/refresh': session('access-2'),
      'GET /api/wedding': (request) =>
        request.headers.get('Authorization') === 'Bearer access-2' ? wedding() : signedOutAnswer.clone(),
    })
    await signInForDevelopment('anna@example.com')

    const result = unwrap(await api.GET('/api/wedding'))

    expect(result.currency).toBe('PLN')
    expect(server.calls).toEqual(['POST /api/auth/dev', 'GET /api/wedding', 'POST /api/auth/refresh', 'GET /api/wedding'])
  })

  it('sends a body again too when it repeats a call', async () => {
    const server = fakeServer({
      'POST /api/auth/dev': session('access-1'),
      'POST /api/auth/refresh': session('access-2'),
      'POST /api/wedding': (request) =>
        request.headers.get('Authorization') === 'Bearer access-2' ? wedding() : signedOutAnswer.clone(),
    })
    await signInForDevelopment('anna@example.com')
    const setup = { date: null, currency: 'PLN', timeZone: 'Europe/Warsaw', yourName: 'Anna', partnerName: 'Piotr' }

    await api.POST('/api/wedding', { body: setup })

    const sent = server.requests('POST /api/wedding')
    expect(sent).toHaveLength(2)
    await expect(sent[1]?.json()).resolves.toEqual(setup)
  })

  it('signs the person out when the session cannot be renewed', async () => {
    fakeServer({
      'POST /api/auth/dev': session('access-1'),
      'POST /api/auth/refresh': signedOutAnswer,
      'GET /api/wedding': signedOutAnswer,
    })
    await signInForDevelopment('anna@example.com')

    const result = await api.GET('/api/wedding')

    expect(result.error?.type).toBe('urn:wedding-planner:problem:unauthenticated')
    expect(snapshot()).toEqual({ status: 'signedOut' })
  })

  it('keeps the person signed in when a renewal fails only because the server is unreachable', async () => {
    const server = fakeServer({ 'POST /api/auth/dev': session('access-1') })
    await signInForDevelopment('anna@example.com')
    server.answer('POST /api/auth/refresh', new TypeError('Failed to fetch'))

    await expect(refresh()).resolves.toBe(false)

    expect(snapshot().status).toBe('signedIn')
  })

  it('renews once when several calls find the token expired at the same moment', async () => {
    const server = fakeServer({
      'POST /api/auth/dev': session('access-1'),
      'POST /api/auth/refresh': session('access-2'),
      'GET /api/wedding': (request) =>
        request.headers.get('Authorization') === 'Bearer access-2' ? wedding() : signedOutAnswer.clone(),
      'GET /api/me': (request) =>
        request.headers.get('Authorization') === 'Bearer access-2' ? Response.json(anna) : signedOutAnswer.clone(),
    })
    await signInForDevelopment('anna@example.com')

    const [first, second] = await Promise.all([api.GET('/api/wedding'), api.GET('/api/me')])

    expect(first.error).toBeUndefined()
    expect(second.error).toBeUndefined()
    expect(server.calls.filter((call) => call === 'POST /api/auth/refresh')).toHaveLength(1)
  })

  it('forgets the session on sign-out even when the server cannot be told', async () => {
    fakeServer({ 'POST /api/auth/dev': session('access-1') })
    await signInForDevelopment('anna@example.com')

    await signOut().catch(() => undefined)

    expect(snapshot()).toEqual({ status: 'signedOut' })
  })
})
