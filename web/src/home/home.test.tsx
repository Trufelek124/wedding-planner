import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../App.tsx'
import { entry, home, planningServer, problem, wedding } from '../test-server.ts'

function open(path = '/') {
  window.history.pushState(null, '', path)
  render(<App />)
}

describe('the home page', () => {
  it('counts the days as the server does, whatever this device thinks the date is', async () => {
    // The server's count is shown as it comes: 31 would be wrong by this device's clock, and is right.
    planningServer([], { 'GET /api/home': home({ daysLeft: 31 }) })
    open()

    expect(await screen.findByRole('heading', { name: 'Anna & Piotr' })).toBeInTheDocument()
    expect(screen.getByText('Saturday 19 June 2027')).toBeInTheDocument()
    expect(await screen.findByText('31 days to go')).toBeInTheDocument()
  })

  it('names the day itself and the days after it', async () => {
    const server = planningServer([], { 'GET /api/home': home({ daysLeft: 0 }) })
    open()
    expect(await screen.findByText('Today is the day')).toBeInTheDocument()

    server.answer('GET /api/home', home({ daysLeft: -3, overdue: [entry({ title: 'Send the thank-you cards', dueDate: '2027-06-20' })] }))
    await userEvent.click(screen.getByRole('link', { name: 'Tasks' }))
    await userEvent.click(await screen.findByRole('link', { name: 'Home' }))
    expect(await screen.findByText('3 days ago')).toBeInTheDocument()
  })

  it('lists everything overdue and what to turn to next, each with its level, date and a way in', async () => {
    const deposit = entry({ title: 'Pay the deposit', dueDate: '2026-11-10', priority: 'CRITICAL', priorityReason: 'OVERDUE' })
    const venue = entry({ kind: 'PLAN_ITEM', title: 'Book the venue', startDate: '2026-11-01', dueDate: '2026-11-15',
      status: 'IN_PROGRESS', priority: 'CRITICAL', priorityReason: 'OVERDUE' })
    const cake = entry({ title: 'Order the cake', dueDate: '2026-11-19', priority: 'CRITICAL', priorityReason: 'DUE_WITHIN_3_DAYS' })
    const band = entry({ title: 'Book the band', priority: 'HIGH', priorityMode: 'MANUAL', priorityReason: 'SET_BY_HAND' })
    planningServer([], { 'GET /api/home': home({ overdue: [deposit, venue], next: [cake, band] }) })
    open()

    const overdue = within(await screen.findByRole('region', { name: 'Overdue' }))
    expect(overdue.getAllByRole('link').map((link) => link.textContent)).toEqual(['Pay the deposit', 'Book the venue'])
    expect(overdue.getByRole('link', { name: 'Pay the deposit' })).toHaveAttribute('href', `/entries/${deposit.id}`)
    expect(overdue.getByText('due 10 Nov 2026')).toBeInTheDocument()
    expect(overdue.getByText('1 Nov 2026 – 15 Nov 2026')).toBeInTheDocument()
    expect(overdue.getAllByText('Critical')[0]?.parentElement).toHaveTextContent('Critical — overdue')

    const next = within(screen.getByRole('region', { name: 'Next' }))
    expect(next.getAllByRole('link').map((link) => link.textContent)).toEqual(['Order the cake', 'Book the band'])
    expect(next.getByText('High').parentElement).toHaveTextContent('High — set by hand')

    expect(screen.getByRole('link', { name: 'The whole plan' })).toHaveAttribute('href', '/plan')
    expect(screen.getByRole('link', { name: 'All tasks' })).toHaveAttribute('href', '/tasks')
  })

  it('ticks an entry off right there, a plan item as well as a task, and reads the lists again', async () => {
    const deposit = entry({ title: 'Pay the deposit', dueDate: '2026-11-10', priority: 'CRITICAL', priorityReason: 'OVERDUE' })
    const venue = entry({ kind: 'PLAN_ITEM', title: 'Book the venue', dueDate: '2026-11-25', priority: 'HIGH', priorityReason: 'DUE_WITHIN_14_DAYS' })
    const server = planningServer([], {
      'GET /api/home': home({ overdue: [deposit], next: [venue] }),
      [`PUT /api/entries/${deposit.id}/status`]: Response.json({ ...deposit, status: 'DONE' }),
      [`PUT /api/entries/${venue.id}/status`]: Response.json({ ...venue, status: 'DONE' }),
    })
    open()

    await userEvent.click(await screen.findByRole('checkbox', { name: 'Done: Pay the deposit' }))
    await waitFor(() => expect(server.requests(`PUT /api/entries/${deposit.id}/status`)).toHaveLength(1))
    await expect(server.requests(`PUT /api/entries/${deposit.id}/status`)[0]?.json()).resolves.toEqual({ status: 'DONE' })

    // What was ticked is gone when the server says so.
    server.answer('GET /api/home', home({ next: [venue] }))
    await userEvent.click(screen.getByRole('checkbox', { name: 'Done: Book the venue' }))
    await waitFor(() => expect(server.requests(`PUT /api/entries/${venue.id}/status`)).toHaveLength(1))
    await waitFor(() => expect(screen.queryByRole('region', { name: 'Overdue' })).not.toBeInTheDocument())
    expect(server.calls.filter((call) => call === 'GET /api/home').length).toBeGreaterThanOrEqual(3)
  })

  it('says so when a tick could not be saved', async () => {
    const deposit = entry({ title: 'Pay the deposit', dueDate: '2026-11-10' })
    planningServer([], {
      'GET /api/home': home({ overdue: [deposit] }),
      [`PUT /api/entries/${deposit.id}/status`]: () => problem(500, 'internal-error'),
    })
    open()

    await userEvent.click(await screen.findByRole('checkbox', { name: 'Done: Pay the deposit' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('That change was not saved. Try again.')
    expect(screen.getByRole('checkbox', { name: 'Done: Pay the deposit' })).not.toBeChecked()
  })

  it('opens an entry for editing and comes back home afterwards', async () => {
    const deposit = entry({ title: 'Pay the deposit', dueDate: '2026-11-10' })
    planningServer([], {
      'GET /api/home': home({ overdue: [deposit] }),
      [`GET /api/entries/${deposit.id}`]: Response.json(deposit),
    })
    open()

    await userEvent.click(await screen.findByRole('link', { name: 'Pay the deposit' }))
    expect(await screen.findByRole('heading', { name: 'Change this task' })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('link', { name: 'Cancel' }))
    expect(await screen.findByRole('heading', { name: 'Anna & Piotr' })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/')
  })

  it('says where things go when nothing is waiting', async () => {
    planningServer([])
    open()

    expect(await screen.findByText(/No task is waiting for you/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'plan' })).toHaveAttribute('href', '/plan')
    expect(screen.getByRole('link', { name: 'tasks' })).toHaveAttribute('href', '/tasks')
    expect(screen.queryByRole('region', { name: 'Next' })).not.toBeInTheDocument()
  })

  it('keeps the names and the day in view while the lists cannot be loaded, and tries again', async () => {
    const server = planningServer([], { 'GET /api/home': () => problem(500, 'internal-error') })
    open()

    expect(await screen.findByRole('alert')).toHaveTextContent('What is next could not be loaded. Try again.')
    expect(screen.getByRole('heading', { name: 'Anna & Piotr' })).toBeInTheDocument()
    expect(screen.queryByText(/days to go/)).not.toBeInTheDocument()

    server.answer('GET /api/home', home())
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByText('214 days to go')).toBeInTheDocument()
  })
})

describe('the home page while the day is not fixed', () => {
  it('asks for the day instead of counting down, and counts once it is given', async () => {
    const server = planningServer([entry()], {
      'GET /api/wedding': wedding({ date: null }),
      'GET /api/home': home({ weddingDate: null, daysLeft: null, next: [entry({ title: 'Choose a date' })] }),
      'PUT /api/wedding': wedding({ date: '2027-06-19' }),
    })
    open()

    expect(await screen.findByText('The day is not fixed yet. Once it is, this page counts down to it.')).toBeInTheDocument()
    expect(screen.queryByText(/days to go/)).not.toBeInTheDocument()
    // The rest of the page is there all the same.
    expect(await screen.findByRole('link', { name: 'Choose a date' })).toBeInTheDocument()

    server.answer('GET /api/home', home())
    await userEvent.type(screen.getByLabelText('Wedding day'), '2027-06-19')
    await userEvent.click(screen.getByRole('button', { name: 'Save the day' }))

    expect(await screen.findByText('Saturday 19 June 2027')).toBeInTheDocument()
    expect(await screen.findByText('214 days to go')).toBeInTheDocument()
    expect(screen.queryByLabelText('Wedding day')).not.toBeInTheDocument()
    // Only the day changes: the currency and the time zone go back as they were.
    await expect(server.requests('PUT /api/wedding')[0]?.json()).resolves.toEqual({
      date: '2027-06-19', currency: 'PLN', timeZone: 'Europe/Warsaw',
    })
  })

  it('does not send an empty day, and says what the server found wrong with one', async () => {
    const server = planningServer([], {
      'GET /api/wedding': wedding({ date: null }),
      'GET /api/home': home({ weddingDate: null, daysLeft: null }),
      'PUT /api/wedding': () =>
        problem(400, 'bad-request', 'Bad request', { fieldProblems: [{ field: 'date', message: 'Choose a day that has not passed.' }] }),
    })
    open()

    await userEvent.click(await screen.findByRole('button', { name: 'Save the day' }))
    expect(await screen.findByText('Choose the day first.')).toBeInTheDocument()
    expect(server.calls).not.toContain('PUT /api/wedding')

    await userEvent.type(screen.getByLabelText('Wedding day'), '2020-01-01')
    await userEvent.click(screen.getByRole('button', { name: 'Save the day' }))
    expect(await screen.findByText('Choose a day that has not passed.')).toBeInTheDocument()
    expect(screen.getByLabelText('Wedding day')).toHaveAccessibleDescription('Choose a day that has not passed.')
  })
})
