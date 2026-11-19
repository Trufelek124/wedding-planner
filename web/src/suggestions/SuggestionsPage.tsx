import { useEffect, useId, useRef, useState } from 'react'
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router'
import { ApiError } from '../api/client.ts'
import { listCategories } from '../planning/planningApi.ts'
import { useLoaded } from '../planning/useLoaded.ts'
import { PageRetry, PageWaiting } from '../ui/Screen.tsx'
import { listVendors } from '../vendors/vendorsApi.ts'
import { useCurrentWedding } from '../wedding/WeddingContext.ts'
import { alreadyDecided, isAlreadyDecided, plainNotice, whatHappened, type Decision, type Notice } from './decisions.ts'
import { Decided, Proposal, Source, type Names } from './parts.tsx'
import {
  acceptSuggestion,
  KIND_LABEL,
  listSuggestions,
  rejectSuggestion,
  SHOWN_STATES,
  STATE_LABEL,
  type ShownState,
  type Suggestion,
} from './suggestionsApi.ts'
import { useWaiting } from './WaitingContext.ts'

/** What goes wrong with one card, said on the card. */
type Trouble = { kind: 'refused'; messages: string[] } | { kind: 'unreachable' } | { kind: 'other'; text: string }

const NOTICES_KEPT = 3

function cameBackWith(state: unknown): Suggestion | null {
  return typeof state === 'object' && state !== null && 'decided' in state ? (state.decided as Suggestion) : null
}

/** What the e-mail import proposes, waiting for one of the two to accept it, change it and accept it, or reject it. */
export function SuggestionsPage() {
  const wedding = useCurrentWedding()
  const waiting = useWaiting()
  const navigate = useNavigate()
  const id = useId()
  const location = useLocation()
  const [params, setParams] = useSearchParams()
  const asked = params.get('state') ?? ''
  const state: ShownState = (SHOWN_STATES as readonly string[]).includes(asked) ? (asked as ShownState) : 'WAITING'

  const suggestions = useLoaded(state, async (signal) => ({ shown: state, items: await listSuggestions(state, signal) }))
  const shownNow = suggestions.state.status === 'ready' ? suggestions.state.data.items : []
  const categories = useLoaded('categories', listCategories)
  const vendors = useLoaded('vendors', (signal) => listVendors({}, signal))

  // A decision is sent once: while one is on its way no other button works, and nothing is sent again by itself.
  const sending = useRef(false)
  const [sendingId, setSendingId] = useState<string | null>(null)
  const [troubles, setTroubles] = useState<Record<string, Trouble>>({})
  const [notices, setNotices] = useState<Notice[]>([])
  const noticeBox = useRef<HTMLDivElement>(null)
  const lastNotice = notices.at(-1)?.key

  // The form that accepted a suggestion leaves its word here, once.
  const arrived = cameBackWith(location.state)
  useEffect(() => {
    if (arrived !== null) {
      setNotices((before) => [...before, whatHappened(arrived)].slice(-NOTICES_KEPT))
      void navigate(`${location.pathname}${location.search}`, { replace: true, state: null })
    }
  }, [arrived, navigate, location.pathname, location.search])

  // After a card is gone the keyboard goes to what was said about it, not to the top of the page.
  useEffect(() => {
    if (lastNotice !== undefined) {
      noticeBox.current?.focus({ preventScroll: true })
    }
  }, [lastNotice])

  function show(next: string) {
    const changed = new URLSearchParams(params)
    if (next === 'WAITING') {
      changed.delete('state')
    } else {
      changed.set('state', next)
    }
    setParams(changed, { replace: true })
  }

  function say(notice: Notice) {
    setNotices((before) => [...before, notice].slice(-NOTICES_KEPT))
  }

  async function decide(suggestion: Suggestion, decision: Decision) {
    if (sending.current) {
      return
    }
    sending.current = true
    setSendingId(suggestion.id)
    setTroubles((before) => Object.fromEntries(Object.entries(before).filter(([key]) => key !== suggestion.id)))
    try {
      const decided = decision === 'accept' ? await acceptSuggestion(suggestion.id) : await rejectSuggestion(suggestion.id)
      say(whatHappened(decided))
    } catch (error) {
      if (isAlreadyDecided(error)) {
        say(await alreadyDecided(suggestion.id, suggestion.title, decision, wedding.members, wedding.timeZone))
      } else if (error instanceof ApiError && error.is('urn:wedding-planner:problem:not-found')) {
        say(plainNotice('alert', 'This suggestion no longer exists', suggestion.title))
      } else if (error instanceof ApiError) {
        const messages = (error.problem.fieldProblems ?? []).map((one) => one.message)
        setTroubles((before) => ({
          ...before,
          [suggestion.id]:
            messages.length > 0
              ? { kind: 'refused', messages }
              : { kind: 'other', text: error.problem.detail ?? 'That did not work. Try again.' },
        }))
        sending.current = false
        setSendingId(null)
        return
      } else {
        setTroubles((before) => ({ ...before, [suggestion.id]: { kind: 'unreachable' } }))
        sending.current = false
        setSendingId(null)
        return
      }
    }
    // Decided, one way or the other: the card goes at once, the lists read again, and the count with them.
    if (state === 'WAITING') {
      suggestions.set({ shown: state, items: shownNow.filter((one) => one.id !== suggestion.id) })
    }
    suggestions.reload()
    waiting.reload()
    sending.current = false
    setSendingId(null)
  }

  if (suggestions.state.status === 'failed' || categories.state.status === 'failed' || vendors.state.status === 'failed') {
    return (
      <PageRetry
        message="The suggestions could not be loaded. Try again."
        onRetry={() => {
          suggestions.retry()
          categories.retry()
          vendors.retry()
        }}
      />
    )
  }
  if (
    suggestions.state.status === 'loading' ||
    categories.state.status === 'loading' ||
    vendors.state.status === 'loading' ||
    // The list of another state is still on its way: its cards would have the wrong buttons.
    suggestions.state.data.shown !== state
  ) {
    return <PageWaiting what="Loading…" />
  }

  const names: Names = {
    categories: new Map(categories.state.data.map((one) => [one.id, one.name])),
    vendors: new Map(vendors.state.data.map((one) => [one.id, one.name])),
  }
  const items = suggestions.state.data.items
  const back = `/suggestions${location.search}`

  return (
    <>
      <div className="page-head">
        <h1>Suggestions</h1>
      </div>
      <p className="muted suggestions-lead">What the e-mail import proposes. Nothing changes in the planner until one of you accepts it.{' '}
        <Link to="/mail">E-mail import</Link>: connect or disconnect your Gmail.
      </p>

      <form className="filters vendor-filters" aria-label="Narrow down" onSubmit={(event) => event.preventDefault()}>
        <div className="filter">
          <label htmlFor={`${id}-state`}>Show</label>
          <select id={`${id}-state`} value={state} onChange={(event) => show(event.target.value)}>
            {SHOWN_STATES.map((one) => (
              <option key={one} value={one}>
                {STATE_LABEL[one]}
              </option>
            ))}
          </select>
        </div>
      </form>

      {notices.length > 0 && (
        <div className="notices" role="group" aria-label="What happened" tabIndex={-1} ref={noticeBox}>
          {notices.map((notice) => (
            <p key={notice.key} className={notice.tone === 'alert' ? 'alert' : 'notice'} role={notice.tone === 'alert' ? 'alert' : 'status'}>
              {notice.lead}
              {notice.title !== undefined && ': '}
              {notice.link === undefined ? notice.title : <Link to={notice.link.to} state={{ back }}>{notice.link.label}</Link>}
            </p>
          ))}
        </div>
      )}

      {items.length === 0 ? (
        <p className="empty">
          {state === 'WAITING' ? 'Nothing waits for you.' : state === 'ACCEPTED' ? 'Nothing has been accepted yet.' : 'Nothing has been rejected.'}
        </p>
      ) : (
        <ul className="suggestions">
          {items.map((suggestion) => (
            <li key={suggestion.id}>
              <Card
                suggestion={suggestion}
                names={names}
                back={back}
                busy={sendingId !== null}
                trouble={troubles[suggestion.id]}
                onDecide={(decision) => void decide(suggestion, decision)}
              />
            </li>
          ))}
        </ul>
      )}
    </>
  )
}

interface CardProps {
  suggestion: Suggestion
  names: Names
  back: string
  busy: boolean
  trouble: Trouble | undefined
  onDecide: (decision: Decision) => void
}

function Card({ suggestion, names, back, busy, trouble, onDecide }: CardProps) {
  const heading = useId()
  return (
    <article className="suggestion" aria-labelledby={heading}>
      <p className="suggestion-kind">{KIND_LABEL[suggestion.kind]}</p>
      <h2 id={heading}>{suggestion.title}</h2>
      <Proposal suggestion={suggestion} names={names} />
      <Source suggestion={suggestion} />

      {suggestion.state !== 'WAITING' && <Decided suggestion={suggestion} back={back} />}

      {trouble !== undefined && (
        <div role="alert" className="alert stack-tight">
          {trouble.kind === 'refused' && (
            <>
              <p>The planner did not take it as proposed:</p>
              <ul>
                {trouble.messages.map((message) => (
                  <li key={message}>{message}</li>
                ))}
              </ul>
              <p>Use “Change and accept” to put it right.</p>
            </>
          )}
          {trouble.kind === 'unreachable' && <p>The server cannot be reached, so nothing was decided. Try again.</p>}
          {trouble.kind === 'other' && <p>{trouble.text}</p>}
        </div>
      )}

      {suggestion.state === 'WAITING' && (
        <div className="actions suggestion-actions">
          <button type="button" className="button" disabled={busy} onClick={() => onDecide('accept')}>
            Accept
          </button>
          <Link className="button button-quiet" to={`/suggestions/${suggestion.id}`} state={{ back }}>
            Change and accept
          </Link>
          <button type="button" className="button button-quiet" disabled={busy} onClick={() => onDecide('reject')}>
            Reject
          </button>
        </div>
      )}
    </article>
  )
}
