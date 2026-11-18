import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import App from '../App.tsx'
import { ANNA_MEMBER, entry, PIOTR_MEMBER, planningServer, problem, VENUE } from '../test-server.ts'

function open(path: string) {
  window.history.pushState(null, '', path)
  render(<App />)
}

function query(request: Request | undefined): Record<string, string[]> {
  const result: Record<string, string[]> = {}
  new URL(request?.url ?? 'http://x').searchParams.forEach((value, name) => {
    result[name] = [...(result[name] ?? []), value]
  })
  return result
}

describe('the plan', () => {
  it('lists the steps with dates first and those without under "Not scheduled"', async () => {
    const venue = entry({ kind: 'PLAN_ITEM', title: 'Book the venue', startDate: '2026-12-01', dueDate: '2027-01-31',
      categoryId: VENUE.id, assigneeIds: [ANNA_MEMBER, PIOTR_MEMBER], priority: 'HIGH' })
    const photographer = entry({ kind: 'PLAN_ITEM', title: 'Choose a photographer' })
    const server = planningServer([venue, photographer])

    open('/plan')

    const scheduled = await screen.findByRole('region', { name: 'Scheduled' })
    expect(within(scheduled).getByRole('link', { name: 'Book the venue' })).toHaveAttribute('href', `/entries/${venue.id}`)
    expect(within(scheduled).getByText('1 Dec 2026 – 31 Jan 2027')).toBeInTheDocument()
    expect(within(scheduled).getByText('Venue')).toBeInTheDocument()
    expect(within(scheduled).getByText('Anna & Piotr')).toBeInTheDocument()
    expect(within(scheduled).getByText('High')).toBeInTheDocument()
    const notScheduled = screen.getByRole('region', { name: 'Not scheduled' })
    expect(within(notScheduled).getByRole('link', { name: 'Choose a photographer' })).toBeInTheDocument()

    const asked = server.requests('GET /api/entries').map(query).find((params) => params.kind?.[0] === 'PLAN_ITEM')
    expect(asked).toEqual({ kind: ['PLAN_ITEM'], status: ['NOT_STARTED', 'IN_PROGRESS'], sort: ['DUE_DATE'] })
  })

  it('shows the tasks of a step under it and lets them be ticked off there', async () => {
    const invitations = entry({ kind: 'PLAN_ITEM', title: 'Send invitations', taskCount: 2 })
    const envelopes = entry({ title: 'Order the envelopes', parentId: invitations.id, status: 'DONE' })
    const addresses = entry({ title: 'Collect addresses', parentId: invitations.id })
    const server = planningServer([invitations, envelopes, addresses, entry({ title: 'Unrelated task' })], {
      [`PUT /api/entries/${addresses.id}/status`]: Response.json({ ...addresses, status: 'DONE' }),
    })
    open('/plan')

    await userEvent.click(await screen.findByText('2 tasks, 1 done'))
    expect(screen.getByRole('checkbox', { name: 'Done: Order the envelopes' })).toBeChecked()
    expect(screen.queryByText('Unrelated task')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('checkbox', { name: 'Done: Collect addresses' }))

    await waitFor(() => expect(server.calls).toContain(`PUT /api/entries/${addresses.id}/status`))
    await expect(server.requests(`PUT /api/entries/${addresses.id}/status`)[0]?.json()).resolves.toEqual({ status: 'DONE' })
    expect(screen.getByRole('link', { name: 'Add a task to this' })).toHaveAttribute(
      'href', `/entries/new?kind=TASK&parent=${invitations.id}`)
  })

  it('moves a step on from the list', async () => {
    const venue = entry({ kind: 'PLAN_ITEM', title: 'Book the venue' })
    const server = planningServer([venue], {
      [`PUT /api/entries/${venue.id}/status`]: Response.json({ ...venue, status: 'IN_PROGRESS' }),
    })
    open('/plan')

    await userEvent.selectOptions(await screen.findByRole('combobox', { name: 'Status of Book the venue' }), 'In progress')

    await waitFor(() => expect(server.requests(`PUT /api/entries/${venue.id}/status`)).toHaveLength(1))
    await expect(server.requests(`PUT /api/entries/${venue.id}/status`)[0]?.json()).resolves.toEqual({ status: 'IN_PROGRESS' })
  })

  it('narrows the list down and remembers how in the address', async () => {
    const server = planningServer([entry({ kind: 'PLAN_ITEM', title: 'Book the venue' })])
    open('/plan')

    await userEvent.selectOptions(await screen.findByLabelText('Show'), 'Everything')
    await userEvent.selectOptions(screen.getByLabelText('Who'), 'Piotr')
    await userEvent.selectOptions(screen.getByLabelText('Category'), 'Venue')
    await userEvent.selectOptions(screen.getByLabelText('Order'), 'By priority')

    await waitFor(() => {
      const last = server.requests('GET /api/entries').map(query).filter((params) => params.kind?.[0] === 'PLAN_ITEM').at(-1)
      expect(last).toEqual({ kind: ['PLAN_ITEM'], assigneeId: [PIOTR_MEMBER], categoryId: [VENUE.id], sort: ['PRIORITY'] })
    })
    expect(window.location.search).toBe(`?status=all&who=${PIOTR_MEMBER}&category=${VENUE.id}&sort=priority`)
  })

  it('says so when there is nothing yet, and when nothing matches', async () => {
    planningServer([])
    open('/plan')

    expect(await screen.findByText('Nothing in the plan yet. Add the first step.')).toBeInTheDocument()

    await userEvent.selectOptions(screen.getByLabelText('Show'), 'Done')
    expect(await screen.findByText('Nothing matches these filters.')).toBeInTheDocument()
  })
})

describe('the tasks', () => {
  it('lists tasks with what they belong to and ticks one off in one click', async () => {
    const invitations = entry({ kind: 'PLAN_ITEM', title: 'Send invitations' })
    const deposit = entry({ title: 'Pay the venue deposit', dueDate: '2027-01-15', priority: 'CRITICAL', assigneeIds: [PIOTR_MEMBER] })
    const envelopes = entry({ title: 'Order the envelopes', parentId: invitations.id })
    // The server takes its time over the change, so the test can look at the page meanwhile.
    let answer: (response: Response) => void = () => undefined
    const server = planningServer([invitations, deposit, envelopes], {
      [`PUT /api/entries/${deposit.id}/status`]: () => new Promise<Response>((resolve) => (answer = resolve)),
    })
    open('/tasks')

    const depositRow = within((await screen.findByRole('link', { name: 'Pay the venue deposit' })).closest('li') as HTMLElement)
    expect(depositRow.getByText('due 15 Jan 2027')).toBeInTheDocument()
    expect(depositRow.getByText('Critical')).toBeInTheDocument()
    expect(depositRow.getByText('Piotr')).toBeInTheDocument()
    const envelopesRow = within(screen.getByRole('link', { name: 'Order the envelopes' }).closest('li') as HTMLElement)
    expect(envelopesRow.getByText('Send invitations')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Send invitations' })).not.toBeInTheDocument()

    const box = screen.getByRole('checkbox', { name: 'Done: Pay the venue deposit' })
    await userEvent.click(box)

    // The tick shows at once, while it is being saved, and the box cannot be clicked twice.
    await waitFor(() => expect(server.requests(`PUT /api/entries/${deposit.id}/status`)).toHaveLength(1))
    expect(box).toBeChecked()
    expect(box).toBeDisabled()
    answer(Response.json({ ...deposit, status: 'DONE' }))
    await waitFor(() => expect(screen.getByRole('checkbox', { name: 'Done: Pay the venue deposit' })).toBeEnabled())
    await expect(server.requests(`PUT /api/entries/${deposit.id}/status`)[0]?.json()).resolves.toEqual({ status: 'DONE' })
  })

  it('says so when a change could not be saved', async () => {
    const deposit = entry({ title: 'Pay the venue deposit' })
    planningServer([deposit])
    open('/tasks')

    await userEvent.click(await screen.findByRole('checkbox', { name: 'Done: Pay the venue deposit' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('That change was not saved. Try again.')
    await waitFor(() => expect(screen.getByRole('checkbox', { name: 'Done: Pay the venue deposit' })).not.toBeChecked())
  })
})

describe('adding and changing an entry', () => {
  it('adds a task and returns to the tasks', async () => {
    const server = planningServer([], { 'POST /api/entries': () => Response.json(entry({ title: 'Call the florist' }), { status: 201 }) })
    open('/tasks')

    await userEvent.click(await screen.findByRole('link', { name: 'Add a task' }))
    expect(await screen.findByRole('heading', { name: 'Add a task' })).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('Title'), 'Call the florist')
    await userEvent.type(screen.getByLabelText(/^Due/), '2027-03-01')
    await userEvent.click(screen.getByRole('checkbox', { name: 'Anna' }))
    await userEvent.selectOptions(screen.getByLabelText(/^Category/), 'Flowers and decor')
    await userEvent.type(screen.getByLabelText(/^Notes/), 'Ask about peonies.')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByRole('heading', { name: 'Tasks' })).toBeInTheDocument()
    await expect(server.requests('POST /api/entries')[0]?.json()).resolves.toEqual({
      kind: 'TASK',
      title: 'Call the florist',
      notes: 'Ask about peonies.',
      categoryId: 'cccccccc-cccc-4ccc-8ccc-cccccccccc02',
      startDate: null,
      dueDate: '2027-03-01',
      status: 'NOT_STARTED',
      assigneeIds: [ANNA_MEMBER],
      priorityMode: 'AUTO',
      priority: null,
      parentId: null,
      amount: null,
      paymentKind: null,
      paidOn: null,
    })
  })

  it('adds a plan item with its dates and a priority set by hand', async () => {
    const server = planningServer([], { 'POST /api/entries': () => Response.json(entry({ kind: 'PLAN_ITEM' }), { status: 201 }) })
    open('/plan')

    await userEvent.click(await screen.findByRole('link', { name: 'Add a plan item' }))
    await userEvent.type(await screen.findByLabelText('Title'), 'Book the venue')
    await userEvent.type(screen.getByLabelText(/^Starts/), '2026-12-01')
    await userEvent.type(screen.getByLabelText(/^Ends/), '2027-01-31')
    await userEvent.click(screen.getByRole('radio', { name: 'Set it myself' }))
    await userEvent.selectOptions(screen.getByLabelText('Priority level'), 'High')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByRole('heading', { name: 'Plan' })).toBeInTheDocument()
    await expect(server.requests('POST /api/entries')[0]?.json()).resolves.toMatchObject({
      kind: 'PLAN_ITEM', title: 'Book the venue', startDate: '2026-12-01', dueDate: '2027-01-31',
      priorityMode: 'MANUAL', priority: 'HIGH', parentId: null,
    })
    expect(screen.queryByLabelText(/^Part of/)).not.toBeInTheDocument()
  })

  it('starts a task for a plan item with that plan item chosen', async () => {
    const invitations = entry({ kind: 'PLAN_ITEM', title: 'Send invitations' })
    const server = planningServer([invitations], { 'POST /api/entries': () => Response.json(entry(), { status: 201 }) })
    open(`/entries/new?kind=TASK&parent=${invitations.id}`)

    expect(await screen.findByLabelText(/^Part of/)).toHaveValue(invitations.id)
    await userEvent.type(screen.getByLabelText('Title'), 'Order the envelopes')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(server.requests('POST /api/entries')).toHaveLength(1))
    await expect(server.requests('POST /api/entries')[0]?.json()).resolves.toMatchObject({ parentId: invitations.id })
  })

  it('changes an entry, saying which version the change is based on', async () => {
    const florist = entry({ title: 'Call the florist', notes: 'Peonies?', dueDate: '2027-03-01', version: 4,
      assigneeIds: [PIOTR_MEMBER], priorityMode: 'MANUAL', priority: 'HIGH', priorityReason: 'SET_BY_HAND' })
    const path = `/api/entries/${florist.id}`
    const server = planningServer([florist], { [`GET ${path}`]: Response.json(florist), [`PUT ${path}`]: Response.json(florist) })
    open(`/entries/${florist.id}`)

    expect(await screen.findByRole('heading', { name: 'Change this task' })).toBeInTheDocument()
    expect(screen.getByLabelText('Title')).toHaveValue('Call the florist')
    expect(screen.getByLabelText(/^Due/)).toHaveValue('2027-03-01')
    expect(screen.getByRole('checkbox', { name: 'Piotr' })).toBeChecked()
    expect(screen.getByRole('radio', { name: 'Set it myself' })).toBeChecked()
    expect(screen.getByLabelText('Priority level')).toHaveValue('HIGH')

    await userEvent.clear(screen.getByLabelText('Title'))
    await userEvent.type(screen.getByLabelText('Title'), 'Call the florist on Monday')
    await userEvent.click(screen.getByRole('radio', { name: 'Let the planner decide, from the dates' }))
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByRole('heading', { name: 'Tasks' })).toBeInTheDocument()
    await expect(server.requests(`PUT ${path}`)[0]?.json()).resolves.toEqual({
      version: 4, title: 'Call the florist on Monday', notes: 'Peonies?', categoryId: null, startDate: null,
      dueDate: '2027-03-01', status: 'NOT_STARTED', assigneeIds: [PIOTR_MEMBER], priorityMode: 'AUTO', priority: null, parentId: null,
      amount: null, paymentKind: null, paidOn: null,
    })
  })

  it('says a payment\'s money again as it is when the task is saved', async () => {
    const deposit = entry({ title: 'Pay the venue deposit', amount: 250_000, paymentKind: 'DEPOSIT', status: 'DONE',
      completedAt: '2026-11-10T09:00:00Z', paidOn: '2026-11-10', version: 2 })
    const path = `/api/entries/${deposit.id}`
    const server = planningServer([deposit], {
      [`GET ${path}`]: Response.json(deposit),
      [`PUT ${path}`]: Response.json({ ...deposit, version: 3 }),
    })
    open(`/entries/${deposit.id}`)

    await userEvent.type(await screen.findByLabelText('Title'), ' (first half)')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(server.requests(`PUT ${path}`)).toHaveLength(1))
    await expect(server.requests(`PUT ${path}`)[0]?.json()).resolves.toMatchObject({
      title: 'Pay the venue deposit (first half)', amount: 250_000, paymentKind: 'DEPOSIT', paidOn: '2026-11-10', status: 'DONE',
    })
  })

  it('sends no paid date for a payment that is no longer done', async () => {
    const deposit = entry({ title: 'Pay the venue deposit', amount: 250_000, status: 'DONE',
      completedAt: '2026-11-10T09:00:00Z', paidOn: '2026-11-10' })
    const path = `/api/entries/${deposit.id}`
    const server = planningServer([deposit], {
      [`GET ${path}`]: Response.json(deposit),
      [`PUT ${path}`]: Response.json({ ...deposit, status: 'IN_PROGRESS', paidOn: null, version: 1 }),
    })
    open(`/entries/${deposit.id}`)

    await userEvent.selectOptions(await screen.findByLabelText('Status'), 'In progress')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(server.requests(`PUT ${path}`)).toHaveLength(1))
    await expect(server.requests(`PUT ${path}`)[0]?.json()).resolves.toMatchObject({ amount: 250_000, status: 'IN_PROGRESS', paidOn: null })
  })

  it('says next to the field what the server found wrong', async () => {
    planningServer([], {
      'POST /api/entries': problem(400, 'bad-request', 'The request is not valid', {
        fieldProblems: [
          { field: 'title', message: 'Enter a title.' },
          { field: 'dueDate', message: 'The end date cannot be before the start date.' },
        ],
      }),
    })
    open('/entries/new?kind=PLAN_ITEM')

    await userEvent.click(await screen.findByRole('button', { name: 'Save' }))

    await waitFor(() => expect(screen.getByLabelText('Title')).toBeInvalid())
    expect(screen.getByLabelText('Title')).toHaveAccessibleDescription('Enter a title.')
    expect(screen.getByLabelText(/^Ends/)).toHaveAccessibleDescription(/The end date cannot be before the start date\./)
    expect(screen.getByRole('heading', { name: 'Add a plan item' })).toBeInTheDocument()
  })

  it('does not overwrite what the other owner saved in the meantime, and offers the latest version', async () => {
    const florist = entry({ title: 'Call the florist', version: 1 })
    const path = `/api/entries/${florist.id}`
    const server = planningServer([florist], {
      [`GET ${path}`]: Response.json(florist),
      [`PUT ${path}`]: problem(409, 'changed-meanwhile', 'Someone else changed this in the meantime'),
    })
    open(`/entries/${florist.id}`)

    await userEvent.type(await screen.findByLabelText(/^Notes/), 'My note')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Someone else changed this in the meantime')
    server.answer(`GET ${path}`, Response.json({ ...florist, title: 'Call the florist about peonies', version: 2 }))
    await userEvent.click(screen.getByRole('button', { name: 'Load the latest version' }))

    await waitFor(() => expect(screen.getByLabelText('Title')).toHaveValue('Call the florist about peonies'))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('deletes a task only after being told twice', async () => {
    const florist = entry({ title: 'Call the florist' })
    const path = `/api/entries/${florist.id}`
    const server = planningServer([florist], { [`GET ${path}`]: Response.json(florist), [`DELETE ${path}`]: new Response(null, { status: 204 }) })
    open(`/entries/${florist.id}`)

    await userEvent.click(await screen.findByRole('button', { name: 'Delete this task' }))
    expect(screen.getByText('Delete “Call the florist”? This cannot be undone.')).toBeInTheDocument()
    expect(server.calls).not.toContain(`DELETE ${path}`)
    await userEvent.click(screen.getByRole('button', { name: 'Yes, delete it' }))

    expect(await screen.findByRole('heading', { name: 'Tasks' })).toBeInTheDocument()
    expect(server.calls).toContain(`DELETE ${path}`)
  })

  it('asks what happens to the tasks of a plan item that is deleted', async () => {
    const invitations = entry({ kind: 'PLAN_ITEM', title: 'Send invitations', taskCount: 2 })
    const path = `/api/entries/${invitations.id}`
    const server = planningServer([invitations], { [`GET ${path}`]: Response.json(invitations), [`DELETE ${path}`]: new Response(null, { status: 204 }) })
    open(`/entries/${invitations.id}`)

    await userEvent.click(await screen.findByRole('button', { name: 'Delete this plan item' }))
    expect(screen.getByText('This plan item has 2 tasks. What should happen to them?')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Delete it, keep the tasks' }))

    expect(await screen.findByRole('heading', { name: 'Plan' })).toBeInTheDocument()
    expect(query(server.requests(`DELETE ${path}`)[0])).toEqual({ tasks: ['KEEP'] })
    expect(screen.queryByRole('button', { name: 'Turn into a task' })).not.toBeInTheDocument()
  })

  it('turns a task into a plan item and stays on its form', async () => {
    const florist = entry({ title: 'Choose the flowers', dueDate: '2027-04-01' })
    const path = `/api/entries/${florist.id}`
    const server = planningServer([florist], {
      [`GET ${path}`]: Response.json(florist),
      [`POST ${path}/convert`]: Response.json({ ...florist, kind: 'PLAN_ITEM', version: 1 }),
    })
    open(`/entries/${florist.id}`)

    await userEvent.click(await screen.findByRole('button', { name: 'Turn into a plan item' }))

    expect(await screen.findByRole('heading', { name: 'Change this plan item' })).toBeInTheDocument()
    await expect(server.requests(`POST ${path}/convert`)[0]?.json()).resolves.toEqual({ kind: 'PLAN_ITEM' })
    expect(screen.getByLabelText(/^Ends/)).toHaveValue('2027-04-01')
    expect(screen.getByRole('button', { name: 'Turn into a task' })).toBeInTheDocument()
  })
})

describe('priorities', () => {
  it('says the level of every entry in words, with the reason', async () => {
    const deposit = entry({ title: 'Pay the deposit', dueDate: '2026-11-19', priority: 'CRITICAL', priorityReason: 'DUE_WITHIN_3_DAYS' })
    const band = entry({ title: 'Book the band', priority: 'HIGH', priorityMode: 'MANUAL', priorityReason: 'SET_BY_HAND' })
    const cake = entry({ title: 'Taste cakes', status: 'DONE', priorityReason: 'FINISHED' })
    planningServer([deposit, band, cake])
    open('/tasks?status=all')

    const row = async (title: string) => within((await screen.findByRole('link', { name: title })).closest('li') as HTMLElement)
    expect((await row('Pay the deposit')).getByText('Critical').parentElement).toHaveTextContent('Critical — due within 3 days')
    expect((await row('Book the band')).getByText('High').parentElement).toHaveTextContent('High — set by hand')
    expect((await row('Taste cakes')).getByText('Low').parentElement).toHaveTextContent('Low — needs no more attention')
  })

  it('words the reason for a plan item by its end, and shows only the level for a reason it does not know', async () => {
    const venue = entry({ kind: 'PLAN_ITEM', title: 'Book the venue', dueDate: '2026-11-27', priority: 'HIGH', priorityReason: 'DUE_WITHIN_14_DAYS' })
    const late = entry({ kind: 'PLAN_ITEM', title: 'Order the rings', startDate: '2026-11-01', dueDate: '2027-03-01', priority: 'NORMAL',
      priorityReason: 'SHOULD_HAVE_STARTED' })
    const newer = entry({ kind: 'PLAN_ITEM', title: 'From a newer server', priority: 'NORMAL', priorityReason: 'SOMETHING_NEW' as never })
    planningServer([venue, late, newer])
    open('/plan')

    const row = async (title: string) => within((await screen.findByRole('link', { name: title })).closest('li') as HTMLElement)
    expect((await row('Book the venue')).getByText('High').parentElement).toHaveTextContent('High — ends within 14 days')
    expect((await row('Order the rings')).getByText('Normal').parentElement).toHaveTextContent('Normal — should have started')
    expect((await row('From a newer server')).getByText('Normal').parentElement).toHaveTextContent(/^Normal$/)
  })

  it('narrows a list to one level, asks the server for it and keeps it in the address', async () => {
    const server = planningServer([entry({ title: 'Anything' })])
    open('/tasks')

    await userEvent.selectOptions(await screen.findByLabelText('Priority'), 'High')

    await waitFor(() => expect(window.location.search).toBe('?priority=HIGH'))
    await waitFor(() =>
      expect(server.requests('GET /api/entries').map(query).filter((params) => params.kind?.[0] === 'TASK').at(-1)).toEqual({
        kind: ['TASK'], status: ['NOT_STARTED', 'IN_PROGRESS'], priority: ['HIGH'], sort: ['DUE_DATE'],
      }),
    )

    await userEvent.selectOptions(screen.getByLabelText('Priority'), 'Any')
    await waitFor(() => expect(window.location.search).toBe(''))
  })

  it('says on the form what the planner decides right now, and why', async () => {
    const deposit = entry({ title: 'Pay the deposit', dueDate: '2026-11-19', priority: 'CRITICAL', priorityReason: 'DUE_WITHIN_3_DAYS' })
    planningServer([deposit], { [`GET /api/entries/${deposit.id}`]: Response.json(deposit) })
    open(`/entries/${deposit.id}`)

    const planner = await screen.findByRole('radio', { name: 'Let the planner decide, from the dates' })
    expect(planner).toBeChecked()
    expect(planner).toHaveAccessibleDescription('Right now: Critical — due within 3 days.')

    // Choosing a level by hand starts from the one the planner had, and the planner's line goes away.
    await userEvent.click(screen.getByRole('radio', { name: 'Set it myself' }))
    expect(screen.getByLabelText('Priority level')).toHaveValue('CRITICAL')
    expect(screen.queryByText(/^Right now/)).not.toBeInTheDocument()
  })

  it('keeps a level set by hand when something else about the entry is saved', async () => {
    const band = entry({ title: 'Book the band', priority: 'HIGH', priorityMode: 'MANUAL', priorityReason: 'SET_BY_HAND', version: 3 })
    const server = planningServer([band], {
      [`GET /api/entries/${band.id}`]: Response.json(band),
      [`PUT /api/entries/${band.id}`]: Response.json({ ...band, version: 4 }),
    })
    open(`/entries/${band.id}`)

    expect(await screen.findByRole('radio', { name: 'Set it myself' })).toBeChecked()
    expect(screen.getByLabelText('Priority level')).toHaveValue('HIGH')
    expect(screen.queryByText(/^Right now/)).not.toBeInTheDocument()
    await userEvent.type(screen.getByLabelText(/^Notes/), 'Ask for the song list.')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(server.requests(`PUT /api/entries/${band.id}`)).toHaveLength(1))
    await expect(server.requests(`PUT /api/entries/${band.id}`)[0]?.json()).resolves.toMatchObject({
      priorityMode: 'MANUAL', priority: 'HIGH', notes: 'Ask for the song list.', version: 3,
    })
  })

  it('hands an entry back to the planner without sending a level', async () => {
    const band = entry({ title: 'Book the band', priority: 'HIGH', priorityMode: 'MANUAL', priorityReason: 'SET_BY_HAND', version: 3 })
    const server = planningServer([band], {
      [`GET /api/entries/${band.id}`]: Response.json(band),
      [`PUT /api/entries/${band.id}`]: Response.json({ ...band, priorityMode: 'AUTO', priority: 'LOW', priorityReason: 'NO_DATE', version: 4 }),
    })
    open(`/entries/${band.id}`)

    await userEvent.click(await screen.findByRole('radio', { name: 'Let the planner decide, from the dates' }))
    expect(screen.queryByLabelText('Priority level')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(server.requests(`PUT /api/entries/${band.id}`)).toHaveLength(1))
    await expect(server.requests(`PUT /api/entries/${band.id}`)[0]?.json()).resolves.toMatchObject({ priorityMode: 'AUTO', priority: null })
  })
})

describe('getting around', () => {
  it('has a way to every page and marks the one that is open', async () => {
    planningServer([])
    open('/')

    await userEvent.click(await screen.findByRole('link', { name: 'Plan' }))
    expect(await screen.findByRole('heading', { name: 'Plan' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Plan' })).toHaveAttribute('aria-current', 'page')

    await userEvent.click(screen.getByRole('link', { name: 'Tasks' }))
    expect(await screen.findByRole('heading', { name: 'Tasks' })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('link', { name: 'Home' }))
    expect(await screen.findByRole('heading', { name: 'Anna & Piotr' })).toBeInTheDocument()
  })

  it('opens a new page at its top, with the keyboard inside the page', async () => {
    const scrolled = vi.spyOn(window, 'scrollTo')
    planningServer([])
    open('/tasks')

    await userEvent.click(await screen.findByRole('link', { name: 'Add a task' }))

    expect(await screen.findByRole('heading', { name: 'Add a task' })).toBeInTheDocument()
    expect(scrolled).toHaveBeenCalledWith(0, 0)
    expect(screen.getByRole('main')).toHaveFocus()
  })

  it('keeps the frame, and one main area, while a page loads or cannot be loaded', async () => {
    let answer: (response: Response) => void = () => undefined
    const server = planningServer([], { 'GET /api/categories': () => new Promise<Response>((resolve) => (answer = resolve)) })
    open('/plan')

    expect(await screen.findByRole('link', { name: 'Tasks' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('Loading…')
    expect(screen.getAllByRole('main')).toHaveLength(1)

    // The page asks just after it appears; the answer can only be given once it has asked.
    await waitFor(() => expect(server.calls).toContain('GET /api/categories'))
    answer(problem(500, 'internal-error'))
    expect(await screen.findByRole('alert')).toHaveTextContent('The plan could not be loaded. Try again.')
    expect(screen.getAllByRole('main')).toHaveLength(1)

    server.answer('GET /api/categories', Response.json([VENUE]))
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('heading', { name: 'Plan' })).toBeInTheDocument()
  })

  it('leads an address that names no page home', async () => {
    planningServer([])
    open('/nowhere/at/all')

    expect(await screen.findByRole('heading', { name: 'Anna & Piotr' })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/')
  })
})
