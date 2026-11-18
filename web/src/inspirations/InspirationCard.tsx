import { Link } from 'react-router'
import { InspirationPicture } from './InspirationPicture.tsx'
import type { Inspiration } from './inspirationsApi.ts'
import { nameOf, sourceName } from './labels.ts'

interface InspirationCardProps {
  inspiration: Inspiration
  /** The name of its board; undefined when it is on none. */
  board: string | undefined
  /** The title of the plan item it belongs to; undefined when it belongs to none. */
  planItem: string | undefined
  /** Where the form returns to. */
  back: string
  onTag: (tag: string) => void
}

/**
 * One idea on the board. Its picture and its name open the original where there is one;
 * "Change" leads to its form. Without a picture the card says where the idea comes from.
 */
export function InspirationCard({ inspiration, board, planItem, back, onTag }: InspirationCardProps) {
  const name = nameOf(inspiration)
  const picture = inspiration.pictures[0]
  const form = `/inspirations/${inspiration.id}`
  const more = inspiration.pictures.length - 1

  const face =
    picture !== undefined ? (
      <InspirationPicture key={picture.id} inspirationId={inspiration.id} picture={picture} size="THUMBNAIL" alt="" ownShape />
    ) : (
      <div className="idea-blank">
        <span className="idea-blank-source">{inspiration.url === null ? 'No picture' : sourceName(inspiration)}</span>
        {inspiration.previewStatus === 'PENDING' && <span role="status">Fetching a preview…</span>}
      </div>
    )

  return (
    <article className="idea" aria-label={name}>
      {inspiration.url !== null ? (
        <a className="idea-face" href={inspiration.url} target="_blank" rel="noopener noreferrer" tabIndex={-1} aria-hidden="true">
          {face}
        </a>
      ) : (
        <Link className="idea-face" to={form} state={{ back }} tabIndex={-1} aria-hidden="true">
          {face}
        </Link>
      )}
      <div className="idea-body">
        <h2 className="idea-name">
          {inspiration.url !== null ? (
            <a href={inspiration.url} target="_blank" rel="noopener noreferrer">
              {name}
              <span className="visually-hidden"> (opens the original in a new tab)</span>
            </a>
          ) : (
            <Link to={form} state={{ back }}>
              {name}
            </Link>
          )}
        </h2>
        <p className="idea-facts">
          {inspiration.url !== null && <span>{sourceName(inspiration)}</span>}
          {board !== undefined && <span>{board}</span>}
          {planItem !== undefined && <span>For {planItem}</span>}
          {more > 0 && <span>{more === 1 ? '1 more picture' : `${more} more pictures`}</span>}
        </p>
        {inspiration.note !== '' && <p className="idea-note">{inspiration.note}</p>}
        {inspiration.tags.length > 0 && (
          <ul className="tags" aria-label="Tags">
            {inspiration.tags.map((tag) => (
              <li key={tag}>
                <button type="button" className="tag" onClick={() => onTag(tag)} aria-label={`Show only “${tag}”`}>
                  {tag}
                </button>
              </li>
            ))}
          </ul>
        )}
        <p className="idea-actions">
          <Link to={form} state={{ back }} aria-label={`Change “${name}”`}>
            Change
          </Link>
        </p>
      </div>
    </article>
  )
}
