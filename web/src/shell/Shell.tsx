import { useEffect, useRef } from 'react'
import { NavigationType, NavLink, Outlet, useLocation, useNavigationType } from 'react-router'
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
]

/** What every page of a signed-in owner shares: the name, the way around, and the way out. */
export function Shell({ onSignOut }: { onSignOut: () => void }) {
  const wedding = useCurrentWedding()
  const you = wedding.members.find((member) => member.you)
  const page = useRef<HTMLElement>(null)
  const { pathname } = useLocation()
  const arrival = useNavigationType()
  const shownPath = useRef(pathname)

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

  return (
    <>
      <header className="topbar">
        <div className="brand">
          <RingsMark />
          Wedding planner
        </div>
        <nav aria-label="Main">
          <ul className="nav">
            {PAGES.map((page) => (
              <li key={page.to}>
                <NavLink to={page.to} end={page.to === '/'}>
                  {page.label}
                </NavLink>
              </li>
            ))}
          </ul>
        </nav>
        <div className="topbar-user">
          {you && <span className="muted">Signed in as {you.displayName}</span>}
          <button type="button" className="button button-quiet" onClick={onSignOut}>
            Sign out
          </button>
        </div>
      </header>
      <main className="content" ref={page} tabIndex={-1}>
        <Outlet />
      </main>
    </>
  )
}
