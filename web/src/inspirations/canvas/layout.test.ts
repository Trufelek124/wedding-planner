import { describe, expect, it } from 'vitest'
import {
  CARD_HEIGHT,
  CARD_WIDTH,
  LIMIT,
  MARGIN,
  MAX_SCALE,
  MIN_SCALE,
  clampPlace,
  clampScale,
  draggedPlace,
  panned,
  readingOrder,
  scaledAround,
  showingAll,
  steppedPlace,
  toPlace,
  toScreen,
  type View,
} from './layout.ts'

describe('where a place lies on the surface', () => {
  const views: View[] = [
    { offsetX: 0, offsetY: 0, scale: 1 },
    { offsetX: 120, offsetY: -40, scale: 0.5 },
    { offsetX: -300, offsetY: 75, scale: 2 },
    { offsetX: 17, offsetY: 3, scale: 0.25 },
  ]

  it('goes to the screen and back at every scale', () => {
    for (const view of views) {
      for (const place of [{ x: 0, y: 0 }, { x: 440, y: 260 }, { x: -1000, y: 3000 }]) {
        const back = toPlace(view, toScreen(view, place))
        expect(back.x).toBeCloseTo(place.x, 9)
        expect(back.y).toBeCloseTo(place.y, 9)
      }
    }
  })

  it('puts a place at its scaled distance from the offset', () => {
    expect(toScreen({ offsetX: 120, offsetY: -40, scale: 0.5 }, { x: 440, y: 260 })).toEqual({ x: 340, y: 90 })
  })

  it('moves the view by a distance on the surface', () => {
    expect(panned({ offsetX: 10, offsetY: 20, scale: 2 }, -30, 5)).toEqual({ offsetX: -20, offsetY: 25, scale: 2 })
  })
})

describe('scaling', () => {
  it('keeps the place under the pointer where it is', () => {
    for (const view of [{ offsetX: 0, offsetY: 0, scale: 1 }, { offsetX: -75, offsetY: 210, scale: 0.6 }]) {
      for (const scale of [0.25, 0.5, 1.5, 2]) {
        const around = { x: 330, y: 140 }
        const before = toPlace(view, around)
        const after = toPlace(scaledAround(view, scale, around), around)
        expect(after.x).toBeCloseTo(before.x, 9)
        expect(after.y).toBeCloseTo(before.y, 9)
      }
    }
  })

  it('stays between a quarter and twice the natural size', () => {
    expect(clampScale(0.01)).toBe(MIN_SCALE)
    expect(clampScale(9)).toBe(MAX_SCALE)
    expect(clampScale(1.3)).toBe(1.3)
    const view = { offsetX: 5, offsetY: 5, scale: 1 }
    expect(scaledAround(view, 10, { x: 0, y: 0 }).scale).toBe(2)
    expect(scaledAround(view, 0, { x: 0, y: 0 }).scale).toBe(0.25)
  })

  it('does not move the place under the pointer when it has hit the limit either', () => {
    const view = { offsetX: 0, offsetY: 0, scale: 1.9 }
    const around = { x: 200, y: 100 }
    const before = toPlace(view, around)
    const after = toPlace(scaledAround(view, 5, around), around)
    expect(after.x).toBeCloseTo(before.x, 9)
    expect(after.y).toBeCloseTo(before.y, 9)
  })
})

describe('showing everything', () => {
  const surface = { width: 1000, height: 600 }

  it('shows one card at natural size, in the middle', () => {
    const view = showingAll([{ x: 0, y: 0 }], surface)
    expect(view.scale).toBe(1)
    expect(toScreen(view, { x: 0, y: 0 })).toEqual({ x: (1000 - CARD_WIDTH) / 2, y: (600 - CARD_HEIGHT) / 2 })
  })

  it('does not make a card larger than natural size, wherever it lies', () => {
    const view = showingAll([{ x: -5000, y: 4000 }], surface)
    expect(view.scale).toBe(1)
    const at = toScreen(view, { x: -5000, y: 4000 })
    expect(at.x).toBe((1000 - CARD_WIDTH) / 2)
  })

  it('makes cards far apart smaller until the farthest corners fit inside the margin', () => {
    const places = [{ x: 0, y: 0 }, { x: 2000, y: 100 }]
    const view = showingAll(places, surface)
    expect(view.scale).toBeCloseTo((1000 - 2 * MARGIN) / (2000 + CARD_WIDTH), 9)
    const left = toScreen(view, places[0] as { x: number; y: number })
    const right = toScreen(view, { x: 2000 + CARD_WIDTH, y: 100 + CARD_HEIGHT })
    expect(left.x).toBeGreaterThanOrEqual(MARGIN - 1e-9)
    expect(right.x).toBeLessThanOrEqual(1000 - MARGIN + 1e-9)
    expect(right.y).toBeLessThanOrEqual(600 - MARGIN + 1e-9)
  })

  it('is as tall-limited as it is wide-limited', () => {
    const view = showingAll([{ x: 0, y: 0 }, { x: 0, y: 1500 }], surface)
    expect(view.scale).toBeCloseTo((600 - 2 * MARGIN) / (1500 + CARD_HEIGHT), 9)
  })

  it('never goes below a quarter, even if that leaves something out', () => {
    expect(showingAll([{ x: 0, y: 0 }, { x: 90000, y: 0 }], surface).scale).toBe(MIN_SCALE)
  })

  it('shows natural size at the margin when there is nothing', () => {
    expect(showingAll([], surface)).toEqual({ offsetX: MARGIN, offsetY: MARGIN, scale: 1 })
  })

  it('shows natural size while the surface has no size yet', () => {
    expect(showingAll([{ x: 0, y: 0 }], { width: 0, height: 0 }).scale).toBe(1)
  })
})

describe('moving a card', () => {
  it('goes by the distance on the screen divided by the scale', () => {
    const start = { x: 100, y: 50 }
    expect(draggedPlace(start, 100, -60, 0.5)).toEqual({ x: 300, y: -70 })
    expect(draggedPlace(start, 100, -60, 1)).toEqual({ x: 200, y: -10 })
    expect(draggedPlace(start, 100, -60, 2)).toEqual({ x: 150, y: 20 })
  })

  it('rounds to whole units', () => {
    expect(draggedPlace({ x: 0, y: 0 }, 10, 10, 0.3)).toEqual({ x: 33, y: 33 })
    expect(draggedPlace({ x: 0, y: 0 }, 1, -1, 3)).toEqual({ x: 0, y: -0 })
  })

  it('stays within the limits of a place', () => {
    expect(draggedPlace({ x: 99_900, y: -99_900 }, 1000, -1000, 1)).toEqual({ x: LIMIT, y: -LIMIT })
    expect(clampPlace({ x: 1e9, y: -1e9 })).toEqual({ x: LIMIT, y: -LIMIT })
    expect(steppedPlace({ x: LIMIT - 10, y: 0 }, 20, 20)).toEqual({ x: LIMIT, y: 20 })
  })
})

describe('the order of reaching cards', () => {
  it('goes by rows from the top, each from the left', () => {
    const cards = [
      { name: 'low left', x: 0, y: 300 },
      { name: 'top right', x: 400, y: 10 },
      { name: 'top left', x: 0, y: 0 },
      { name: 'top middle, a little lower', x: 220, y: 60 },
      { name: 'low right', x: 220, y: 280 },
    ]
    expect(readingOrder(cards).map((card) => card.name)).toEqual([
      'top left',
      'top middle, a little lower',
      'top right',
      'low left',
      'low right',
    ])
  })

  it('has none for none', () => {
    expect(readingOrder([])).toEqual([])
  })
})
