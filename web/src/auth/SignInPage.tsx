import { useEffect, useState, type FormEvent } from 'react'
import { ApiError, publicApi, unwrap, type Schemas } from '../api/client.ts'
import { Field } from '../ui/Field.tsx'
import { CardScreen, Retry, Waiting } from '../ui/Screen.tsx'
import { GoogleButton } from './GoogleButton.tsx'
import { signInForDevelopment, signInWithGoogle } from './session.ts'

type AuthConfig = Schemas['AuthConfig']

type Config = { status: 'loading' } | { status: 'failed' } | { status: 'ready'; config: AuthConfig }

/** What to tell someone whose sign-in did not work. */
function explain(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.is('urn:wedding-planner:problem:account-not-allowed')) {
      return 'This account cannot open this planner. Sign in with the account you use for the wedding.'
    }
    if (error.is('urn:wedding-planner:problem:unauthenticated')) {
      return 'Google could not confirm who you are. Try again.'
    }
    return 'Signing in did not work. Try again.'
  }
  return 'The server cannot be reached. Check your connection and try again.'
}

export function SignInPage() {
  const [config, setConfig] = useState<Config>({ status: 'loading' })
  const [attempt, setAttempt] = useState(0)
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const [email, setEmail] = useState('')

  useEffect(() => {
    let cancelled = false
    publicApi
      .GET('/api/auth/config')
      .then((result) => {
        if (!cancelled) {
          setConfig({ status: 'ready', config: unwrap(result) })
        }
      })
      .catch(() => {
        if (!cancelled) {
          setConfig({ status: 'failed' })
        }
      })
    return () => {
      cancelled = true
    }
  }, [attempt])

  async function signIn(how: () => Promise<void>) {
    setBusy(true)
    setProblem(null)
    try {
      await how()
    } catch (error) {
      setProblem(explain(error))
      setBusy(false)
    }
  }

  function submitDevelopmentSignIn(event: FormEvent) {
    event.preventDefault()
    void signIn(() => signInForDevelopment(email.trim()))
  }

  if (config.status === 'loading') {
    return <Waiting what="Loading…" />
  }
  if (config.status === 'failed') {
    return (
      <Retry
        message="The server cannot be reached. Check your connection and try again."
        onRetry={() => {
          setConfig({ status: 'loading' })
          setAttempt((n) => n + 1)
        }}
      />
    )
  }

  const { googleClientId, devLoginEnabled } = config.config
  const nothingToSignInWith = googleClientId === null && !devLoginEnabled

  return (
    <CardScreen title="Wedding planner" lead="Sign in to open your plan.">
      <div className="stack">
        {problem !== null && (
          <p role="alert" className="alert">
            {problem}
          </p>
        )}

        {googleClientId !== null && (
          <GoogleButton
            clientId={googleClientId}
            onIdToken={(idToken) => void signIn(() => signInWithGoogle(idToken))}
            onUnavailable={() => setProblem('Google sign-in could not be loaded. Check your connection and reload the page.')}
          />
        )}

        {nothingToSignInWith && (
          <p role="alert" className="alert">
            Signing in is not set up on this server yet.
          </p>
        )}

        {devLoginEnabled && (
          <>
            {googleClientId !== null && <p className="divider">or</p>}
            <form className="stack" onSubmit={submitDevelopmentSignIn}>
              <Field label="Development sign-in" hint="Only on a development server. Use one of the allowed addresses.">
                {(control) => (
                  <input
                    {...control}
                    type="email"
                    name="email"
                    autoComplete="email"
                    required
                    value={email}
                    onChange={(event) => setEmail(event.target.value)}
                  />
                )}
              </Field>
              <button type="submit" className="button button-block" disabled={busy}>
                {busy ? 'Signing in…' : 'Sign in'}
              </button>
            </form>
          </>
        )}
      </div>
    </CardScreen>
  )
}
