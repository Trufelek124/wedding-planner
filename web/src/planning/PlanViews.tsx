import { NavLink, useLocation } from 'react-router'

/** The two ways to look at the plan. Switching keeps what the list was narrowed down to. */
export function PlanViews() {
  const { search } = useLocation()

  return (
    <nav aria-label="Ways to look at the plan">
      <ul className="views">
        <li>
          <NavLink to={{ pathname: '/plan', search }} end>
            List
          </NavLink>
        </li>
        <li>
          <NavLink to={{ pathname: '/plan/chart', search }}>Gantt</NavLink>
        </li>
      </ul>
    </nav>
  )
}
