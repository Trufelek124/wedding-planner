import { RingsMark } from '../ui/RingsMark.tsx'
import { countdown, daysUntil, longDate } from './dates.ts'
import type { Wedding } from './weddingApi.ts'

interface HomePageProps {
  wedding: Wedding
  onSignOut: () => void
}

/** Where a signed-in owner lands. The plan, the tasks and the rest arrive here as they are built. */
export function HomePage({ wedding, onSignOut }: HomePageProps) {
  const you = wedding.members.find((member) => member.you)
  const names = wedding.members.map((member) => member.displayName).join(' & ')

  return (
    <>
      <header className="topbar">
        <div className="brand">
          <RingsMark />
          Wedding planner
        </div>
        <div className="topbar-user">
          {you && <span className="muted">Signed in as {you.displayName}</span>}
          <button type="button" className="button button-quiet" onClick={onSignOut}>
            Sign out
          </button>
        </div>
      </header>
      <main className="content">
        <div className="hero">
          <h1>{names}</h1>
          {wedding.date === null ? (
            <p className="muted">The day is not fixed yet.</p>
          ) : (
            <>
              <p className="muted">{longDate(wedding.date)}</p>
              <p className="countdown">{countdown(daysUntil(wedding.date, wedding.timeZone))}</p>
            </>
          )}
        </div>
      </main>
    </>
  )
}
