import { useId, useState } from 'react'
import { Link, useLocation, useSearchParams } from 'react-router'
import { useLoaded } from '../planning/useLoaded.ts'
import { PageRetry, PageWaiting } from '../ui/Screen.tsx'
import { useCurrentWedding } from '../wedding/WeddingContext.ts'
import {
  fetchGuestCounts,
  GUEST_STATUS_LABEL,
  GUEST_STATUSES,
  listGuests,
  listHouseholds,
  people,
  setGuestStatus,
  whatComesAlong,
  type Guest,
  type GuestCounts,
  type GuestFilter,
  type GuestStatus,
} from './guestsApi.ts'

/** The choice "a guest of both", as the address spells it. */
const BOTH = 'both'

/** Who is invited, who is coming, and how many people that makes. */
export function GuestsPage() {
  const wedding = useCurrentWedding()
  const id = useId()
  const { search } = useLocation()
  const [params, setParams] = useSearchParams()
  const asked = params.get('status') ?? ''
  const status = (GUEST_STATUSES as string[]).includes(asked) ? (asked as GuestStatus) : ''
  const sideAsked = params.get('side') ?? ''
  const side = sideAsked === BOTH || wedding.members.some((member) => member.id === sideAsked) ? sideAsked : ''

  const query: GuestFilter = {}
  if (status !== '') {
    query.status = [status]
  }
  if (side === BOTH) {
    query.bothSides = true
  } else if (side !== '') {
    query.sideMemberId = side
  }
  const guests = useLoaded(JSON.stringify(query), (signal) => listGuests(query, signal))
  const households = useLoaded('households', listHouseholds)
  const counts = useLoaded('guest-counts', fetchGuestCounts)

  /** A status on its way to the server: shown at once, then the list and the count are read again. */
  const [pending, setPending] = useState<{ id: string; status: GuestStatus } | null>(null)
  const [failed, setFailed] = useState(false)

  function answer(guest: Guest, next: GuestStatus) {
    setPending({ id: guest.id, status: next })
    setFailed(false)
    setGuestStatus(guest.id, next)
      .catch(() => setFailed(true))
      .finally(() => {
        setPending(null)
        guests.reload()
        counts.reload()
      })
  }

  function show(name: 'status' | 'side', value: string) {
    const next = new URLSearchParams(params)
    if (value === '') {
      next.delete(name)
    } else {
      next.set(name, value)
    }
    setParams(next, { replace: true })
  }

  if (guests.state.status === 'failed' || households.state.status === 'failed' || counts.state.status === 'failed') {
    return (
      <PageRetry
        message="The guest list could not be loaded. Try again."
        onRetry={() => {
          guests.retry()
          households.retry()
          counts.retry()
        }}
      />
    )
  }
  if (guests.state.status === 'loading' || households.state.status === 'loading' || counts.state.status === 'loading') {
    return <PageWaiting what="Loading…" />
  }

  const all = guests.state.data
  const back = `/guests${search}`
  const sideName = new Map(wedding.members.map((member) => [member.id, `${member.displayName}’s side`]))
  // Households that have someone on the list as it is narrowed down, by name; then the guests on their own.
  const groups = households.state.data
    .map((household) => ({ key: household.id, name: household.name, guests: all.filter((guest) => guest.householdId === household.id) }))
    .filter((group) => group.guests.length > 0)
  const known = new Set(households.state.data.map((household) => household.id))
  const alone = all.filter((guest) => guest.householdId === null || !known.has(guest.householdId))
  const everybody = counts.state.data.total.guests

  const row = (guest: Guest) => {
    const shown = pending?.id === guest.id ? pending.status : guest.status
    const facts = [guest.sideMemberId === null ? 'Both sides' : sideName.get(guest.sideMemberId), ...whatComesAlong(guest)].filter(
      (fact): fact is string => fact !== undefined,
    )
    return (
      <li key={guest.id} className="entry guest-row" data-done={shown === 'DECLINED'}>
        <div className="entry-body">
          <Link className="entry-title" to={`/guests/${guest.id}`} state={{ back }}>
            {guest.name}
          </Link>
          <p className="entry-meta">
            {guest.heads > 1 && <span className="entry-money">{people(guest.heads)}</span>}
            {facts.map((fact) => (
              <span key={fact}>{fact}</span>
            ))}
          </p>
        </div>
        <select
          className="status-select"
          aria-label={`Status of ${guest.name}`}
          value={shown}
          disabled={pending?.id === guest.id}
          onChange={(event) => answer(guest, event.target.value as GuestStatus)}
        >
          {GUEST_STATUSES.map((one) => (
            <option key={one} value={one}>
              {GUEST_STATUS_LABEL[one]}
            </option>
          ))}
        </select>
      </li>
    )
  }

  return (
    <div className="guests-page">
      <div className="page-head">
        <h1>Guests</h1>
        <Link className="button" to="/guests/new" state={{ back }}>
          Add a guest
        </Link>
      </div>

      {everybody > 0 && <HeadCountTable counts={counts.state.data} />}

      <form className="filters guest-filters" aria-label="Narrow down" onSubmit={(event) => event.preventDefault()}>
        <div className="filter">
          <label htmlFor={`${id}-status`}>Status</label>
          <select id={`${id}-status`} value={status} onChange={(event) => show('status', event.target.value)}>
            <option value="">Any</option>
            {GUEST_STATUSES.map((one) => (
              <option key={one} value={one}>
                {GUEST_STATUS_LABEL[one]}
              </option>
            ))}
          </select>
        </div>
        <div className="filter">
          <label htmlFor={`${id}-side`}>Side</label>
          <select id={`${id}-side`} value={side} onChange={(event) => show('side', event.target.value)}>
            <option value="">Any</option>
            {wedding.members.map((member) => (
              <option key={member.id} value={member.id}>
                {member.displayName}’s side
              </option>
            ))}
            <option value={BOTH}>Both sides</option>
          </select>
        </div>
        <p className="filter-link">
          <Link to="/guests/households" state={{ back }}>
            Edit the households
          </Link>
        </p>
      </form>

      {failed && (
        <p role="alert" className="alert">
          That change was not saved. Try again.
        </p>
      )}

      {all.length === 0 ? (
        <p className="empty">
          {everybody === 0 ? 'Nobody on the list yet. Add the first guest.' : 'Nobody matches. Choose another status or side.'}
        </p>
      ) : groups.length === 0 ? (
        <ul className="entries">{alone.map(row)}</ul>
      ) : (
        <>
          {groups.map((group) => (
            <section key={group.key} className="group" aria-label={group.name}>
              <h2>
                {group.name} <span className="group-count">{people(group.guests.reduce((sum, guest) => sum + guest.heads, 0))}</span>
              </h2>
              <ul className="entries">{group.guests.map(row)}</ul>
            </section>
          ))}
          {alone.length > 0 && (
            <section className="group" aria-label="On their own">
              <h2>On their own</h2>
              <ul className="entries">{alone.map(row)}</ul>
            </section>
          )}
        </>
      )}
    </div>
  )
}

/**
 * How many people, status by status and side by side. A table, because that is what it is: the
 * statuses down the side, whose guests across, so that it fits a phone as well.
 */
function HeadCountTable({ counts }: { counts: GuestCounts }) {
  const wedding = useCurrentWedding()
  const confirmed = counts.byStatus.find((line) => line.status === 'CONFIRMED')?.count
  const sideName = (sideMemberId: string | null) =>
    sideMemberId === null ? 'Both' : (wedding.members.find((member) => member.id === sideMemberId)?.displayName ?? 'Other')

  return (
    <section className="head-count" aria-labelledby="head-count">
      <h2 id="head-count">Head count</h2>
      {confirmed !== undefined && (
        <p className="head-count-lead">
          <strong>{people(confirmed.heads)}</strong> confirmed
          {confirmed.heads > 0 && (
            <span className="muted">
              {' '}
              — {confirmed.guests === 1 ? '1 guest' : `${confirmed.guests} guests`}
              {confirmed.plusOnes > 0 && `, ${confirmed.plusOnes === 1 ? '1 plus-one' : `${confirmed.plusOnes} plus-ones`}`}
              {confirmed.children > 0 && `, ${confirmed.children === 1 ? '1 child' : `${confirmed.children} children`}`}
            </span>
          )}
        </p>
      )}
      <table className="counts">
        <caption>People, with plus-ones and children counted.</caption>
        <thead>
          <tr>
            <td />
            {counts.bySide.map((side) => (
              <th key={side.sideMemberId ?? 'both'} scope="col">
                {sideName(side.sideMemberId)}
              </th>
            ))}
            <th scope="col">All</th>
          </tr>
        </thead>
        <tbody>
          {counts.byStatus.map((line, place) => (
            <tr key={line.status}>
              <th scope="row">{(GUEST_STATUS_LABEL as Partial<Record<string, string>>)[line.status] ?? line.status}</th>
              {counts.bySide.map((side) => (
                <td key={side.sideMemberId ?? 'both'}>{side.byStatus[place]?.count.heads ?? 0}</td>
              ))}
              <td>{line.count.heads}</td>
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr>
            <th scope="row">On the list</th>
            {counts.bySide.map((side) => (
              <td key={side.sideMemberId ?? 'both'}>{side.count.heads}</td>
            ))}
            <td>{counts.total.heads}</td>
          </tr>
        </tfoot>
      </table>
    </section>
  )
}
