import type { ReactNode } from 'react'
import { RingsMark } from './RingsMark.tsx'

/** A single card in the middle of the page: sign-in, setup, and the states in between. */
export function CardScreen({ title, lead, wide = false, children }: {
  title: string
  lead?: string
  wide?: boolean
  children?: ReactNode
}) {
  return (
    <main className="centered">
      <div className={wide ? 'card card-wide' : 'card'}>
        <div className="card-head">
          <RingsMark />
          <h1>{title}</h1>
          {lead && <p className="muted">{lead}</p>}
        </div>
        {children}
      </div>
    </main>
  )
}

/** For the moments when there is nothing to show yet. */
export function Waiting({ what }: { what: string }) {
  return (
    <main className="centered">
      <p role="status" className="muted">
        {what}
      </p>
    </main>
  )
}

/** Something did not work and trying again may help. */
export function Retry({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <CardScreen title="Wedding planner">
      <div className="stack">
        <p role="alert" className="alert">
          {message}
        </p>
        <button type="button" className="button button-block" onClick={onRetry}>
          Try again
        </button>
      </div>
    </CardScreen>
  )
}

/** The same moment inside a page that already has its frame around it. */
export function PageWaiting({ what }: { what: string }) {
  return (
    <p role="status" className="muted page-note">
      {what}
    </p>
  )
}

/** A page inside the frame could not be loaded. */
export function PageRetry({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className="stack page-note">
      <p role="alert" className="alert">
        {message}
      </p>
      <div>
        <button type="button" className="button" onClick={onRetry}>
          Try again
        </button>
      </div>
    </div>
  )
}
