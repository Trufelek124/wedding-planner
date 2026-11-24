/**
 * Where things lie on the canvas. Everything here is arithmetic on numbers, so that it is tested without a
 * screen (D-001, D-015): nothing is measured on the page except the surface's own size, which the page gives.
 *
 * A card has a place, the corner of its upper left, in the canvas's own units. How a place reaches the screen
 * is the view: `screen = place * scale + offset`, in pixels from the surface's upper left corner.
 */

/** A card is this wide, in units. */
export const CARD_WIDTH = 200
/** And this tall: the picture's frame has a fixed shape, so that the canvas never has to ask the page. */
export const CARD_HEIGHT = 240
/** The server keeps places within this, either way. */
export const LIMIT = 100_000
export const MIN_SCALE = 0.25
export const MAX_SCALE = 2
/** Room kept free round everything when it is all shown, in pixels. */
export const MARGIN = 24
/** A press that moves less than this, in pixels, has not moved. */
export const SLACK = 4
/** One press of an arrow key on a card, in units; with Shift. */
export const STEP = 20
export const BIG_STEP = 100
/** One press of an arrow key on the surface, in pixels; with Shift. */
export const PAN_STEP = 60
export const BIG_PAN_STEP = 240
/** What one press of a zoom button multiplies the scale by. */
export const ZOOM_STEP = 1.25

export interface Point {
  x: number
  y: number
}

export interface Size {
  width: number
  height: number
}

/** How the canvas is shown: where its origin lies on the surface, and how large it is. */
export interface View {
  offsetX: number
  offsetY: number
  scale: number
}

export const NATURAL: View = { offsetX: 0, offsetY: 0, scale: 1 }

export function clamp(value: number, least: number, most: number): number {
  return Math.min(most, Math.max(least, value))
}

export function clampScale(scale: number): number {
  return clamp(scale, MIN_SCALE, MAX_SCALE)
}

/** A place a card may have: whole numbers within the limits. */
export function clampPlace(place: Point): Point {
  return { x: clamp(Math.round(place.x), -LIMIT, LIMIT), y: clamp(Math.round(place.y), -LIMIT, LIMIT) }
}

/** Where a place of the canvas is on the surface. */
export function toScreen(view: View, place: Point): Point {
  return { x: place.x * view.scale + view.offsetX, y: place.y * view.scale + view.offsetY }
}

/** Which place of the canvas lies under a point of the surface; not rounded. */
export function toPlace(view: View, screen: Point): Point {
  return { x: (screen.x - view.offsetX) / view.scale, y: (screen.y - view.offsetY) / view.scale }
}

/** The view moved by a distance on the surface. */
export function panned(view: View, dx: number, dy: number): View {
  return { ...view, offsetX: view.offsetX + dx, offsetY: view.offsetY + dy }
}

/** The view at another scale (held within its limits) that keeps what lies under `around` where it is. */
export function scaledAround(view: View, scale: number, around: Point): View {
  const next = clampScale(scale)
  const ratio = next / view.scale
  return { scale: next, offsetX: around.x - (around.x - view.offsetX) * ratio, offsetY: around.y - (around.y - view.offsetY) * ratio }
}

/** Where a card lies after being dragged by a distance on the surface: whole units, within the limits. */
export function draggedPlace(start: Point, dx: number, dy: number, scale: number): Point {
  return clampPlace({ x: start.x + dx / scale, y: start.y + dy / scale })
}

/** A card's place moved by a number of units, within the limits. */
export function steppedPlace(place: Point, dx: number, dy: number): Point {
  return clampPlace({ x: place.x + dx, y: place.y + dy })
}

/**
 * The view that shows every place at once, as large as it can be but never larger than natural size,
 * with the whole of it in the middle of the surface. With nothing to show: natural size at the margin.
 */
export function showingAll(places: readonly Point[], surface: Size): View {
  if (places.length === 0 || surface.width <= 0 || surface.height <= 0) {
    return { offsetX: MARGIN, offsetY: MARGIN, scale: 1 }
  }
  const left = Math.min(...places.map((place) => place.x))
  const top = Math.min(...places.map((place) => place.y))
  const width = Math.max(...places.map((place) => place.x)) + CARD_WIDTH - left
  const height = Math.max(...places.map((place) => place.y)) + CARD_HEIGHT - top
  const scale = clamp(Math.min(1, (surface.width - 2 * MARGIN) / width, (surface.height - 2 * MARGIN) / height), MIN_SCALE, 1)
  return {
    scale,
    offsetX: (surface.width - width * scale) / 2 - left * scale,
    offsetY: (surface.height - height * scale) / 2 - top * scale,
  }
}

/**
 * The order in which cards are reached with Tab: by rows from the top, each row from the left. A card is in
 * the row of the one that begins it when it lies less than half a card's height lower.
 */
export function readingOrder<T extends Point>(cards: readonly T[]): T[] {
  const byTop = [...cards].sort((a, b) => a.y - b.y || a.x - b.x)
  const rows: T[][] = []
  let rowTop = 0
  for (const card of byTop) {
    const row = rows.at(-1)
    if (row === undefined || card.y - rowTop >= CARD_HEIGHT / 2) {
      rows.push([card])
      rowTop = card.y
    } else {
      row.push(card)
    }
  }
  return rows.flatMap((row) => row.sort((a, b) => a.x - b.x || a.y - b.y))
}

/** How far apart two pointers are. */
export function distance(a: Point, b: Point): number {
  return Math.hypot(a.x - b.x, a.y - b.y)
}

export function middle(a: Point, b: Point): Point {
  return { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 }
}
