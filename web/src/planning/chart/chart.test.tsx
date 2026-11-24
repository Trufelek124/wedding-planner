import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../../App.tsx'
import { entry, planningServer, problem } from '../../test-server.ts'
import type { Entry } from '../planningApi.ts'
import { dayNumber } from './days.ts'
import { HEADING_HEIGHT, ROW_HEIGHT } from './layout.ts'

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
    // A task of no plan item is not on a bar; it has a row of its own (see the Gantt tests below).
    expect(within(venueBar).queryByText(/Not of this plan item/)).not.toBeInTheDocument()

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

    expect(await screen.findByText(/No plan item here has dates yet, and no task has a due date/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Choose a photographer' })).toBeInTheDocument()

    server.answer('GET /api/entries', Response.json([]))
    await userEvent.selectOptions(screen.getByLabelText('Who'), 'Anna')
    expect(await screen.findByText('Nothing matches these filters.')).toBeInTheDocument()
  })

  it('keeps what the plan was narrowed down to when switching between list and chart', async () => {
    planningServer([planItem('Book the venue', '2026-12-01', '2026-12-10')])
    open('/plan?status=all&priority=HIGH')

    await userEvent.click(await screen.findByRole('link', { name: 'Gantt' }))
    await bar('Book the venue')
    expect(window.location.pathname + window.location.search).toBe('/plan/chart?status=all&priority=HIGH')
    expect(screen.getByRole('link', { name: 'Gantt' })).toHaveAttribute('aria-current', 'page')
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
      vendorId: null, amount: null, paymentKind: null, paidOn: null, version: 4,
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

const DEPOSIT_AMOUNT = 250000

function task(title: string, dueDate: string | null, more: Partial<Entry> = {}): Entry {
  return entry({ kind: 'TASK', title, dueDate, ...more })
}

/** The marker of a task on its own row; its name starts with the title. */
async function marker(title: string): Promise<HTMLElement> {
  return screen.findByRole('button', { name: new RegExp(`^${title}(,|$)`) })
}

function tasksRows(): HTMLElement[] {
  const heading = screen.getByRole('heading', { name: 'Tasks' })
  return within(heading.nextElementSibling as HTMLElement).getAllByRole('listitem')
}

/** The query a request for entries was made with. */
function asked(request: Request): Record<string, string[]> {
  const found: Record<string, string[]> = {}
  for (const [name, value] of new URL(request.url).searchParams) {
    found[name] = [...(found[name] ?? []), value]
  }
  return found
}

function taskRequests(server: ReturnType<typeof planningServer>): Record<string, string[]>[] {
  return server.requests('GET /api/entries').map(asked).filter((query) => query.kind?.[0] === 'TASK')
}

describe('tasks on the Gantt chart', () => {
  it('calls the view Gantt', async () => {
    planningServer([task('Order the cake', '2026-12-20')])
    open('/plan')

    expect(await screen.findByRole('link', { name: 'Gantt' })).toHaveAttribute('href', '/plan/chart')
    expect(screen.queryByRole('link', { name: 'Chart' })).not.toBeInTheDocument()
  })

  it('gives a row to every task with a due day that no bar marks, by due day and then by title', async () => {
    const venue = planItem('Book the venue', '2026-12-01', '2026-12-10')
    const undatedStep = planItem('Choose a photographer', null, null)
    planningServer([
      venue,
      undatedStep,
      task('Pay the deposit', '2026-12-05', { parentId: venue.id }),
      task('Order the cake', '2026-12-20'),
      task('Book the band', '2026-12-05'),
      task('Ask about parking', '2026-12-05'),
      task('Meet the photographer', '2026-12-12', { parentId: undatedStep.id }),
      task('Think about the honeymoon', null),
    ])
    open('/plan/chart')

    await bar('Book the venue')
    expect(tasksRows().map((row) => row.textContent)).toEqual([
      'Ask about parkingdue 5 Dec 2026',
      'Book the banddue 5 Dec 2026',
      'Meet the photographerdue 12 Dec 2026',
      'Order the cakedue 20 Dec 2026',
    ])
    // A task of a dated step is a mark on its bar and has no row; a task without a due day is nowhere.
    expect(within(await bar('Book the venue')).getByText('Pay the deposit, due 5 Dec 2026')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Pay the deposit/ })).not.toBeInTheDocument()
    expect(screen.queryByText('Think about the honeymoon')).not.toBeInTheDocument()
  })

  it('puts a marker, a diamond, on the day of each task, under the bars and the heading', async () => {
    planningServer([planItem('Book the venue', '2026-12-01', '2026-12-10'), task('Book the band', '2026-12-05')])
    open('/plan/chart')

    const shape = part(await marker('Book the band'), '.gantt-marker-shape')
    // 24 pixels a day from Monday 9 November: the middle of 5 December; one step above, so the row is under it and the heading.
    const middleY = ROW_HEIGHT + HEADING_HEIGHT + ROW_HEIGHT / 2
    expect(shape).toHaveAttribute('d', `M ${26 * DAY + DAY / 2} ${middleY - 9} l 9 9 l -9 9 l -9 -9 z`)
    expect(screen.getByRole('heading', { name: 'Tasks' })).toBeInTheDocument()
  })

  it('says done, overdue, in progress and the amount of a payment in words, not by colour alone', async () => {
    planningServer([
      task('Send the save-the-dates', '2026-11-10', { status: 'DONE' }),
      task('Pay the deposit', '2026-11-12', { amount: DEPOSIT_AMOUNT, paymentKind: 'DEPOSIT' }),
      task('Taste the menu', '2026-11-20', { status: 'IN_PROGRESS' }),
      task('Order the cake', '2026-11-17'),
      task('Choose the band', '2026-11-30', { status: 'DROPPED' }),
    ])
    open('/plan/chart')

    // What a screen reader hears.
    expect(await marker('Send the save-the-dates')).toHaveAccessibleName('Send the save-the-dates, due 10 Nov 2026, done')
    expect(await marker('Pay the deposit')).toHaveAccessibleName('Pay the deposit, 2,500.00\u00a0zł, due 12 Nov 2026, overdue')
    expect(await marker('Taste the menu')).toHaveAccessibleName('Taste the menu, due 20 Nov 2026, in progress')
    // Due today is not overdue yet.
    expect(await marker('Order the cake')).toHaveAccessibleName('Order the cake, due 17 Nov 2026')
    expect(await marker('Choose the band')).toHaveAccessibleName('Choose the band, due 30 Nov 2026, dropped')

    // What a person sees beside the row.
    expect(tasksRows().map((row) => row.textContent)).toEqual([
      'Send the save-the-datesdone · due 10 Nov 2026',
      'Pay the depositoverdue · 2,500.00\u00a0zł · due 12 Nov 2026',
      'Order the cakedue 17 Nov 2026',
      'Taste the menuin progress · due 20 Nov 2026',
      'Choose the banddropped · due 30 Nov 2026',
    ])
    expect(within(tasksRows()[1] as HTMLElement).getByText('overdue')).toHaveClass('gantt-warning')
    expect(await marker('Pay the deposit')).toHaveAttribute('data-overdue')
    expect(await marker('Send the save-the-dates')).toHaveAttribute('data-done')
    expect(await marker('Send the save-the-dates')).not.toHaveAttribute('data-overdue')
  })

  it('draws the chart for tasks alone and reaches from the first to the last day of anything on it', async () => {
    planningServer([task('Order the cake', '2026-09-01'), task('Send the thank-you cards', '2027-08-30')])
    open('/plan/chart')

    // Tuesday 1 September 2026: the frame starts on the Monday a week before it, 24 August.
    expect(part(await marker('Order the cake'), '.gantt-marker-shape')).toHaveAttribute('d', expect.stringMatching(/^M 204 /))
    const last = part(await marker('Send the thank-you cards'), '.gantt-marker-shape')
    const lastX = Number(/^M (\d+) /.exec(last.getAttribute('d') ?? '')?.[1])
    expect(lastX).toBe((dayNumber('2027-08-30') - dayNumber('2026-08-24')) * DAY + DAY / 2)
    expect(document.querySelector('.gantt-body')).toHaveAttribute('width', expect.stringMatching(/^\d+$/))
    expect(Number(document.querySelector('.gantt-body')?.getAttribute('width'))).toBeGreaterThan(lastX)
    expect(screen.queryByText(/No plan item here has dates yet/)).not.toBeInTheDocument()
  })

  it('says what would put something on the chart when nothing has a day, and that nothing matches when filters are set', async () => {
    const server = planningServer([planItem('Choose a photographer', null, null)])
    open('/plan/chart')

    expect(await screen.findByText(/No plan item here has dates yet, and no task has a due date/)).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Tasks' })).not.toBeInTheDocument()

    server.answer('GET /api/entries', Response.json([]))
    await userEvent.selectOptions(screen.getByLabelText('Who'), 'Anna')
    expect(await screen.findByText('Nothing matches these filters.')).toBeInTheDocument()
  })

  it('says there is nothing yet when the plan is empty', async () => {
    planningServer([])
    open('/plan/chart')

    expect(await screen.findByText('Nothing in the plan yet. Add a step, or a task with a due date.')).toBeInTheDocument()
  })

  it('hides the tasks with one control beside the scale, remembers it in the address, and keeps the marks on the bars', async () => {
    const venue = planItem('Book the venue', '2026-12-01', '2026-12-10')
    planningServer([venue, task('Pay the deposit', '2026-12-05', { parentId: venue.id }), task('Book the band', '2026-12-05')])
    open('/plan/chart?scale=week')

    await marker('Book the band')
    await userEvent.selectOptions(screen.getByLabelText('Tasks'), 'Hidden')

    await waitFor(() => expect(window.location.search).toBe('?scale=week&tasks=hidden'))
    expect(screen.queryByRole('heading', { name: 'Tasks' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Book the band/ })).not.toBeInTheDocument()
    expect(within(await bar('Book the venue')).getByText('Pay the deposit, due 5 Dec 2026')).toBeInTheDocument()

    // Shown is the usual: it is not written into the address.
    await userEvent.selectOptions(screen.getByLabelText('Tasks'), 'Shown')
    await waitFor(() => expect(window.location.search).toBe('?scale=week'))
    await marker('Book the band')
  })

  it('starts hidden when the address says so, and says so when the tasks are all there is', async () => {
    planningServer([task('Book the band', '2026-12-05')])
    open('/plan/chart?tasks=hidden')

    expect(await screen.findByText('The tasks are hidden. Show them to see them on the chart.')).toBeInTheDocument()
    expect(screen.getByLabelText('Tasks')).toHaveValue('hidden')
    await userEvent.selectOptions(screen.getByLabelText('Tasks'), 'Shown')
    await marker('Book the band')
  })

  it('asks the server for the rows of the tasks with the filters of the plan, and for the marks without them', async () => {
    const venue = planItem('Book the venue', '2026-12-01', '2026-12-10')
    const server = planningServer([venue, task('Pay the deposit', '2026-12-05', { parentId: venue.id })])
    open('/plan/chart')
    await bar('Book the venue')

    await userEvent.selectOptions(screen.getByLabelText('Who'), 'Piotr')
    await userEvent.selectOptions(screen.getByLabelText('Category'), 'Venue')
    await userEvent.selectOptions(screen.getByLabelText('Priority'), 'High')
    await userEvent.selectOptions(screen.getByLabelText('Show'), 'Done')

    await waitFor(() => {
      const rows = taskRequests(server).filter((query) => query.assigneeId !== undefined && query.categoryId !== undefined && query.priority !== undefined && query.status?.[0] === 'DONE')
      expect(rows.length).toBeGreaterThan(0)
      expect(rows.at(-1)).toMatchObject({ kind: ['TASK'], scheduled: ['true'], sort: ['DUE_DATE'], priority: ['HIGH'] })
    })
    // The plan items were asked the same way; the marks on the bars are all of the tasks, as before.
    const plain = taskRequests(server).filter((query) => query.status === undefined && query.assigneeId === undefined)
    expect(plain.length).toBeGreaterThan(0)
    expect(plain[0]).toEqual({ kind: ['TASK'], scheduled: ['true'], sort: ['DUE_DATE'] })
  })

  it('asks for the open tasks to begin with, as it does for the plan items', async () => {
    const server = planningServer([task('Book the band', '2026-12-05')])
    open('/plan/chart')
    await marker('Book the band')

    expect(taskRequests(server).some((query) => query.status?.join() === 'NOT_STARTED,IN_PROGRESS')).toBe(true)
  })
})

/** A task as the chart shows it moves with `saving`: the server answers with the day it was sent, one version on. */
describe('moving a task on the chart', () => {
  it('moves the marker along its row by pointer and saves the new day with the version it was based on', async () => {
    const band = task('Book the band', '2026-12-05', { version: 3, notes: 'Ask for the demo.' })
    const server = planningServer([band], { [`PUT /api/entries/${band.id}`]: saving(band) })
    open('/plan/chart')

    drag(await marker('Book the band'), 3)

    await waitFor(() => expect(server.requests(`PUT /api/entries/${band.id}`)).toHaveLength(1))
    await expect(server.requests(`PUT /api/entries/${band.id}`)[0]?.json()).resolves.toMatchObject({
      title: 'Book the band', notes: 'Ask for the demo.', startDate: null, dueDate: '2026-12-08', version: 3,
    })
    expect(await screen.findByRole('button', { name: 'Book the band, due 8 Dec 2026' })).toBeInTheDocument()
  })

  it('shows the new day at once while the marker is held, and saves nothing when it is put back', async () => {
    const band = task('Book the band', '2026-12-05')
    const server = planningServer([band])
    open('/plan/chart')

    const mark = await marker('Book the band')
    fireEvent.pointerDown(mark, { clientX: 400, clientY: 120, button: 0, pointerId: 1 })
    fireEvent.pointerMove(window, { clientX: 400 + 2 * DAY + 5, clientY: 120, pointerId: 1 })
    expect(await screen.findByRole('button', { name: 'Book the band, due 7 Dec 2026' })).toBeInTheDocument()
    fireEvent.pointerMove(window, { clientX: 404, clientY: 120, pointerId: 1 })
    fireEvent.pointerUp(window, { clientX: 404, clientY: 120, pointerId: 1 })

    expect(await screen.findByRole('button', { name: 'Book the band, due 5 Dec 2026' })).toBeInTheDocument()
    expect(server.calls.filter((call) => call.startsWith('PUT'))).toEqual([])
    // Held and put back is not a click: the form stays closed.
    expect(window.location.pathname).toBe('/plan/chart')
  })

  it('moves a task with the arrow keys, a day at a time and a week with Shift, saving them one after the other', async () => {
    const band = task('Book the band', '2026-12-05', { version: 7 })
    let answerFirst: () => void = () => undefined
    let calls = 0
    const server = planningServer([band], {
      [`PUT /api/entries/${band.id}`]: async (request) => {
        calls += 1
        if (calls === 1) {
          await new Promise<void>((resolve) => (answerFirst = resolve))
        }
        return saving(band)(request)
      },
    })
    open('/plan/chart')

    ;(await marker('Book the band')).focus()
    await userEvent.keyboard('{ArrowRight}')
    await waitFor(() => expect(server.requests(`PUT /api/entries/${band.id}`)).toHaveLength(1))
    await userEvent.keyboard('{ArrowLeft}{Shift>}{ArrowRight}{/Shift}')
    expect(screen.getByRole('button', { name: 'Book the band, due 12 Dec 2026' })).toHaveFocus()
    expect(server.requests(`PUT /api/entries/${band.id}`)).toHaveLength(1)

    answerFirst()
    await waitFor(() => expect(server.requests(`PUT /api/entries/${band.id}`)).toHaveLength(2))
    const sent = await Promise.all(server.requests(`PUT /api/entries/${band.id}`).map((request) => request.json()))
    expect(sent[0]).toMatchObject({ dueDate: '2026-12-06', version: 7 })
    // The two that waited go together, on the version the first one returned.
    expect(sent[1]).toMatchObject({ dueDate: '2026-12-12', version: 8 })

    await userEvent.keyboard('{Shift>}{ArrowLeft}{/Shift}')
    await waitFor(() => expect(server.requests(`PUT /api/entries/${band.id}`)).toHaveLength(3))
    await expect(server.requests(`PUT /api/entries/${band.id}`)[2]?.json()).resolves.toMatchObject({ dueDate: '2026-12-05', version: 9 })
  })

  it('keeps the keyboard on the marker when the move puts the task in another row', async () => {
    const band = task('Book the band', '2026-12-05')
    const cake = task('Order the cake', '2026-12-06')
    const server = planningServer([band, cake], { [`PUT /api/entries/${band.id}`]: saving(band) })
    open('/plan/chart')

    ;(await marker('Book the band')).focus()
    await userEvent.keyboard('{ArrowRight}{ArrowRight}')
    await waitFor(() => expect(server.requests(`PUT /api/entries/${band.id}`).length).toBeGreaterThan(0))
    await waitFor(() => expect(screen.getByRole('button', { name: /^Book the band, due 7 Dec 2026/ })).toHaveFocus())
  })

  it('goes from row to row with the up and down arrows, from the bars to the tasks, as one stop for the Tab key', async () => {
    const venue = planItem('Book the venue', '2026-12-01', '2026-12-10')
    planningServer([venue, task('Book the band', '2026-12-05'), task('Order the cake', '2026-12-20')])
    open('/plan/chart')

    const first = await bar('Book the venue')
    const band = await marker('Book the band')
    const cake = await marker('Order the cake')
    expect(first).toHaveAttribute('tabindex', '0')
    expect(band).toHaveAttribute('tabindex', '-1')
    expect(screen.getByRole('link', { name: 'Book the band' })).toHaveAttribute('tabindex', '-1')

    first.focus()
    await userEvent.keyboard('{ArrowDown}')
    expect(band).toHaveFocus()
    expect(band).toHaveAttribute('tabindex', '0')
    await userEvent.keyboard('{ArrowDown}')
    expect(cake).toHaveFocus()
    await userEvent.keyboard('{ArrowDown}{ArrowUp}{ArrowUp}{ArrowUp}')
    expect(first).toHaveFocus()
  })

  it('puts the marker back and says so when someone else changed the task in the meantime', async () => {
    const band = task('Book the band', '2026-12-05')
    const server = planningServer([band], { [`PUT /api/entries/${band.id}`]: () => problem(409, 'changed-meanwhile') })
    open('/plan/chart')

    drag(await marker('Book the band'), 3)

    expect(await screen.findByRole('alert')).toHaveTextContent(/Someone else changed that task in the meantime, so your change was not saved/)
    expect(screen.getByRole('button', { name: 'Book the band, due 5 Dec 2026' })).toBeInTheDocument()
    await waitFor(() => expect(server.calls.filter((call) => call === 'GET /api/entries').length).toBeGreaterThan(4))
  })

  it('says that the day could not be saved when the server fails otherwise', async () => {
    const band = task('Book the band', '2026-12-05')
    planningServer([band], { [`PUT /api/entries/${band.id}`]: () => problem(500, 'server-error') })
    open('/plan/chart')

    drag(await marker('Book the band'), 3)

    expect(await screen.findByRole('alert')).toHaveTextContent('The new day could not be saved. Try again.')
  })

  it('opens the form of the task with Enter or with a click that moves nothing, and the form returns to the chart', async () => {
    const band = task('Book the band', '2026-12-05')
    const cake = task('Order the cake', '2026-12-20')
    planningServer([band, cake], {
      [`GET /api/entries/${band.id}`]: Response.json(band),
      [`GET /api/entries/${cake.id}`]: Response.json(cake),
    })
    open('/plan/chart?scale=month&tasks=shown')

    ;(await marker('Book the band')).focus()
    await userEvent.keyboard('{Enter}')
    expect(await screen.findByRole('heading', { name: 'Change this task' })).toBeInTheDocument()
    expect(screen.getByLabelText('Title')).toHaveValue('Book the band')
    await userEvent.click(screen.getByRole('link', { name: 'Cancel' }))
    expect(await marker('Order the cake')).toBeInTheDocument()
    expect(window.location.pathname + window.location.search).toBe('/plan/chart?scale=month&tasks=shown')

    drag(await marker('Order the cake'), 0)
    expect(await screen.findByRole('heading', { name: 'Change this task' })).toBeInTheDocument()
    expect(screen.getByLabelText('Title')).toHaveValue('Order the cake')
  })

  it('lets a finger choose a marker first and drag it only then', async () => {
    const band = task('Book the band', '2026-12-05')
    const cake = task('Order the cake', '2026-12-20')
    const server = planningServer([band, cake], { [`PUT /api/entries/${cake.id}`]: saving(cake) })
    open('/plan/chart')

    await marker('Book the band')
    const mark = await marker('Order the cake')
    drag(mark, 3, null, 'touch')
    expect(mark).toHaveFocus()
    expect(server.calls.filter((call) => call.startsWith('PUT'))).toEqual([])

    drag(mark, 3, null, 'touch')
    await waitFor(() => expect(server.requests(`PUT /api/entries/${cake.id}`)).toHaveLength(1))
  })

  it('does not move a task that is marked on a bar', async () => {
    const venue = planItem('Book the venue', '2026-12-01', '2026-12-10')
    const deposit = task('Pay the deposit', '2026-12-05', { parentId: venue.id })
    const server = planningServer([venue, deposit], { [`PUT /api/entries/${venue.id}`]: saving(venue) })
    open('/plan/chart')

    // Taking hold of its mark takes hold of the bar, as before: the task itself is not sent.
    const mark = within(await bar('Book the venue')).getByText('Pay the deposit, due 5 Dec 2026').parentElement as Element
    drag(mark, 2)
    await waitFor(() => expect(server.requests(`PUT /api/entries/${venue.id}`)).toHaveLength(1))
    expect(server.requests(`PUT /api/entries/${deposit.id}`)).toHaveLength(0)
  })
})
