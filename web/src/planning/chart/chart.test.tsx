import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../../App.tsx'
import { entry, planningServer, problem } from '../../test-server.ts'
import type { Entry } from '../planningApi.ts'
import { ROW_HEIGHT } from './layout.ts'

// A Tuesday. The test wedding is on 19 June 2027, so by week the chart starts on Monday 9 November 2026.
const TODAY = '2026-11-17T10:00:00Z'
const DAY = 24

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date(TODAY))
})

afterEach(() => {
  vi.useRealTimers()
})

function open(path: string) {
  window.history.pushState(null, '', path)
  render(<App />)
}

function planItem(title: string, startDate: string | null, dueDate: string | null, more: Partial<Entry> = {}): Entry {
  return entry({ kind: 'PLAN_ITEM', title, startDate, dueDate, ...more })
}

/** The bar of a plan item; its name starts with the title. */
async function bar(title: string): Promise<HTMLElement> {
  return screen.findByRole('button', { name: new RegExp(`^${title},`) })
}

function part(of: HTMLElement, selector: string): Element {
  const found = of.querySelector(selector)
  if (found === null) {
    throw new Error(`No ${selector} in this bar`)
  }
  return found
}

/** Takes hold of something, moves the pointer by a number of days and rows, and lets go. */
function drag(target: Element, days: number, toRow: number | null = null, pointerType = 'mouse') {
  const from = { clientX: 400, clientY: 20 }
  const to = { clientX: 400 + days * DAY, clientY: toRow === null ? 20 : toRow * ROW_HEIGHT + ROW_HEIGHT / 2 }
  fireEvent.pointerDown(target, { ...from, button: 0, pointerId: 1, pointerType })
  fireEvent.pointerMove(window, { ...to, pointerId: 1, pointerType })
  fireEvent.pointerUp(window, { ...to, pointerId: 1, pointerType })
}

/** Answers a change of an entry the way the server does: what was sent, one version on. */
function saving(original: Entry) {
  return async (request: Request) => {
    const sent = (await request.json()) as { startDate: string | null; dueDate: string; version: number }
    return Response.json({ ...original, startDate: sent.startDate, dueDate: sent.dueDate, version: sent.version + 1 })
  }
}

describe('the chart of the plan', () => {
  it('draws a bar for each plan item with dates, earliest first, and lists the others below', async () => {
    const venue = planItem('Book the venue', '2026-12-01', '2026-12-10')
    const invitations = planItem('Send invitations', '2026-11-20', '2026-11-30', { status: 'IN_PROGRESS' })
    const rings = planItem('Collect the rings', null, '2026-12-04')
    planningServer([venue, invitations, rings, planItem('Choose a photographer', null, null)])
    open('/plan/chart')

    // A bar's place comes from its dates: 24 pixels a day, counted from Monday 9 November.
    const first = part(await bar('Send invitations'), '.gantt-bar-body')
    expect(first).toHaveAttribute('x', String(11 * DAY))
    expect(first).toHaveAttribute('width', String(11 * DAY))
    expect(Number(first.getAttribute('y'))).toBeLessThan(ROW_HEIGHT)
    const second = part(await bar('Book the venue'), '.gantt-bar-body')
    expect(second).toHaveAttribute('x', String(22 * DAY))
    expect(second).toHaveAttribute('width', String(10 * DAY))
    expect(Number(second.getAttribute('y'))).toBeGreaterThan(ROW_HEIGHT)
    // Only an end date: the bar is that one day.
    expect(part(await bar('Collect the rings'), '.gantt-bar-body')).toHaveAttribute('width', String(DAY))

    expect(screen.getByRole('button', { name: 'Send invitations, 20 Nov 2026 – 30 Nov 2026, In progress' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Collect the rings, by 4 Dec 2026' })).toBeInTheDocument()
    const rows = within(screen.getByRole('group', { name: 'The plan on a timeline' })).getAllByRole('listitem')
    expect(rows.map((row) => row.textContent)).toEqual([
      'Send invitations20 Nov 2026 – 30 Nov 2026 · In progress',
      'Book the venue1 Dec 2026 – 10 Dec 2026',
      'Collect the ringsby 4 Dec 2026',
    ])

    const notScheduled = within(screen.getByRole('region', { name: 'Not scheduled' }))
    expect(notScheduled.getByRole('link', { name: 'Choose a photographer' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Choose a photographer/ })).not.toBeInTheDocument()
  })

  it('marks today and the wedding day, and the tasks of a plan item on its bar', async () => {
    const venue = planItem('Book the venue', '2026-12-01', '2026-12-10')
    const deposit = entry({ title: 'Pay the deposit', dueDate: '2026-12-05', parentId: venue.id })
    const contract = entry({ title: 'Sign the contract', dueDate: '2026-12-02', parentId: venue.id, status: 'DONE' })
    const server = planningServer([venue, deposit, contract, entry({ title: 'Not of this plan item', dueDate: '2026-12-03' })])
    open('/plan/chart')

    const venueBar = await bar('Book the venue')
    expect(venueBar).toHaveAccessibleName('Book the venue, 1 Dec 2026 – 10 Dec 2026, 2 tasks')
    // A mark is a diamond in the middle of its day; pointing at it names the task.
    const mark = within(venueBar).getByText('Pay the deposit, due 5 Dec 2026').parentElement
    expect(mark).toHaveAttribute('d', expect.stringMatching(new RegExp(`^M ${26 * DAY + DAY / 2} `)))
    expect(mark).not.toHaveAttribute('data-done')
    expect(within(venueBar).getByText('Sign the contract, due 2 Dec 2026, done').parentElement).toHaveAttribute('data-done')
    expect(screen.queryByText(/Not of this plan item/)).not.toBeInTheDocument()

    // Each marked day has a line and a name; the lines stand in the middle of their day.
    expect(screen.getByText('Today')).toHaveAttribute('x', String(8 * DAY + DAY / 2))
    expect(document.querySelector('.gantt-today')).toHaveAttribute('x1', String(8 * DAY + DAY / 2))
    expect(screen.getByText('Wedding day')).toHaveAttribute('x', String(222 * DAY + DAY / 2))
    expect(document.querySelector('.gantt-wedding')).toHaveAttribute('x1', String(222 * DAY + DAY / 2))

    // Only tasks with a date are asked for: the others have no place on a timeline.
    const asked = server.requests('GET /api/entries').map((request) => new URL(request.url).searchParams)
    expect(asked.find((params) => params.get('kind') === 'TASK')?.get('scheduled')).toBe('true')
  })

  it('reads by week or by month, and remembers which in the address', async () => {
    planningServer([planItem('Book the venue', '2026-12-01', '2026-12-10')])
    open('/plan/chart')

    expect(await screen.findByText('November 2026')).toBeInTheDocument()
    await userEvent.selectOptions(screen.getByLabelText('Scale'), 'Months')

    // Six pixels a day, counted from 1 November.
    await waitFor(() => expect(window.location.search).toBe('?scale=month'))
    expect(part(await bar('Book the venue'), '.gantt-bar-body')).toHaveAttribute('x', String(30 * 6))
    expect(part(await bar('Book the venue'), '.gantt-bar-body')).toHaveAttribute('width', String(10 * 6))
    expect(screen.getByText('2027')).toBeInTheDocument()
    expect(screen.getByText('Jun')).toBeInTheDocument()
    expect(screen.queryByText('November 2026')).not.toBeInTheDocument()
  })

  it('opens by month on a narrow screen, where a week would fill it', async () => {
    vi.stubGlobal('matchMedia', (query: string) => ({ matches: query === '(max-width: 40rem)' }))
    planningServer([planItem('Book the venue', '2026-12-01', '2026-12-10')])
    open('/plan/chart')

    expect(part(await bar('Book the venue'), '.gantt-bar-body')).toHaveAttribute('width', String(10 * 6))
    expect(screen.getByLabelText('Scale')).toHaveValue('month')

    // Asking for weeks there is remembered in the address, as asking for months is elsewhere.
    await userEvent.selectOptions(screen.getByLabelText('Scale'), 'Weeks')
    await waitFor(() => expect(window.location.search).toBe('?scale=week'))
    expect(part(await bar('Book the venue'), '.gantt-bar-body')).toHaveAttribute('width', String(10 * DAY))
  })

  it('says so when no plan item has dates, and when there is nothing at all', async () => {
    const server = planningServer([planItem('Choose a photographer', null, null)])
    open('/plan/chart')

    expect(await screen.findByText(/No plan item here has dates yet/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Choose a photographer' })).toBeInTheDocument()

    server.answer('GET /api/entries', Response.json([]))
    await userEvent.selectOptions(screen.getByLabelText('Who'), 'Anna')
    expect(await screen.findByText('Nothing matches these filters.')).toBeInTheDocument()
  })

  it('keeps what the plan was narrowed down to when switching between list and chart', async () => {
    planningServer([planItem('Book the venue', '2026-12-01', '2026-12-10')])
    open('/plan?status=all&priority=HIGH')

    await userEvent.click(await screen.findByRole('link', { name: 'Chart' }))
    await bar('Book the venue')
    expect(window.location.pathname + window.location.search).toBe('/plan/chart?status=all&priority=HIGH')
    expect(screen.getByRole('link', { name: 'Chart' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('link', { name: 'Plan' })).toHaveAttribute('aria-current', 'page')
    expect(screen.queryByLabelText('Order')).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('link', { name: 'List' }))
    expect(await screen.findByLabelText('Order')).toBeInTheDocument()
    expect(window.location.pathname + window.location.search).toBe('/plan?status=all&priority=HIGH')
  })
})

describe('rescheduling on the chart', () => {
  it('moves a plan item by dragging its bar and saves both dates', async () => {
    const venue = planItem('Book the venue', '2026-12-01', '2026-12-10', { version: 4, notes: 'Ask about the garden.' })
    const server = planningServer([venue], { [`PUT /api/entries/${venue.id}`]: saving(venue) })
    open('/plan/chart')

    drag(part(await bar('Book the venue'), '.gantt-bar-body'), 3)

    await waitFor(() => expect(server.requests(`PUT /api/entries/${venue.id}`)).toHaveLength(1))
    // The whole entry goes back, with the two dates changed and the version the change is based on.
    await expect(server.requests(`PUT /api/entries/${venue.id}`)[0]?.json()).resolves.toEqual({
      title: 'Book the venue', notes: 'Ask about the garden.', categoryId: null, startDate: '2026-12-04', dueDate: '2026-12-13',
      status: 'NOT_STARTED', assigneeIds: [], priorityMode: 'AUTO', priority: null, parentId: null,
      amount: null, paymentKind: null, paidOn: null, version: 4,
    })
    expect(await screen.findByRole('button', { name: 'Book the venue, 4 Dec 2026 – 13 Dec 2026' })).toBeInTheDocument()
  })

  it('pulls the end of a bar, or its start, and leaves the other date alone', async () => {
    const venue = planItem('Book the venue', '2026-12-01', '2026-12-10')
    const server = planningServer([venue], { [`PUT /api/entries/${venue.id}`]: saving(venue) })
    open('/plan/chart')

    drag(part(await bar('Book the venue'), '[data-grip=end]'), 5)
    await waitFor(() => expect(server.requests(`PUT /api/entries/${venue.id}`)).toHaveLength(1))
    await expect(server.requests(`PUT /api/entries/${venue.id}`)[0]?.json()).resolves.toMatchObject({
      startDate: '2026-12-01', dueDate: '2026-12-15',
    })

    drag(part(await bar('Book the venue'), '[data-grip=start]'), -2)
    await waitFor(() => expect(server.requests(`PUT /api/entries/${venue.id}`)).toHaveLength(2))
    await expect(server.requests(`PUT /api/entries/${venue.id}`)[1]?.json()).resolves.toMatchObject({
      startDate: '2026-11-29', dueDate: '2026-12-15', version: 1,
    })

    // An end cannot be pulled past the other one: the plan item shrinks to a day.
    drag(part(await bar('Book the venue'), '[data-grip=end]'), -60)
    await waitFor(() => expect(server.requests(`PUT /api/entries/${venue.id}`)).toHaveLength(3))
    await expect(server.requests(`PUT /api/entries/${venue.id}`)[2]?.json()).resolves.toMatchObject({
      startDate: '2026-11-29', dueDate: '2026-11-29',
    })
  })

  it('shows the new dates while the bar is held, and saves nothing when it is put back where it was', async () => {
    const venue = planItem('Book the venue', '2026-12-01', '2026-12-10')
    const server = planningServer([venue])
    open('/plan/chart')

    const body = part(await bar('Book the venue'), '.gantt-bar-body')
    fireEvent.pointerDown(body, { clientX: 400, clientY: 20, button: 0, pointerId: 1 })
    fireEvent.pointerMove(window, { clientX: 400 + 2 * DAY + 5, clientY: 20, pointerId: 1 })
    expect(await screen.findByRole('button', { name: 'Book the venue, 3 Dec 2026 – 12 Dec 2026' })).toBeInTheDocument()
    fireEvent.pointerMove(window, { clientX: 404, clientY: 20, pointerId: 1 })
    fireEvent.pointerUp(window, { clientX: 404, clientY: 20, pointerId: 1 })

    expect(await screen.findByRole('button', { name: 'Book the venue, 1 Dec 2026 – 10 Dec 2026' })).toBeInTheDocument()
    expect(server.calls.filter((call) => call.startsWith('PUT'))).toEqual([])
  })

  it('moves and stretches a plan item with the keyboard, saving quick changes one after the other', async () => {
    const venue = planItem('Book the venue', '2026-12-01', '2026-12-10', { version: 7 })
    // The server takes its time over the first change, so that two more arrive while it is on its way.
    let answerFirst: () => void = () => undefined
    let calls = 0
    const server = planningServer([venue], {
      [`PUT /api/entries/${venue.id}`]: async (request) => {
        calls += 1
        if (calls === 1) {
          await new Promise<void>((resolve) => (answerFirst = resolve))
        }
        return saving(venue)(request)
      },
    })
    open('/plan/chart')

    ;(await bar('Book the venue')).focus()
    await userEvent.keyboard('{ArrowRight}')
    await waitFor(() => expect(server.requests(`PUT /api/entries/${venue.id}`)).toHaveLength(1))
    await userEvent.keyboard('{ArrowRight}{Shift>}{ArrowRight}{/Shift}')
    expect(screen.getByRole('button', { name: 'Book the venue, 3 Dec 2026 – 13 Dec 2026' })).toHaveFocus()
    expect(server.requests(`PUT /api/entries/${venue.id}`)).toHaveLength(1)

    answerFirst()
    await waitFor(() => expect(server.requests(`PUT /api/entries/${venue.id}`)).toHaveLength(2))
    const sent = await Promise.all(server.requests(`PUT /api/entries/${venue.id}`).map((request) => request.json()))
    expect(sent[0]).toMatchObject({ startDate: '2026-12-02', dueDate: '2026-12-11', version: 7 })
    // The two that waited go together, based on the version the first one returned.
    expect(sent[1]).toMatchObject({ startDate: '2026-12-03', dueDate: '2026-12-13', version: 8 })

    await userEvent.keyboard('{Alt>}{ArrowLeft}{/Alt}')
    await waitFor(() => expect(server.requests(`PUT /api/entries/${venue.id}`)).toHaveLength(3))
    await expect(server.requests(`PUT /api/entries/${venue.id}`)[2]?.json()).resolves.toMatchObject({
      startDate: '2026-12-02', dueDate: '2026-12-13', version: 9,
    })
  })

  it('goes from bar to bar with the up and down arrows, as one stop for the Tab key, and opens one with Enter', async () => {
    const venue = planItem('Book the venue', '2026-12-01', '2026-12-10')
    const invitations = planItem('Send invitations', '2026-12-05', '2026-12-20')
    planningServer([venue, invitations], { [`GET /api/entries/${invitations.id}`]: Response.json(invitations) })
    open('/plan/chart')

    const first = await bar('Book the venue')
    const second = await bar('Send invitations')
    expect(first).toHaveAttribute('tabindex', '0')
    expect(second).toHaveAttribute('tabindex', '-1')
    expect(screen.getByRole('link', { name: 'Book the venue' })).toHaveAttribute('tabindex', '-1')

    first.focus()
    await userEvent.keyboard('{ArrowDown}')
    expect(second).toHaveFocus()
    expect(second).toHaveAttribute('tabindex', '0')
    expect(first).toHaveAttribute('tabindex', '-1')

    await userEvent.keyboard('{Enter}')
    expect(await screen.findByRole('heading', { name: 'Change this plan item' })).toBeInTheDocument()
    expect(screen.getByLabelText('Title')).toHaveValue('Send invitations')

    // The form goes back to the chart it was opened from.
    await userEvent.click(screen.getByRole('link', { name: 'Cancel' }))
    await bar('Send invitations')
    expect(window.location.pathname).toBe('/plan/chart')
  })

  it('puts the bar back and says why when someone else changed the plan item in the meantime', async () => {
    const venue = planItem('Book the venue', '2026-12-01', '2026-12-10')
    const server = planningServer([venue], { [`PUT /api/entries/${venue.id}`]: () => problem(409, 'changed-meanwhile') })
    open('/plan/chart')

    drag(part(await bar('Book the venue'), '.gantt-bar-body'), 3)

    expect(await screen.findByRole('alert')).toHaveTextContent(/Someone else changed that plan item in the meantime/)
    expect(screen.getByRole('button', { name: 'Book the venue, 1 Dec 2026 – 10 Dec 2026' })).toBeInTheDocument()
    // The chart reads the plan again, to show what the other person saved.
    await waitFor(() => expect(server.calls.filter((call) => call === 'GET /api/entries').length).toBeGreaterThan(2))
  })

  it('lets a finger choose a bar first and drag it only then, so that swiping still scrolls', async () => {
    const venue = planItem('Book the venue', '2026-12-01', '2026-12-10')
    const invitations = planItem('Send invitations', '2026-12-05', '2026-12-20')
    const server = planningServer([venue, invitations], { [`PUT /api/entries/${invitations.id}`]: saving(invitations) })
    open('/plan/chart')

    const second = await bar('Send invitations')
    drag(part(second, '.gantt-bar-body'), 3, null, 'touch')
    expect(second).toHaveFocus()
    expect(server.calls.filter((call) => call.startsWith('PUT'))).toEqual([])

    drag(part(second, '.gantt-bar-body'), 3, null, 'touch')
    await waitFor(() => expect(server.requests(`PUT /api/entries/${invitations.id}`)).toHaveLength(1))
  })
})

describe('what waits for what', () => {
  it('draws a line between two bars, and marks the one that starts too early', async () => {
    const venue = planItem('Book the venue', '2026-12-01', '2026-12-10')
    const menu = planItem('Choose the menu', '2026-12-12', '2026-12-20')
    const invitations = planItem('Send invitations', '2026-12-05', '2026-12-20')
    menu.dependsOnIds = [venue.id]
    invitations.dependsOnIds = [venue.id]
    invitations.conflictsWithIds = [venue.id]
    planningServer([venue, menu, invitations])
    open('/plan/chart')

    expect(await screen.findByRole('button', { name: '“Choose the menu” waits for “Book the venue”' })).not.toHaveAttribute('data-conflict')
    const tooEarly = screen.getByRole('button', { name: '“Send invitations” waits for “Book the venue”, which is not finished when it starts' })
    expect(tooEarly).toHaveAttribute('data-conflict')

    // Said in words on the bar and next to the title, not by the dashed outline alone.
    const marked = await bar('Send invitations')
    expect(marked).toHaveAccessibleName('Send invitations, 5 Dec 2026 – 20 Dec 2026, Starts before “Book the venue” is finished')
    expect(marked).toHaveAttribute('data-conflict')
    expect(within(marked).getByText('!')).toBeInTheDocument()
    const rows = within(screen.getByRole('group', { name: 'The plan on a timeline' })).getAllByRole('listitem')
    expect(rows.map((row) => row.textContent)).toContain('Send invitationsStarts before “Book the venue” is finished')
  })

  it('makes a plan item wait for another by dragging from the end of that one onto it', async () => {
    const venue = planItem('Book the venue', '2026-12-01', '2026-12-10')
    const invitations = planItem('Send invitations', '2026-12-15', '2026-12-20')
    const server = planningServer([venue, invitations], {
      [`PUT /api/entries/${invitations.id}/dependencies/${venue.id}`]: Response.json({ ...invitations, dependsOnIds: [venue.id] }),
    })
    open('/plan/chart')

    // Row 0 is the venue, row 1 the invitations.
    drag(part(await bar('Book the venue'), '[data-grip=link]'), 6, 1)

    await waitFor(() => expect(server.calls).toContain(`PUT /api/entries/${invitations.id}/dependencies/${venue.id}`))
    // The chart reads the plan again to draw the line.
    await waitFor(() => expect(server.calls.filter((call) => call === 'GET /api/entries').length).toBeGreaterThan(2))
  })

  it('links nothing when the pointer is let go over the same bar or over no row', async () => {
    const venue = planItem('Book the venue', '2026-12-01', '2026-12-10')
    const server = planningServer([venue, planItem('Send invitations', '2026-12-15', '2026-12-20')])
    open('/plan/chart')

    drag(part(await bar('Book the venue'), '[data-grip=link]'), 6, 0)
    drag(part(await bar('Book the venue'), '[data-grip=link]'), 6, 5)

    expect(server.calls.filter((call) => call.startsWith('PUT'))).toEqual([])
  })

  it('links two plan items with the keyboard alone', async () => {
    const venue = planItem('Book the venue', '2026-12-01', '2026-12-10')
    const invitations = planItem('Send invitations', '2026-12-15', '2026-12-20')
    const server = planningServer([venue, invitations], {
      [`PUT /api/entries/${invitations.id}/dependencies/${venue.id}`]: Response.json({ ...invitations, dependsOnIds: [venue.id] }),
    })
    open('/plan/chart')

    ;(await bar('Book the venue')).focus()
    await userEvent.keyboard('l')
    expect(screen.getByRole('status')).toHaveTextContent('Choose the plan item that waits for “Book the venue”')
    await userEvent.keyboard('{ArrowDown}{Enter}')

    await waitFor(() => expect(server.calls).toContain(`PUT /api/entries/${invitations.id}/dependencies/${venue.id}`))
    expect(screen.getByRole('status')).toBeEmptyDOMElement()
    expect(screen.queryByRole('heading', { name: 'Change this plan item' })).not.toBeInTheDocument()

    // Escape gives the idea up.
    await userEvent.keyboard('l')
    await userEvent.keyboard('{Escape}')
    expect(screen.getByRole('status')).toBeEmptyDOMElement()
  })

  it('says so when a link would make two plan items wait for each other', async () => {
    const venue = planItem('Book the venue', '2026-12-01', '2026-12-10')
    const invitations = planItem('Send invitations', '2026-12-15', '2026-12-20')
    venue.dependsOnIds = [invitations.id]
    planningServer([venue, invitations], {
      [`PUT /api/entries/${invitations.id}/dependencies/${venue.id}`]: () => problem(409, 'dependency-cycle'),
    })
    open('/plan/chart')

    drag(part(await bar('Book the venue'), '[data-grip=link]'), 6, 1)

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '“Book the venue” already waits for “Send invitations”, directly or through others, so the two cannot wait for each other.',
    )
  })

  it('removes a link after it was chosen', async () => {
    const venue = planItem('Book the venue', '2026-12-01', '2026-12-10')
    const invitations = planItem('Send invitations', '2026-12-15', '2026-12-20')
    invitations.dependsOnIds = [venue.id]
    const server = planningServer([venue, invitations], {
      [`DELETE /api/entries/${invitations.id}/dependencies/${venue.id}`]: Response.json({ ...invitations, dependsOnIds: [] }),
    })
    open('/plan/chart')

    await userEvent.click(await screen.findByRole('button', { name: '“Send invitations” waits for “Book the venue”' }))
    const chosen = within(screen.getByRole('group', { name: 'The chosen link' }))
    expect(chosen.getByText('“Send invitations” waits for “Book the venue”.')).toBeInTheDocument()

    // Changing one's mind costs nothing.
    await userEvent.click(chosen.getByRole('button', { name: 'Keep it' }))
    expect(screen.queryByRole('group', { name: 'The chosen link' })).not.toBeInTheDocument()
    expect(server.calls.filter((call) => call.startsWith('DELETE'))).toEqual([])

    await userEvent.click(screen.getByRole('button', { name: '“Send invitations” waits for “Book the venue”' }))
    await userEvent.click(screen.getByRole('button', { name: 'Remove this link' }))
    await waitFor(() => expect(server.calls).toContain(`DELETE /api/entries/${invitations.id}/dependencies/${venue.id}`))
    await waitFor(() => expect(screen.queryByRole('group', { name: 'The chosen link' })).not.toBeInTheDocument())
  })
})

describe('a long plan', () => {
  it('draws 200 plan items and still moves the last one', async () => {
    const items = Array.from({ length: 200 }, (_, index) => {
      const start = new Date(Date.UTC(2026, 10, 20 + index))
      const end = new Date(Date.UTC(2026, 10, 27 + index))
      return planItem(`Step ${String(index + 1).padStart(3, '0')}`, start.toISOString().slice(0, 10), end.toISOString().slice(0, 10))
    })
    items.forEach((item, index) => {
      if (index > 0) {
        item.dependsOnIds = [items[index - 1]?.id ?? '']
      }
    })
    const last = items[199] as Entry
    const server = planningServer(items, { [`PUT /api/entries/${last.id}`]: saving(last) })
    open('/plan/chart')

    await bar('Step 001')
    expect(document.querySelectorAll('.gantt-bar')).toHaveLength(200)
    expect(document.querySelectorAll('.gantt-link')).toHaveLength(199)

    drag(part(await bar('Step 200'), '.gantt-bar-body'), 1)
    await waitFor(() => expect(server.requests(`PUT /api/entries/${last.id}`)).toHaveLength(1))
  })
})

describe('what a plan item waits for, on its form', () => {
  it('lists it, adds to it and takes away from it without touching the rest of the form', async () => {
    const venue = planItem('Book the venue', '2026-12-01', '2026-12-10')
    const menu = planItem('Choose the menu', '2026-12-12', '2026-12-20')
    const invitations = planItem('Send invitations', '2026-12-05', '2026-12-20')
    invitations.dependsOnIds = [venue.id]
    invitations.conflictsWithIds = [venue.id]
    const server = planningServer([venue, menu, invitations], {
      [`GET /api/entries/${invitations.id}`]: Response.json(invitations),
      [`PUT /api/entries/${invitations.id}/dependencies/${menu.id}`]: Response.json({
        ...invitations, dependsOnIds: [venue.id, menu.id], conflictsWithIds: [venue.id, menu.id],
      }),
      [`DELETE /api/entries/${invitations.id}/dependencies/${venue.id}`]: Response.json({
        ...invitations, dependsOnIds: [menu.id], conflictsWithIds: [menu.id],
      }),
    })
    open(`/entries/${invitations.id}`)

    const waitsFor = within(await screen.findByRole('region', { name: 'Waits for' }))
    expect(waitsFor.getByText('Book the venue')).toHaveTextContent('Book the venue — not finished when this one starts')
    await userEvent.type(screen.getByLabelText(/^Notes/), 'Typed before the link was added.')

    // A plan item cannot wait for itself, nor twice for the same one.
    const choice = waitsFor.getByLabelText('Add a plan item to wait for')
    expect(within(choice).getAllByRole('option').map((option) => option.textContent)).toEqual(['Choose one', 'Choose the menu'])
    expect(waitsFor.getByRole('button', { name: 'Add' })).toBeDisabled()
    await userEvent.selectOptions(choice, 'Choose the menu')
    await userEvent.click(waitsFor.getByRole('button', { name: 'Add' }))

    await waitFor(() => expect(server.calls).toContain(`PUT /api/entries/${invitations.id}/dependencies/${menu.id}`))
    expect(await waitsFor.findByRole('button', { name: 'Stop waiting for “Choose the menu”' })).toBeInTheDocument()

    await userEvent.click(waitsFor.getByRole('button', { name: 'Stop waiting for “Book the venue”' }))
    await waitFor(() => expect(server.calls).toContain(`DELETE /api/entries/${invitations.id}/dependencies/${venue.id}`))
    await waitFor(() => expect(waitsFor.queryByRole('button', { name: 'Stop waiting for “Book the venue”' })).not.toBeInTheDocument())

    expect(screen.getByLabelText(/^Notes/)).toHaveValue('Typed before the link was added.')
  })

  it('says so when the chosen plan item already waits for this one', async () => {
    const venue = planItem('Book the venue', '2026-12-01', '2026-12-10')
    const invitations = planItem('Send invitations', '2026-12-15', '2026-12-20')
    planningServer([venue, invitations], {
      [`GET /api/entries/${venue.id}`]: Response.json(venue),
      [`PUT /api/entries/${venue.id}/dependencies/${invitations.id}`]: () => problem(409, 'dependency-cycle'),
    })
    open(`/entries/${venue.id}`)

    const waitsFor = within(await screen.findByRole('region', { name: 'Waits for' }))
    await userEvent.selectOptions(waitsFor.getByLabelText('Add a plan item to wait for'), 'Send invitations')
    await userEvent.click(waitsFor.getByRole('button', { name: 'Add' }))

    expect(await waitsFor.findByRole('alert')).toHaveTextContent(
      '“Send invitations” already waits for this one, directly or through others, so the two cannot wait for each other.',
    )
  })

  it('is not offered for a task', async () => {
    const task = entry({ title: 'Pay the deposit' })
    planningServer([task], { [`GET /api/entries/${task.id}`]: Response.json(task) })
    open(`/entries/${task.id}`)

    expect(await screen.findByRole('heading', { name: 'Change this task' })).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Waits for' })).not.toBeInTheDocument()
  })
})
