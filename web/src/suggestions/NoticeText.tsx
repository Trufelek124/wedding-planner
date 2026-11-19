import { Link } from 'react-router'
import type { Notice } from './decisions.ts'

/** What a notice says. The title came out of a mail: it is text here, never markup, and a link only to the planner's own page. */
export function NoticeText({ notice, back }: { notice: Notice; back?: string }) {
  const title =
    notice.title === undefined ? null : notice.link === undefined ? (
      notice.title
    ) : (
      <Link to={notice.link.to} state={back === undefined ? undefined : { back }}>
        {notice.link.label}
      </Link>
    )
  return (
    <>
      {notice.lead}
      {notice.title !== undefined && notice.lead !== '' && ': '}
      {notice.quoted === true ? <>“{title}”</> : title}
      {notice.tail}
      {notice.open !== undefined && (
        <>
          {' '}
          <Link to={notice.open.to} state={back === undefined ? undefined : { back }}>
            {notice.open.label}
          </Link>
        </>
      )}
    </>
  )
}
