import { useEffect, useMemo, useRef } from 'react'
import { NavigationType, NavLink, Outlet, useLocation, useNavigationType } from 'react-router'
import { useLoaded } from '../planning/useLoaded.ts'
import { listSuggestions } from '../suggestions/suggestionsApi.ts'
import { WaitingContext } from '../suggestions/WaitingContext.ts'
import { RingsMark } from '../ui/RingsMark.tsx'
import { useCurrentWedding } from '../wedding/WeddingContext.ts'

const PAGES = [
  { to: '/', label: 'Home' },
  { to: '/plan', label: 'Plan' },
  { to: '/tasks', label: 'Tasks' },
  { to: '/inspirations', label: 'Inspirations' },
  { to: '/budget', label: 'Budget' },
  { to: '/vendors', label: 'Vendors' },
  { to: '/guests', label: 'Guests' },
  { to: '/suggestions', label: 'Suggestions' },
]

/** What every page of a signed-in owner shares: the name, the way around, and the way out. */
export function Shell({ onSignOut }: { onSignOut: () => void }) {
  const wedding = useCurrentWedding()
  const you = wedding.members.find((member) => member.you)
  const page = useRef<HTMLElement>(null)
  const { pathname } = useLocation()
  const arrival = useNavigationType()
  const shownPath = useRef(pathname)
  const bar = useRef<HTMLUListElement>(null)
  // How many suggestions wait: shown beside the page's name, and read again by whatever decides one.
  const suggestions = useLoaded('waiting-suggestions', (signal) => listSuggestions('WAITING', signal))
  const waiting = useMemo(
    () => ({ count: suggestions.state.status === 'ready' ? suggestions.state.data.length : null, reload: suggestions.reload }),
    [suggestions.state, suggestions.reload],
  )

  // A page opened from a link or a form starts at its top, and the keyboard continues inside it rather than
  // at the link that led here. Going back is left alone: the browser returns to where the reader was.
  useEffect(() => {
    if (shownPath.current === pathname) {
      return
    }
    shownPath.current = pathname
    if (arrival !== NavigationType.Pop) {
      window.scrollTo(0, 0)
      if (!page.current?.contains(document.activeElement)) {
        page.current?.focus({ preventScroll: true })
      }
    }
  }, [pathname, arrival])

  // On a narrow screen the entries are one row that moves sideways: the entry of the shown page is brought into
  // view whenever the page changes and when the window is first drawn. Only the row moves, never the page.
  useEffect(() => {
    bar.current?.querySelector('a[aria-current="page"]')?.scrollIntoView?.({ block: 'nearest', inline: 'center' })
  }, [pathname, waiting.count])

  // The row fades at an edge that has more behind it, so that "more" is seen without a scroll bar.
  useEffect(() => {
    const row = bar.current
    if (!row) {
      return undefined
    }
    const mark = () => {
      row.dataset.before = String(row.scrollLeft > 1)
      row.dataset.after = String(row.scrollLeft + row.clientWidth < row.scrollWidth - 1)
    }
    mark()
    row.addEventListener('scroll', mark, { passive: true })
    window.addEventListener('resize', mark)
    return () => {
      row.removeEventListener('scroll', mark)
      window.removeEventListener('resize', mark)
    }
  }, [])

  return (
    <>
      <header className="topbar">
        <div className="brand">
          <RingsMark />
          Wedding planner
        </div>
        <nav aria-label="Main">
          <ul className="nav" ref={bar}>
            {PAGES.map((page) => (
              <li key={page.to}>
                <NavLink to={page.to} end={page.to === '/'}>
                  {page.label}
                  {page.to === '/suggestions' && waiting.count !== null && waiting.count > 0 && (
                    <>
                      <span className="nav-count" aria-hidden="true">
                        {waiting.count}
                      </span>
                      <span className="visually-hidden">, {waiting.count} waiting</span>
                    </>
                  )}
                </NavLink>
              </li>
            ))}
          </ul>
        </nav>
        <div className="topbar-user">
          {you && (
            <span className="muted">
              <span className="topbar-lead">Signed in as </span>
              {you.displayName}
            </span>
          )}
          <button type="button" className="button button-quiet" onClick={onSignOut}>
            Sign out
          </button>
        </div>
      </header>
      <main className="content" ref={page} tabIndex={-1}>
        <WaitingContext value={waiting}>
          <Outlet />
        </WaitingContext>
      </main>
    </>
  )
}
