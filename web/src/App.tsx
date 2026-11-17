import { useEffect } from 'react'
import { forgetGoogleSignIn } from './auth/google.ts'
import { restore, signOut, type Me } from './auth/session.ts'
import { SignInPage } from './auth/SignInPage.tsx'
import { useSession } from './auth/useSession.ts'
import { CardScreen, Retry, Waiting } from './ui/Screen.tsx'
import { HomePage } from './wedding/HomePage.tsx'
import { SetupPage } from './wedding/SetupPage.tsx'
import { useWedding } from './wedding/useWedding.ts'

function leave() {
  forgetGoogleSignIn()
  void signOut()
}

/** Nobody sees anything of the planner without being signed in: every state below starts from the session. */
export default function App() {
  const session = useSession()

  useEffect(() => {
    void restore()
  }, [])

  switch (session.status) {
    case 'restoring':
      return <Waiting what="Loading…" />
    case 'unreachable':
      return (
        <Retry
          message="The server cannot be reached. Check your connection and try again."
          onRetry={() => void restore()}
        />
      )
    case 'signedOut':
      return <SignInPage />
    case 'signedIn':
      return <SignedIn user={session.user} />
  }
}

function SignedIn({ user }: { user: Me }) {
  const { state, reload, show } = useWedding()

  switch (state.status) {
    case 'loading':
      return <Waiting what="Loading…" />
    case 'notSetUp':
      return <SetupPage suggestedName={user.displayName} onDone={show} onAlreadySetUp={reload} />
    case 'ready':
      return <HomePage wedding={state.wedding} onSignOut={leave} />
    case 'full':
      return (
        <CardScreen title="Wedding planner">
          <div className="stack">
            <p role="alert" className="alert">
              This planner already belongs to two people, and you are not one of them.
            </p>
            <button type="button" className="button button-block" onClick={leave}>
              Sign out
            </button>
          </div>
        </CardScreen>
      )
    case 'failed':
      return <Retry message="The wedding could not be loaded. Try again." onRetry={reload} />
  }
}
