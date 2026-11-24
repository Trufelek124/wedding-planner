import { NavLink, useLocation } from 'react-router'

/** The two ways to look at the ideas. Switching keeps the board and the tag the ideas were narrowed down to. */
export function InspirationViews() {
  const { search } = useLocation()

  return (
    <nav aria-label="Ways to look at the inspirations">
      <ul className="views">
        <li>
          <NavLink to={{ pathname: '/inspirations', search }} end>
            Grid
          </NavLink>
        </li>
        <li>
          <NavLink to={{ pathname: '/inspirations/canvas', search }}>Canvas</NavLink>
        </li>
      </ul>
    </nav>
  )
}
