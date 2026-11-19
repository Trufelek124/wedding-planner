import { useEffect, useId, useRef, useState } from 'react'
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router'
import { listCategories } from '../planning/planningApi.ts'
import { useLoaded } from '../planning/useLoaded.ts'
import { PageRetry, PageWaiting } from '../ui/Screen.tsx'
import { listVendors } from '../vendors/vendorsApi.ts'
import { useCurrentWedding } from '../wedding/WeddingContext.ts'
import { decide as send, whatHappened, type Decision, type Notice, type Trouble } from './decisions.ts'
import { NoticeText } from './NoticeText.tsx'
import { Decided, Proposal, Source, type Names } from './parts.tsx'
import {
  KIND_LABEL,
  listSuggestions,
  SHOWN_STATES,
  STATE_LABEL,
  type ShownState,
  type Suggestion,
} from './suggestionsApi.ts'
import { useWaiting } from './WaitingContext.ts'

const NOTICES_KEPT = 3

/** Said once above the part: it is not a list to work through. */
export const ADDED_LEAD =
  'These are already in your plan. Undo what should not be there; what nobody undoes counts as kept after thirty days.'

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
  const [sendingNow, setSending] = useState<{ id: string; decision: Decision } | null>(null)
  const [troubles, setTroubles] = useState<Record<string, Trouble>>({})
  const [notices, setNotices] = useState<Notice[]>([])
  const noticeBox = useRef<HTMLDivElement>(null)
  const lastNotice = notices.at(-1)?.key

  // The form that accepted a suggestion leaves its word here, once.
  const arrived = cameBackWith(location.state)
  useEffect(() => {
    if (arrived !== null) {
      say(whatHappened(arrived))
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
    setSending({ id: suggestion.id, decision })
    setTroubles((before) => Object.fromEntries(Object.entries(before).filter(([key]) => key !== suggestion.id)))
    const outcome = await send(suggestion, decision, wedding.members, wedding.timeZone)
    if ('trouble' in outcome) {
      setTroubles((before) => ({ ...before, [suggestion.id]: outcome.trouble }))
    } else {
      say(outcome.notice)
      // Decided, one way or the other: the card goes at once, the lists read again, and the counts with them.
      if (state === 'WAITING' || state === 'ADDED') {
        suggestions.set({ shown: state, items: shownNow.filter((one) => one.id !== suggestion.id) })
      }
      suggestions.reload()
      waiting.reload()
    }
    sending.current = false
    setSending(null)
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
      <p className="muted suggestions-lead">What the e-mail import found. A payment or a new vendor waits until one of you accepts it; a task or a step it is sure of is added by itself, and you can undo it.{' '}
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
              <NoticeText notice={notice} back={back} />
            </p>
          ))}
        </div>
      )}

      {state === 'ADDED' && <p className="muted suggestions-lead">{ADDED_LEAD}</p>}

      {items.length === 0 ? (
        <p className="empty">
          {
            {
              WAITING: 'Nothing waits for you.',
              ADDED: 'Nothing was added by itself.',
              ACCEPTED: 'Nothing has been accepted yet.',
              REJECTED: 'Nothing has been rejected.',
            }[state]
          }
        </p>
      ) : (
        <ul className="suggestions">
          {items.map((suggestion) => (
            <li key={suggestion.id}>
              <Card
                suggestion={suggestion}
                names={names}
                back={back}
                sending={sendingNow}
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
  sending: { id: string; decision: Decision } | null
  trouble: Trouble | undefined
  onDecide: (decision: Decision) => void
}

function Card({ suggestion, names, back, sending, trouble, onDecide }: CardProps) {
  const heading = useId()
  const busy = sending !== null
  const now = (decision: Decision) => sending?.id === suggestion.id && sending.decision === decision
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

      {suggestion.state === 'ADDED' && (
        <div className="actions suggestion-actions">
          <button type="button" className="button" disabled={busy} onClick={() => onDecide('undo')}>
            {now('undo') ? 'Undoing…' : 'Undo'}
          </button>
          <button type="button" className="button button-quiet" disabled={busy} onClick={() => onDecide('keep')}>
            {now('keep') ? 'Keeping…' : 'Keep'}
          </button>
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
