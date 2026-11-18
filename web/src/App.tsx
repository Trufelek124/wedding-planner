import { useEffect } from 'react'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router'
import { forgetGoogleSignIn } from './auth/google.ts'
import { restore, signOut, type Me } from './auth/session.ts'
import { SignInPage } from './auth/SignInPage.tsx'
import { useSession } from './auth/useSession.ts'
import { BudgetPage } from './budget/BudgetPage.tsx'
import { BudgetPlanPage } from './budget/BudgetPlanPage.tsx'
import { HomePage } from './home/HomePage.tsx'
import { BoardsPage } from './inspirations/BoardsPage.tsx'
import { InspirationFormPage } from './inspirations/InspirationFormPage.tsx'
import { InspirationsPage } from './inspirations/InspirationsPage.tsx'
import { ChartPage } from './planning/ChartPage.tsx'
import { EntryFormPage } from './planning/EntryFormPage.tsx'
import { PlanPage } from './planning/PlanPage.tsx'
import { TasksPage } from './planning/TasksPage.tsx'
import { Shell } from './shell/Shell.tsx'
import { CardScreen, Retry, Waiting } from './ui/Screen.tsx'
import { VendorFormPage } from './vendors/VendorFormPage.tsx'
import { VendorPage } from './vendors/VendorPage.tsx'
import { VendorsPage } from './vendors/VendorsPage.tsx'
import { SetupPage } from './wedding/SetupPage.tsx'
import { useWedding } from './wedding/useWedding.ts'
import { WeddingContext, WeddingUpdateContext } from './wedding/WeddingContext.ts'

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
      return (
        <WeddingContext value={state.wedding}>
          <WeddingUpdateContext value={show}>
            <BrowserRouter>
              <Routes>
                <Route element={<Shell onSignOut={leave} />}>
                  <Route index element={<HomePage />} />
                  <Route path="plan" element={<PlanPage />} />
                  <Route path="plan/chart" element={<ChartPage />} />
                  <Route path="tasks" element={<TasksPage />} />
                  <Route path="entries/new" element={<EntryFormPage />} />
                  <Route path="entries/:id" element={<EntryFormPage />} />
                  <Route path="budget" element={<BudgetPage />} />
                  <Route path="budget/plan" element={<BudgetPlanPage />} />
                  <Route path="vendors" element={<VendorsPage />} />
                  <Route path="vendors/new" element={<VendorFormPage />} />
                  <Route path="vendors/:id" element={<VendorPage />} />
                  <Route path="vendors/:id/edit" element={<VendorFormPage />} />
                  <Route path="inspirations" element={<InspirationsPage />} />
                  <Route path="inspirations/boards" element={<BoardsPage />} />
                  <Route path="inspirations/new" element={<InspirationFormPage />} />
                  <Route path="inspirations/:id" element={<InspirationFormPage />} />
                  {/* An address that names no page leads home. */}
                  <Route path="*" element={<Navigate to="/" replace />} />
                </Route>
              </Routes>
            </BrowserRouter>
          </WeddingUpdateContext>
        </WeddingContext>
      )
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
