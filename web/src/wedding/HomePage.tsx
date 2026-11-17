import { countdown, daysUntil, longDate } from './dates.ts'
import { useCurrentWedding } from './WeddingContext.ts'

/** Where a signed-in owner lands. What needs attention arrives here as it is built. */
export function HomePage() {
  const wedding = useCurrentWedding()
  const names = wedding.members.map((member) => member.displayName).join(' & ')

  return (
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
  )
}
