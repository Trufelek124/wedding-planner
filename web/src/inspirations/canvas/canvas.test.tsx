import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../../App.tsx'
import { DRESS, inspiration, inspirationsServer, picture, problem, type FakeServer, type Handler } from '../../test-server.ts'
import type { Inspiration } from '../inspirationsApi.ts'
import { forgetPictures } from '../pictures.ts'
import { KEYS_REST_MS } from './Canvas.tsx'

// The surface is 1000 by 600 pixels. jsdom lays nothing out, so the page is told, as the browser would tell it.
const WIDTH = 1000
const HEIGHT = 600

let addresses = 0

beforeEach(() => {
  addresses = 0
  vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(WIDTH)
  vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(HEIGHT)
  vi.spyOn(URL, 'createObjectURL').mockImplementation(() => `blob:picture-${(addresses += 1)}`)
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined)
})

afterEach(() => {
  forgetPictures()
  vi.useRealTimers()
})

function open(path: string) {
  window.history.pushState(null, '', path)
  render(<App />)
}

/** An idea lying at a place; its name is what the test looks for. */
function idea(title: string, x: number, y: number, more: Partial<Inspiration> = {}): Inspiration {
  return inspiration({ title, x, y, url: null, source: 'PHOTO', ...more })
}

/** A server that answers a move the way the real one does: the same idea at the new place, moved just now. */
function placing(ideas: Inspiration[], extra: Record<string, Handler> = {}): FakeServer {
  const routes: Record<string, Handler> = {}
  for (const one of ideas) {
    routes[`PUT /api/inspirations/${one.id}/place`] = moves(one)
  }
  return inspirationsServer(ideas, { ...routes, ...extra })
}

function moves(one: Inspiration) {
  return async (request: Request) => {
    const sent = (await request.json()) as { x: number; y: number }
    return Response.json({ ...one, ...sent, placedAt: '2026-11-24T12:00:00Z' })
  }
}

function sentTo(server: FakeServer, one: Inspiration): Promise<unknown[]> {
  return Promise.all(server.requests(`PUT /api/inspirations/${one.id}/place`).map((request) => request.json()))
}

/** The card of an idea, once the canvas has drawn it. */
async function card(name: string): Promise<HTMLElement> {
  return screen.findByRole('link', { name: new RegExp(`^${name},`) })
}

function surface(): HTMLElement {
  return screen.getByRole('group', { name: 'Canvas of inspirations' })
}

/** How the surface is shown now, read from what the page drew. */
function viewOf(): { offsetX: number; offsetY: number; scale: number } {
  const plane = surface().querySelector<HTMLElement>('.canvas-plane')
  const found = /translate\((-?[\d.e-]+)px, (-?[\d.e-]+)px\) scale\(([\d.e-]+)\)/.exec(plane?.style.transform ?? '')
  if (found === null) {
    throw new Error(`The plane is drawn as ${plane?.style.transform ?? 'nothing'}`)
  }
  return { offsetX: Number(found[1]), offsetY: Number(found[2]), scale: Number(found[3]) }
}

/** Where a card's upper left corner is on the surface, in pixels. */
function onScreen(element: HTMLElement): { x: number; y: number } {
  const view = viewOf()
  return { x: parseFloat(element.style.left) * view.scale + view.offsetX, y: parseFloat(element.style.top) * view.scale + view.offsetY }
}

function placeOf(element: HTMLElement): { x: number; y: number } {
  return { x: parseFloat(element.style.left), y: parseFloat(element.style.top) }
}

/** Takes hold of something, moves the pointer by a distance in pixels, and lets go. */
function drag(target: Element, dx: number, dy: number, pointerId = 1) {
  fireEvent.pointerDown(target, { clientX: 300, clientY: 200, button: 0, pointerId })
  fireEvent.pointerMove(window, { clientX: 300 + dx, clientY: 200 + dy, pointerId })
  fireEvent.pointerUp(window, { clientX: 300 + dx, clientY: 200 + dy, pointerId })
}

describe('the two views', () => {
  it('has Grid and Canvas side by side, the canvas at an address of its own, and keeps the filters when it changes', async () => {
    placing([idea('Lace sleeves', 0, 0, { tags: ['lace'] })])
    open(`/inspirations?board=${DRESS.id}&tag=lace`)

    const views = await screen.findByRole('navigation', { name: 'Ways to look at the inspirations' })
    expect(within(views).getByRole('link', { name: 'Grid' })).toHaveAttribute('aria-current', 'page')
    expect(within(views).getByRole('link', { name: 'Canvas' })).toHaveAttribute('href', `/inspirations/canvas?board=${DRESS.id}&tag=lace`)

    await userEvent.click(within(views).getByRole('link', { name: 'Canvas' }))
    expect(await screen.findByRole('group', { name: 'Canvas of inspirations' })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/inspirations/canvas')
    expect(window.location.search).toBe(`?board=${DRESS.id}&tag=lace`)
    expect(screen.getByLabelText('Tag')).toHaveValue('lace')
    expect(within(views).getByRole('link', { name: 'Canvas' })).toHaveAttribute('aria-current', 'page')
    // The head is the same: the button that adds an idea and the way to the boards.
    expect(screen.getByRole('link', { name: 'Add an inspiration' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Add or change boards' })).toBeInTheDocument()

    await userEvent.click(within(views).getByRole('link', { name: 'Grid' }))
    expect(await screen.findByRole('article', { name: 'Lace sleeves' })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/inspirations')
    expect(window.location.search).toBe(`?board=${DRESS.id}&tag=lace`)
  })

  it('narrows the canvas by the same filters, in the address', async () => {
    const server = placing([idea('Lace sleeves', 0, 0, { tags: ['lace'] })])
    open('/inspirations/canvas')

    await userEvent.selectOptions(await screen.findByLabelText('Board'), 'Dress (2)')
    await waitFor(() => expect(window.location.search).toBe(`?board=${DRESS.id}`))
    await waitFor(() => expect(server.requests('GET /api/inspirations').length).toBeGreaterThan(1))
    expect(new URL(server.requests('GET /api/inspirations').at(-1)?.url ?? 'http://x').searchParams.get('boardId')).toBe(DRESS.id)
    expect(window.location.pathname).toBe('/inspirations/canvas')
  })
})

describe('the cards on the surface', () => {
  it('draws every card at its place, in the order it was moved, the last one on top', async () => {
    placing([
      idea('Moved first', 0, 0, { placedAt: '2026-11-20T10:00:00Z' }),
      idea('Moved last', 100, 50, { placedAt: '2026-11-23T10:00:00Z' }),
      idea('Moved in between', 40, 20, { placedAt: '2026-11-21T10:00:00Z' }),
    ])
    open('/inspirations/canvas')

    const [first, last, between] = [await card('Moved first'), await card('Moved last'), await card('Moved in between')]
    expect(placeOf(first)).toEqual({ x: 0, y: 0 })
    expect(placeOf(last)).toEqual({ x: 100, y: 50 })
    expect(placeOf(between)).toEqual({ x: 40, y: 20 })
    expect(Number(last.style.zIndex)).toBeGreaterThan(Number(between.style.zIndex))
    expect(Number(between.style.zIndex)).toBeGreaterThan(Number(first.style.zIndex))
    // A card is 200 units wide.
    expect(first.style.width).toBe('200px')
  })

  it('opens showing everything, and never larger than natural size', async () => {
    placing([idea('Alone', 3000, -2000)])
    open('/inspirations/canvas')
    const alone = await card('Alone')
    expect(viewOf().scale).toBe(1)
    // In the middle of the surface.
    expect(onScreen(alone)).toEqual({ x: (WIDTH - 200) / 2, y: (HEIGHT - 240) / 2 })
  })

  it('opens with cards far apart smaller, all of them inside the surface', async () => {
    placing([idea('Far left', -1500, 0), idea('Far right', 1500, 900)])
    open('/inspirations/canvas')
    const right = await card('Far right')
    const left = await card('Far left')

    expect(viewOf().scale).toBeLessThan(1)
    expect(viewOf().scale).toBeGreaterThanOrEqual(0.25)
    for (const one of [left, right]) {
      const at = onScreen(one)
      expect(at.x).toBeGreaterThanOrEqual(0)
      expect(at.y).toBeGreaterThanOrEqual(0)
      expect(at.x + 200 * viewOf().scale).toBeLessThanOrEqual(WIDTH)
      expect(at.y + 240 * viewOf().scale).toBeLessThanOrEqual(HEIGHT)
    }
  })

  it('names the surface, says how it is used, and names every card by the idea and where it comes from', async () => {
    placing([idea('Lace sleeves', 0, 0), idea('Peonies', 220, 0, { url: 'https://www.instagram.com/p/x/', source: 'INSTAGRAM' })])
    open('/inspirations/canvas')

    expect(await screen.findByRole('link', { name: 'Lace sleeves, Photo' })).toBeInTheDocument()
    expect(surface()).toHaveAttribute('tabindex', '0')
    expect(surface()).toHaveAccessibleDescription(/Drag a card to move it/)
    expect(surface()).toHaveAccessibleDescription(/arrow keys/)
    expect(screen.getByRole('link', { name: 'Peonies, Instagram' })).toHaveAttribute('href', expect.stringMatching(/^\/inspirations\/9/))
    // The idea's own address is not followed from the canvas.
    expect(screen.getByRole('link', { name: 'Peonies, Instagram' })).not.toHaveAttribute('target')
  })

  it('shows a name with markup in it as text', async () => {
    placing([idea('<img src=x onerror=alert(1)> and <b>bold</b>', 0, 0, { note: '<script>1</script>' })])
    open('/inspirations/canvas')

    const shown = await card('<img src=x onerror=alert\\(1\\)> and <b>bold</b>')
    expect(within(shown).getByText('<img src=x onerror=alert(1)> and <b>bold</b>')).toBeInTheDocument()
    expect(surface().querySelector('b, script, img[src="x"]')).toBeNull()
  })

  it('asks for a card’s picture as the grid does: with the token, as a thumbnail, shown from the browser’s own address', async () => {
    const itsPicture = picture()
    const one = idea('With a picture', 0, 0, { pictures: [itsPicture] })
    const server = placing([one])
    open('/inspirations/canvas')

    await waitFor(() => expect(surface().querySelector('img')).toHaveAttribute('src', 'blob:picture-1'))
    const asked = server.requests(`GET /api/inspirations/${one.id}/pictures/${itsPicture.id}`)
    expect(asked).toHaveLength(1)
    expect(asked[0]?.headers.get('Authorization')).toBe('Bearer access-1')
    expect(new URL(asked[0]?.url ?? 'http://x').searchParams.get('size')).toBe('THUMBNAIL')
    // No address of the API is ever put where an image is drawn.
    expect(document.querySelector('img[src^="/api"], img[src*="/api/"]')).toBeNull()
  })

  it('says in words what stands for a picture where there is none', async () => {
    placing([idea('Nothing to see', 0, 0), idea('A link', 220, 0, { url: 'https://www.florist.example/arch', source: 'WEB' })])
    open('/inspirations/canvas')
    expect(within(await card('Nothing to see')).getByText('No picture')).toBeInTheDocument()
    expect(within(await card('A link')).getAllByText('florist.example').length).toBeGreaterThan(0)
  })

  it('says so when there is nothing yet, and when nothing matches the filters', async () => {
    inspirationsServer([])
    open('/inspirations/canvas')
    expect(await screen.findByText('Nothing kept yet. Paste a link above, or drop a few pictures on this page.')).toBeInTheDocument()
    expect(surface()).toBeInTheDocument()
  })

  it('says that nothing matches when the filters leave nothing', async () => {
    inspirationsServer([])
    open('/inspirations/canvas?tag=lace')
    expect(await screen.findByText('Nothing here matches. Choose another board or tag.')).toBeInTheDocument()
  })

  it('hides a card that does not match the filters, and it lies where it did when it is shown again', async () => {
    const lace = idea('Lace sleeves', 700, 300, { tags: ['lace'] })
    const cake = idea('Cake', 40, 80, { tags: ['cake'] })
    const server = placing([lace, cake], {
      'GET /api/inspirations': (request) => {
        const tag = new URL(request.url).searchParams.get('tag')
        return Response.json([lace, cake].filter((one) => tag === null || one.tags.includes(tag)))
      },
    })
    open('/inspirations/canvas')
    expect(placeOf(await card('Lace sleeves'))).toEqual({ x: 700, y: 300 })

    await userEvent.selectOptions(screen.getByLabelText('Tag'), 'cake (1)')
    await waitFor(() => expect(screen.queryByRole('link', { name: /^Lace sleeves,/ })).not.toBeInTheDocument())
    expect(placeOf(await card('Cake'))).toEqual({ x: 40, y: 80 })

    await userEvent.selectOptions(screen.getByLabelText('Tag'), 'Any tag')
    expect(placeOf(await card('Lace sleeves'))).toEqual({ x: 700, y: 300 })
    expect(placeOf(await card('Cake'))).toEqual({ x: 40, y: 80 })
    expect(server.calls.filter((call) => call.includes('/place'))).toEqual([])
  })
})

describe('moving a card with the pointer', () => {
  /** Two cards 1704 units apart are shown at exactly half size; more than that is made of buttons. */
  async function atScale(scale: 0.5 | 1 | 2) {
    const mine = idea('Mine', 100, 100)
    const far = idea('Far', scale === 0.5 ? 1804 : 100, 100)
    const server = placing([mine, far])
    open('/inspirations/canvas')
    const target = await card('Mine')
    if (scale === 1) {
      await userEvent.click(screen.getByRole('button', { name: 'Natural size' }))
    } else if (scale === 2) {
      for (let press = 0; press < 5; press += 1) {
        await userEvent.click(screen.getByRole('button', { name: 'Larger' }))
      }
    }
    expect(viewOf().scale).toBe(scale)
    return { mine, server, target }
  }

  it.each([0.5, 1, 2] as const)('follows the pointer and saves the place the distance comes to at %s of the size', async (scale) => {
    const { mine, server, target } = await atScale(scale)
    const before = onScreen(target)

    fireEvent.pointerDown(target, { clientX: 300, clientY: 200, button: 0, pointerId: 1 })
    fireEvent.pointerMove(window, { clientX: 360, clientY: 150, pointerId: 1 })
    // At once, before it is let go: the card is where the pointer took it, and nothing is saved yet.
    expect(onScreen(target)).toEqual({ x: before.x + 60, y: before.y - 50 })
    expect(server.requests(`PUT /api/inspirations/${mine.id}/place`)).toHaveLength(0)
    fireEvent.pointerUp(window, { clientX: 360, clientY: 150, pointerId: 1 })

    await waitFor(() => expect(server.requests(`PUT /api/inspirations/${mine.id}/place`)).toHaveLength(1))
    await expect(sentTo(server, mine)).resolves.toEqual([{ x: Math.round(100 + 60 / scale), y: Math.round(100 - 50 / scale) }])
    // It stays where it was put.
    expect(placeOf(target)).toEqual({ x: Math.round(100 + 60 / scale), y: Math.round(100 - 50 / scale) })
    expect(await screen.findByText('Moved “Mine”.')).toBeInTheDocument()
  })

  it('sends one request for one move, however far the pointer went on the way', async () => {
    const one = idea('Mine', 0, 0)
    const server = placing([one])
    open('/inspirations/canvas')
    const target = await card('Mine')

    fireEvent.pointerDown(target, { clientX: 300, clientY: 200, button: 0, pointerId: 1 })
    for (let step = 1; step <= 12; step += 1) {
      fireEvent.pointerMove(window, { clientX: 300 + step * 10, clientY: 200, pointerId: 1 })
    }
    fireEvent.pointerUp(window, { clientX: 420, clientY: 200, pointerId: 1 })

    await waitFor(() => expect(server.requests(`PUT /api/inspirations/${one.id}/place`)).toHaveLength(1))
    expect(server.calls.filter((call) => call.endsWith('/place'))).toHaveLength(1)
    await expect(sentTo(server, one)).resolves.toEqual([{ x: 120, y: 0 }])
  })

  it('keeps the card where it was put while the request is on its way', async () => {
    const one = idea('Mine', 0, 0)
    let answer: (response: Response) => void = () => undefined
    placing([one], {
      [`PUT /api/inspirations/${one.id}/place`]: () => new Promise<Response>((resolve) => (answer = resolve)),
    })
    open('/inspirations/canvas')
    const target = await card('Mine')

    drag(target, 80, 0)
    await waitFor(() => expect(placeOf(target)).toEqual({ x: 80, y: 0 }))
    expect(placeOf(target)).toEqual({ x: 80, y: 0 })
    await act(() => {
      answer(Response.json({ ...one, x: 80, y: 0, placedAt: '2026-11-24T12:00:00Z' }))
      return Promise.resolve()
    })
    expect(placeOf(await card('Mine'))).toEqual({ x: 80, y: 0 })
  })

  it('puts the card back where it was and says so when the place could not be saved', async () => {
    const one = idea('Mine', 10, 20)
    placing([one], { [`PUT /api/inspirations/${one.id}/place`]: problem(500, 'unexpected') })
    open('/inspirations/canvas')
    const target = await card('Mine')

    drag(target, 90, 40)

    const said = await screen.findByRole('alert')
    expect(said).toHaveTextContent('“Mine” could not be moved. It is back where it was. Try again.')
    expect(placeOf(await card('Mine'))).toEqual({ x: 10, y: 20 })
    expect(screen.queryByText('Moved “Mine”.')).not.toBeInTheDocument()
  })

  it('holds a place within the limits the server takes', async () => {
    const one = idea('Mine', 99_990, -99_990)
    const server = placing([one])
    open('/inspirations/canvas')
    const target = await card('Mine')
    await userEvent.click(screen.getByRole('button', { name: 'Natural size' }))

    drag(target, 500, -500)

    await waitFor(() => expect(server.requests(`PUT /api/inspirations/${one.id}/place`)).toHaveLength(1))
    await expect(sentTo(server, one)).resolves.toEqual([{ x: 100_000, y: -100_000 }])
  })

  it('draws the card that was moved on top of the others at once, and after the server answered', async () => {
    const [a, b] = [idea('A', 0, 0, { placedAt: '2026-11-22T10:00:00Z' }), idea('B', 50, 0, { placedAt: '2026-11-23T10:00:00Z' })]
    placing([a, b])
    open('/inspirations/canvas')
    const first = await card('A')
    const second = await card('B')
    expect(Number(first.style.zIndex)).toBeLessThan(Number(second.style.zIndex))

    fireEvent.pointerDown(first, { clientX: 300, clientY: 200, button: 0, pointerId: 1 })
    fireEvent.pointerMove(window, { clientX: 330, clientY: 200, pointerId: 1 })
    expect(Number(first.style.zIndex)).toBeGreaterThan(Number(second.style.zIndex))
    fireEvent.pointerUp(window, { clientX: 330, clientY: 200, pointerId: 1 })

    await screen.findByText('Moved “A”.')
    expect(Number((await card('A')).style.zIndex)).toBeGreaterThan(Number((await card('B')).style.zIndex))
  })

  it('does not move the surface while a card is dragged', async () => {
    placing([idea('Mine', 0, 0)])
    open('/inspirations/canvas')
    const target = await card('Mine')
    const before = viewOf()
    drag(target, 70, 70)
    expect(viewOf()).toEqual(before)
  })
})

describe('pressing a card', () => {
  it('opens the idea when it did not move, sends no place, and returns to the canvas with its filters', async () => {
    const one = idea('Lace sleeves', 0, 0, { tags: ['lace'] })
    const server = placing([one], { [`GET /api/inspirations/${one.id}`]: Response.json(one) })
    open('/inspirations/canvas?tag=lace')
    const target = await card('Lace sleeves')

    // A few pixels of slack are not a move.
    fireEvent.pointerDown(target, { clientX: 300, clientY: 200, button: 0, pointerId: 1 })
    fireEvent.pointerMove(window, { clientX: 302, clientY: 201, pointerId: 1 })
    fireEvent.pointerUp(window, { clientX: 302, clientY: 201, pointerId: 1 })
    await userEvent.click(target)

    expect(await screen.findByLabelText(/^Title/)).toBeInTheDocument()
    expect(window.location.pathname).toBe(`/inspirations/${one.id}`)
    expect(server.calls.filter((call) => call.endsWith('/place'))).toEqual([])

    await userEvent.click(screen.getByRole('link', { name: 'Cancel' }))
    expect(await screen.findByRole('group', { name: 'Canvas of inspirations' })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/inspirations/canvas')
    expect(window.location.search).toBe('?tag=lace')
  })

  it('opens the idea with Enter', async () => {
    const one = idea('Lace sleeves', 0, 0)
    placing([one], { [`GET /api/inspirations/${one.id}`]: Response.json(one) })
    open('/inspirations/canvas')
    ;(await card('Lace sleeves')).focus()
    await userEvent.keyboard('{Enter}')
    expect(await screen.findByLabelText(/^Title/)).toBeInTheDocument()
  })

  it('does not open the idea when the press moved it', async () => {
    const one = idea('Lace sleeves', 0, 0)
    placing([one], { [`GET /api/inspirations/${one.id}`]: Response.json(one) })
    open('/inspirations/canvas')
    const target = await card('Lace sleeves')

    drag(target, 60, 0)
    // The click that a browser sends after a drag.
    fireEvent.click(target)

    await screen.findByText('Moved “Lace sleeves”.')
    expect(window.location.pathname).toBe('/inspirations/canvas')
  })
})

describe('moving a card with the keys', () => {
  async function focused(one: Inspiration) {
    const target = await card(one.title)
    act(() => target.focus())
    await waitFor(() => expect(target).toHaveFocus())
    return target
  }

  it('moves 20 units a press and 100 with Shift, at once, and saves once the keys rest', async () => {
    const one = idea('Mine', 0, 0)
    const server = placing([one])
    open('/inspirations/canvas')
    const target = await focused(one)
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })

    fireEvent.keyDown(target, { key: 'ArrowRight' })
    fireEvent.keyDown(target, { key: 'ArrowRight' })
    fireEvent.keyDown(target, { key: 'ArrowDown' })
    fireEvent.keyDown(target, { key: 'ArrowRight', shiftKey: true })
    fireEvent.keyDown(target, { key: 'ArrowUp', shiftKey: true })
    fireEvent.keyDown(target, { key: 'ArrowLeft' })
    expect(placeOf(target)).toEqual({ x: 120, y: -80 })

    // Nothing is saved while the keys are still being pressed.
    act(() => void vi.advanceTimersByTime(KEYS_REST_MS - 1))
    expect(server.requests(`PUT /api/inspirations/${one.id}/place`)).toHaveLength(0)
    fireEvent.keyDown(target, { key: 'ArrowDown' })
    act(() => void vi.advanceTimersByTime(KEYS_REST_MS - 1))
    expect(server.requests(`PUT /api/inspirations/${one.id}/place`)).toHaveLength(0)
    act(() => void vi.advanceTimersByTime(1))
    vi.useRealTimers()

    await waitFor(() => expect(server.requests(`PUT /api/inspirations/${one.id}/place`)).toHaveLength(1))
    await expect(sentTo(server, one)).resolves.toEqual([{ x: 120, y: -60 }])
    expect(await screen.findByText('Moved “Mine”.')).toBeInTheDocument()
    expect(placeOf(await card('Mine'))).toEqual({ x: 120, y: -60 })
  })

  it('keeps the keyboard on the card that is moved', async () => {
    const [mine, other] = [idea('Mine', 0, 0), idea('Other', 220, 0)]
    placing([mine, other])
    open('/inspirations/canvas')
    const target = await focused(mine)
    fireEvent.keyDown(target, { key: 'ArrowDown', shiftKey: true })
    fireEvent.keyDown(target, { key: 'ArrowDown', shiftKey: true })
    fireEvent.keyDown(target, { key: 'ArrowDown', shiftKey: true })
    // It now lies a row below the other one; it still has the keyboard.
    await waitFor(() => expect(target).toHaveFocus())
    expect(placeOf(target)).toEqual({ x: 0, y: 300 })
  })

  it('does not take a card beyond the limits', async () => {
    const one = idea('Mine', 99_990, 0)
    placing([one])
    open('/inspirations/canvas')
    const target = await focused(one)
    fireEvent.keyDown(target, { key: 'ArrowRight', shiftKey: true })
    expect(placeOf(target).x).toBe(100_000)
  })

  it('goes from card to card with Tab, by rows from the top', async () => {
    placing([idea('Low left', 0, 300), idea('Top right', 400, 10), idea('Top left', 0, 0), idea('Low right', 220, 280)])
    open('/inspirations/canvas')
    await card('Top left')

    surface().focus()
    await userEvent.tab()
    expect(screen.getByRole('link', { name: /^Top left,/ })).toHaveFocus()
    await userEvent.tab()
    expect(screen.getByRole('link', { name: /^Top right,/ })).toHaveFocus()
    await userEvent.tab()
    expect(screen.getByRole('link', { name: /^Low left,/ })).toHaveFocus()
    await userEvent.tab()
    expect(screen.getByRole('link', { name: /^Low right,/ })).toHaveFocus()
  })

  it('is not moved by the arrow keys of the surface when a card has them', async () => {
    const one = idea('Mine', 0, 0)
    placing([one])
    open('/inspirations/canvas')
    const target = await focused(one)
    const before = viewOf()
    fireEvent.keyDown(target, { key: 'ArrowLeft' })
    expect(viewOf()).toEqual(before)
  })
})

describe('moving and scaling the surface', () => {
  it('is moved by dragging where no card is, and sends no place', async () => {
    const one = idea('Mine', 0, 0)
    const server = placing([one])
    open('/inspirations/canvas')
    const target = await card('Mine')
    const before = viewOf()
    const cardBefore = onScreen(target)

    drag(surface(), 120, -30)

    expect(viewOf()).toEqual({ ...before, offsetX: before.offsetX + 120, offsetY: before.offsetY - 30 })
    expect(onScreen(target)).toEqual({ x: cardBefore.x + 120, y: cardBefore.y - 30 })
    expect(server.calls.filter((call) => call.endsWith('/place'))).toEqual([])
  })

  it('is moved by the wheel, without letting the page behind scroll', async () => {
    placing([idea('Mine', 0, 0)])
    open('/inspirations/canvas')
    await card('Mine')
    const before = viewOf()

    const left = fireEvent.wheel(surface(), { deltaX: 10, deltaY: 40 })

    expect(left).toBe(false)
    expect(viewOf()).toEqual({ ...before, offsetX: before.offsetX - 10, offsetY: before.offsetY - 40 })
  })

  it('is made larger and smaller by the wheel with Ctrl or ⌘ held, around the pointer', async () => {
    placing([idea('Mine', 0, 0)])
    open('/inspirations/canvas')
    await card('Mine')
    const around = { clientX: 640, clientY: 120 }
    const underPointer = () => {
      const view = viewOf()
      return { x: (around.clientX - view.offsetX) / view.scale, y: (around.clientY - view.offsetY) / view.scale }
    }
    const before = underPointer()

    fireEvent.wheel(surface(), { ...around, deltaY: -100, ctrlKey: true })
    expect(viewOf().scale).toBeGreaterThan(1)
    expect(underPointer().x).toBeCloseTo(before.x, 1)
    expect(underPointer().y).toBeCloseTo(before.y, 1)

    fireEvent.wheel(surface(), { ...around, deltaY: 200, metaKey: true })
    expect(viewOf().scale).toBeLessThan(1.3)
    expect(underPointer().x).toBeCloseTo(before.x, 1)

    // Never beyond a quarter or twice.
    fireEvent.wheel(surface(), { ...around, deltaY: -100000, ctrlKey: true })
    expect(viewOf().scale).toBe(2)
    fireEvent.wheel(surface(), { ...around, deltaY: 100000, ctrlKey: true })
    expect(viewOf().scale).toBe(0.25)
    expect(underPointer().x).toBeCloseTo(before.x, 1)
  })

  it('is made larger, smaller, natural and shows everything with buttons that have names', async () => {
    placing([idea('Left', -1500, 0), idea('Right', 1500, 0)])
    open('/inspirations/canvas')
    await card('Left')
    const everything = viewOf()
    expect(everything.scale).toBeLessThan(0.5)
    expect(screen.getByText(`Shown at ${Math.round(everything.scale * 100)}%`)).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Natural size' }))
    expect(viewOf().scale).toBe(1)
    // Around the middle of the surface.
    const middle = { x: (WIDTH / 2 - viewOf().offsetX) / viewOf().scale, y: (HEIGHT / 2 - viewOf().offsetY) / viewOf().scale }
    const wasMiddle = { x: (WIDTH / 2 - everything.offsetX) / everything.scale, y: (HEIGHT / 2 - everything.offsetY) / everything.scale }
    expect(middle.x).toBeCloseTo(wasMiddle.x, 6)
    expect(middle.y).toBeCloseTo(wasMiddle.y, 6)
    expect(screen.getByText('Shown at 100%')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Larger' }))
    expect(viewOf().scale).toBe(1.25)
    await userEvent.click(screen.getByRole('button', { name: 'Smaller' }))
    await userEvent.click(screen.getByRole('button', { name: 'Smaller' }))
    expect(viewOf().scale).toBe(0.8)
    for (let press = 0; press < 12; press += 1) {
      await userEvent.click(screen.getByRole('button', { name: 'Smaller' }))
    }
    expect(viewOf().scale).toBe(0.25)
    for (let press = 0; press < 12; press += 1) {
      await userEvent.click(screen.getByRole('button', { name: 'Larger' }))
    }
    expect(viewOf().scale).toBe(2)

    await userEvent.click(screen.getByRole('button', { name: 'Show everything' }))
    expect(viewOf()).toEqual(everything)
  })

  it('is moved by the arrow keys when the surface itself has the keyboard', async () => {
    placing([idea('Mine', 0, 0)])
    open('/inspirations/canvas')
    await card('Mine')
    const before = viewOf()
    act(() => surface().focus())

    fireEvent.keyDown(surface(), { key: 'ArrowRight' })
    expect(viewOf().offsetX).toBe(before.offsetX - 60)
    fireEvent.keyDown(surface(), { key: 'ArrowDown', shiftKey: true })
    expect(viewOf().offsetY).toBe(before.offsetY - 240)
    fireEvent.keyDown(surface(), { key: 'ArrowLeft' })
    fireEvent.keyDown(surface(), { key: 'ArrowUp', shiftKey: true })
    expect(viewOf()).toEqual(before)
  })

  it('is scaled by two fingers, around where they are, and moves no card', async () => {
    const one = idea('Mine', 0, 0)
    const server = placing([one])
    open('/inspirations/canvas')
    const target = await card('Mine')
    const before = viewOf()
    const under = { x: (400 - before.offsetX) / before.scale, y: (200 - before.offsetY) / before.scale }

    fireEvent.pointerDown(surface(), { clientX: 300, clientY: 200, button: 0, pointerId: 1, pointerType: 'touch' })
    fireEvent.pointerDown(surface(), { clientX: 500, clientY: 200, button: 0, pointerId: 2, pointerType: 'touch' })
    fireEvent.pointerMove(window, { clientX: 200, clientY: 200, pointerId: 1, pointerType: 'touch' })
    fireEvent.pointerMove(window, { clientX: 600, clientY: 200, pointerId: 2, pointerType: 'touch' })

    // Twice as far apart: twice the size, and what lay between the fingers still does.
    expect(viewOf().scale).toBe(2)
    const view = viewOf()
    expect(under.x * view.scale + view.offsetX).toBeCloseTo(400, 1)
    expect(under.y * view.scale + view.offsetY).toBeCloseTo(200, 1)
    fireEvent.pointerUp(window, { clientX: 200, clientY: 200, pointerId: 1, pointerType: 'touch' })
    fireEvent.pointerUp(window, { clientX: 600, clientY: 200, pointerId: 2, pointerType: 'touch' })
    expect(placeOf(target)).toEqual({ x: 0, y: 0 })
    expect(server.calls.filter((call) => call.endsWith('/place'))).toEqual([])
  })

  it('sends nothing for a card that a second finger took away from the first', async () => {
    const one = idea('Mine', 0, 0)
    const server = placing([one])
    open('/inspirations/canvas')
    const target = await card('Mine')

    fireEvent.pointerDown(target, { clientX: 300, clientY: 200, button: 0, pointerId: 1, pointerType: 'touch' })
    fireEvent.pointerMove(window, { clientX: 340, clientY: 200, pointerId: 1, pointerType: 'touch' })
    expect(placeOf(target).x).toBe(40)
    fireEvent.pointerDown(surface(), { clientX: 600, clientY: 400, button: 0, pointerId: 2, pointerType: 'touch' })
    fireEvent.pointerUp(window, { clientX: 340, clientY: 200, pointerId: 1, pointerType: 'touch' })
    fireEvent.pointerUp(window, { clientX: 600, clientY: 400, pointerId: 2, pointerType: 'touch' })

    expect(placeOf(target)).toEqual({ x: 0, y: 0 })
    expect(server.calls.filter((call) => call.endsWith('/place'))).toEqual([])
  })
})
