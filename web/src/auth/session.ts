import { ApiError, publicApi, setAuthHooks, unwrap, type Schemas } from '../api/client.ts'

export type Me = Schemas['Me']
type SessionAnswer = Schemas['Session']

export type SessionState =
  /** The page has just loaded and is asking the server whether someone is still signed in. */
  | { status: 'restoring' }
  | { status: 'signedOut' }
  /** The server did not answer, so it is unknown whether someone is signed in. */
  | { status: 'unreachable' }
  | { status: 'signedIn'; user: Me }

/** How long before the access token expires a new one is fetched. */
const REFRESH_AHEAD_MS = 60_000

let state: SessionState = { status: 'restoring' }
let accessToken: string | null = null
let refreshTimer: ReturnType<typeof setTimeout> | undefined
let refreshing: Promise<boolean> | null = null
const listeners = new Set<() => void>()

function set(next: SessionState): void {
  state = next
  listeners.forEach((listener) => listener())
}

function signedIn(answer: SessionAnswer): void {
  accessToken = answer.accessToken
  clearTimeout(refreshTimer)
  const wait = Date.parse(answer.accessTokenExpiresAt) - Date.now() - REFRESH_AHEAD_MS
  refreshTimer = setTimeout(() => void refresh(), Math.max(wait, 5_000))
  set({ status: 'signedIn', user: answer.user })
}

function signedOut(): void {
  accessToken = null
  clearTimeout(refreshTimer)
  set({ status: 'signedOut' })
}

/**
 * Exchanges the refresh cookie for a new access token. Calls made at the same moment share one
 * request. Resolves to false when nobody is signed in (any more) or the server did not answer.
 */
export function refresh(): Promise<boolean> {
  refreshing ??= (async () => {
    try {
      signedIn(unwrap(await publicApi.POST('/api/auth/refresh')))
      return true
    } catch (error) {
      if (error instanceof ApiError) {
        signedOut()
      } else if (state.status === 'restoring') {
        set({ status: 'unreachable' })
      }
      return false
    } finally {
      refreshing = null
    }
  })()
  return refreshing
}

/** Called once when the page loads: is someone still signed in from last time? */
export function restore(): Promise<boolean> {
  set({ status: 'restoring' })
  return refresh()
}

/** @throws ApiError when the server refuses, for example `account-not-allowed` */
export async function signInWithGoogle(idToken: string): Promise<void> {
  signedIn(unwrap(await publicApi.POST('/api/auth/google', { body: { idToken } })))
}

/** Only works while the server has its development login switched on. */
export async function signInForDevelopment(email: string): Promise<void> {
  signedIn(unwrap(await publicApi.POST('/api/auth/dev', { body: { email } })))
}

export async function signOut(): Promise<void> {
  try {
    await publicApi.POST('/api/auth/sign-out')
  } finally {
    // Whatever the server said, this browser forgets the session.
    signedOut()
  }
}

export function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export function snapshot(): SessionState {
  return state
}

/** For tests: back to a freshly loaded page. */
export function resetSession(): void {
  clearTimeout(refreshTimer)
  accessToken = null
  refreshing = null
  state = { status: 'restoring' }
}

setAuthHooks({ accessToken: () => accessToken, refresh })
