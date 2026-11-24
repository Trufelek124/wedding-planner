import { memo, type MouseEvent } from 'react'
import { Link } from 'react-router'
import { InspirationPicture } from '../InspirationPicture.tsx'
import type { Inspiration } from '../inspirationsApi.ts'
import { nameOf, sourceName } from '../labels.ts'
import { CARD_HEIGHT, CARD_WIDTH } from './layout.ts'

interface CanvasCardProps {
  inspiration: Inspiration
  /** Where it lies, in the canvas's units. */
  x: number
  y: number
  /** Its place in the order of drawing: the one moved last has the highest. */
  layer: number
  /** Where the idea's own page returns to. */
  back: string
  /** Whether the click that follows a drag is to be dropped. */
  dragged: () => boolean
  /** Whether it is being moved right now. */
  moving: boolean
}

/**
 * One idea on the canvas, small: its picture, or the words that stand for one, its name and where it comes
 * from. It is one link to the idea's page, so that Enter opens it; dragging and the arrow keys are the
 * canvas's. Everything shown is plain text.
 */
export const CanvasCard = memo(function CanvasCard({ inspiration, x, y, layer, back, dragged, moving }: CanvasCardProps) {
  const name = nameOf(inspiration)
  const source = sourceName(inspiration)
  const picture = inspiration.pictures[0]

  function click(event: MouseEvent) {
    // Letting go of a card that was dragged is not a press on it.
    if (dragged()) {
      event.preventDefault()
    }
  }

  return (
    <Link
      className="canvas-card"
      to={`/inspirations/${inspiration.id}`}
      state={{ back }}
      data-card={inspiration.id}
      data-moving={moving || undefined}
      aria-label={`${name}, ${source}`}
      draggable={false}
      onClick={click}
      style={{ left: x, top: y, width: CARD_WIDTH, height: CARD_HEIGHT, zIndex: layer }}
    >
      <span className="canvas-card-face">
        {picture !== undefined ? (
          <InspirationPicture key={picture.id} inspirationId={inspiration.id} picture={picture} size="THUMBNAIL" alt="" />
        ) : (
          <span className="canvas-card-blank">{inspiration.url === null ? 'No picture' : source}</span>
        )}
      </span>
      <span className="canvas-card-name">{name}</span>
      <span className="canvas-card-source">{source}</span>
    </Link>
  )
})
