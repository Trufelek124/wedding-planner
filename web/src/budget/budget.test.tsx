import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../App.tsx'
import { budget, entry, FLOWERS, home, planningServer, problem, VENUE } from '../test-server.ts'

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

/** 60,000 in all; the venue exactly as planned, the flowers over their plan, one payment without a category. */
function aBudgetInUse() {
  return budget({
    total: 6_000_000,
    planned: 2_500_000,
    committed: 2_700_000,
    paid: 500_000,
    categories: [
      { categoryId: VENUE.id, name: VENUE.name, planned: 2_000_000, committed: 2_000_000, paid: 500_000, payments: 3 },
      { categoryId: FLOWERS.id, name: FLOWERS.name, planned: 500_000, committed: 620_000, paid: 0, payments: 2 },
      { categoryId: 'cccccccc-cccc-4ccc-8ccc-cccccccccc03', name: 'Music', planned: null, committed: 0, paid: 0, payments: 0 },
    ],
    uncategorised: { committed: 80_000, paid: 0, payments: 1 },
    version: 3,
  })
}

function tile(name: string) {
  return screen.getByText(name, { selector: 'dt' }).parentElement as HTMLElement
}

describe('a payment on the task form', () => {
  it('makes a task a payment: the amount as people write it, sent as whole grosze', async () => {
    const server = planningServer([], { 'POST /api/entries': Response.json(entry(), { status: 201 }) })
    open('/entries/new?kind=TASK')

    await userEvent.type(await screen.findByLabelText('Title'), 'Pay the venue deposit')
    expect(screen.queryByLabelText(/^Kind of payment/)).not.toBeInTheDocument()
    await userEvent.type(screen.getByLabelText(/^Amount in PLN/), '2 500,50')
    await userEvent.selectOptions(screen.getByLabelText(/^Kind of payment/), 'Deposit')
    expect(screen.getByText('It counts as paid once its status is Done.')).toBeInTheDocument()
    expect(screen.queryByLabelText(/^Paid on/)).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(server.requests('POST /api/entries')).toHaveLength(1))
    await expect(server.requests('POST /api/entries')[0]?.json()).resolves.toMatchObject({
      kind: 'TASK', title: 'Pay the venue deposit', amount: 250_050, paymentKind: 'DEPOSIT', paidOn: null, status: 'NOT_STARTED',
    })
  })

  it('takes the day it was paid once it is done', async () => {
    const deposit = entry({ title: 'Pay the venue deposit', amount: 250_000, paymentKind: 'DEPOSIT', version: 1 })
    const path = `/api/entries/${deposit.id}`
    const server = planningServer([deposit], {
      [`GET ${path}`]: Response.json(deposit),
      [`PUT ${path}`]: Response.json({ ...deposit, status: 'DONE', paidOn: '2026-11-10', version: 2 }),
    })
    open(`/entries/${deposit.id}`)

    expect(await screen.findByLabelText(/^Amount in PLN/)).toHaveValue('2500')
    await userEvent.selectOptions(screen.getByLabelText('Status'), 'Done')
    expect(screen.getByLabelText(/^Paid on/)).toHaveAccessibleDescription('Left empty, it is today.')
    await userEvent.type(screen.getByLabelText(/^Paid on/), '2026-11-10')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(server.requests(`PUT ${path}`)).toHaveLength(1))
    await expect(server.requests(`PUT ${path}`)[0]?.json()).resolves.toMatchObject({
      version: 1, amount: 250_000, paymentKind: 'DEPOSIT', status: 'DONE', paidOn: '2026-11-10',
    })
  })

  it('takes the money away when the amount is emptied', async () => {
    const deposit = entry({ title: 'Pay the venue deposit', amount: 250_000, paymentKind: 'DEPOSIT' })
    const path = `/api/entries/${deposit.id}`
    const server = planningServer([deposit], {
      [`GET ${path}`]: Response.json(deposit),
      [`PUT ${path}`]: Response.json({ ...deposit, amount: null, paymentKind: null, version: 1 }),
    })
    open(`/entries/${deposit.id}`)

    await userEvent.clear(await screen.findByLabelText(/^Amount in PLN/))
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(server.requests(`PUT ${path}`)).toHaveLength(1))
    await expect(server.requests(`PUT ${path}`)[0]?.json()).resolves.toMatchObject({ amount: null, paymentKind: null, paidOn: null })
  })

  it('sends nothing while the amount cannot be read, and says how to write one', async () => {
    const server = planningServer([])
    open('/entries/new?kind=TASK')

    await userEvent.type(await screen.findByLabelText('Title'), 'Pay the band')
    await userEvent.type(screen.getByLabelText(/^Amount in PLN/), 'about 400')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(screen.getByLabelText(/^Amount in PLN/)).toBeInvalid()
    expect(screen.getByLabelText(/^Amount in PLN/)).toHaveAccessibleDescription(/Enter an amount like 2500 or 2500\.50\./)
    expect(server.calls).not.toContain('POST /api/entries')

    // Once the text is changed, what was said about the old one is gone.
    await userEvent.clear(screen.getByLabelText(/^Amount in PLN/))
    await userEvent.type(screen.getByLabelText(/^Amount in PLN/), '400')
    expect(screen.getByLabelText(/^Amount in PLN/)).toBeValid()
  })

  it('shows what the server says about the amount next to it', async () => {
    planningServer([], {
      'POST /api/entries': problem(400, 'bad-request', 'Bad request', {
        fieldProblems: [{ field: 'amount', message: 'Enter an amount above zero.' }],
      }),
    })
    open('/entries/new?kind=TASK')

    await userEvent.type(await screen.findByLabelText('Title'), 'Pay nothing')
    await userEvent.type(screen.getByLabelText(/^Amount in PLN/), '0')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(screen.getByLabelText(/^Amount in PLN/)).toHaveAccessibleDescription(/Enter an amount above zero\./))
  })

  it('is not offered on a plan item, and a payment is not offered to become one', async () => {
    const deposit = entry({ title: 'Pay the venue deposit', amount: 250_000 })
    planningServer([deposit], { [`GET /api/entries/${deposit.id}`]: Response.json(deposit) })
    open('/entries/new?kind=PLAN_ITEM')
    expect(await screen.findByLabelText('Title')).toBeInTheDocument()
    expect(screen.queryByLabelText(/^Amount in PLN/)).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('link', { name: 'Tasks' }))
    await userEvent.click(await screen.findByRole('link', { name: 'Pay the venue deposit' }))

    expect(await screen.findByText(/A payment stays a task: a plan item carries no money\./)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Turn into a plan item' })).not.toBeInTheDocument()
  })
})

describe('payments in the task list', () => {
  it('shows the amount and whether it is paid, and the box marks it paid', async () => {
    const deposit = entry({ title: 'Pay the venue deposit', amount: 250_000, paymentKind: 'DEPOSIT', dueDate: '2026-12-01' })
    const rings = entry({ title: 'Pay for the rings', amount: 180_050, status: 'DONE', completedAt: '2026-11-10T09:00:00Z', paidOn: '2026-11-10' })
    const band = entry({ title: 'A second band', amount: 300_000, status: 'DROPPED' })
    const call = entry({ title: 'Call the florist' })
    const server = planningServer([deposit, rings, band, call], {
      [`PUT /api/entries/${deposit.id}/status`]: Response.json({ ...deposit, status: 'DONE', paidOn: '2026-11-17' }),
    })
    open('/tasks?status=all')

    const row = (title: string) => within((screen.getByRole('link', { name: title }).closest('li') as HTMLElement))
    expect(await screen.findByRole('checkbox', { name: 'Paid: Pay the venue deposit' })).not.toBeChecked()
    expect(row('Pay the venue deposit').getByText('2,500.00 zł, deposit, to pay')).toBeInTheDocument()
    expect(row('Pay for the rings').getByText('1,800.50 zł, paid 10 Nov 2026')).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: 'Paid: Pay for the rings' })).toBeChecked()
    expect(row('A second band').getByText('3,000.00 zł, dropped, not counted')).toBeInTheDocument()
    // A task without money is done, not paid.
    expect(screen.getByRole('checkbox', { name: 'Done: Call the florist' })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('checkbox', { name: 'Paid: Pay the venue deposit' }))

    await waitFor(() => expect(server.requests(`PUT /api/entries/${deposit.id}/status`)).toHaveLength(1))
    await expect(server.requests(`PUT /api/entries/${deposit.id}/status`)[0]?.json()).resolves.toEqual({ status: 'DONE' })
  })

  it('narrows down to the payments, and to what has no category, and keeps both in the address', async () => {
    const server = planningServer([entry({ title: 'Pay the band', amount: 400_000 })])
    open('/tasks')

    await userEvent.selectOptions(await screen.findByLabelText('Money'), 'Payments only')
    await userEvent.selectOptions(screen.getByLabelText('Category'), 'No category')

    await waitFor(() => {
      const last = server.requests('GET /api/entries').map(query).filter((params) => params.kind?.[0] === 'TASK').at(-1)
      expect(last).toMatchObject({ payment: ['true'], withoutCategory: ['true'] })
      expect(last).not.toHaveProperty('categoryId')
    })
    expect(window.location.search).toBe('?money=payments&category=none')
  })

  it('offers the money filter for tasks only', async () => {
    planningServer([])
    open('/plan')

    expect(await screen.findByLabelText('Category')).toBeInTheDocument()
    expect(screen.queryByLabelText('Money')).not.toBeInTheDocument()
  })
})

describe('the budget page', () => {
  it('shows planned, committed and paid in total', async () => {
    planningServer([], { 'GET /api/budget': Response.json(aBudgetInUse()) })
    open('/budget')

    expect(await screen.findByRole('heading', { level: 1, name: 'Budget' })).toBeInTheDocument()
    expect(tile('Budget')).toHaveTextContent('60,000.00 zł')
    expect(tile('Budget')).toHaveTextContent('33,000.00 zł not committed yet')
    expect(tile('Planned')).toHaveTextContent('25,000.00 zł')
    expect(tile('Planned')).toHaveTextContent('35,000.00 zł of the budget not planned')
    expect(tile('Committed')).toHaveTextContent('27,000.00 zł')
    expect(tile('Committed')).toHaveTextContent('6 payments')
    expect(tile('Paid')).toHaveTextContent('5,000.00 zł')
    expect(tile('Paid')).toHaveTextContent('22,000.00 zł still to pay')
    // The bar says the same in words to whoever does not see it.
    expect(screen.getByRole('img', { name: /^The whole budget: 5,000\.00.zł paid, 22,000\.00.zł still to pay, of 60,000\.00.zł budget$/ }))
      .toBeInTheDocument()
  })

  it('shows each category against its plan, in words as well as in a bar, with its payments one click away', async () => {
    planningServer([], { 'GET /api/budget': Response.json(aBudgetInUse()) })
    open('/budget')

    const venue = within((await screen.findByRole('heading', { level: 3, name: 'Venue' })).closest('li') as HTMLElement)
    expect(venue.getByText('20,000.00 zł of 20,000.00 zł')).toBeInTheDocument()
    expect(venue.getByText('Exactly as planned')).toBeInTheDocument()
    expect(venue.getByText('5,000.00 zł paid')).toBeInTheDocument()
    expect(venue.getByRole('img', { name: /^Venue: 5,000\.00.zł paid, 15,000\.00.zł still to pay, of 20,000\.00.zł planned$/ })).toBeInTheDocument()
    expect(venue.getByRole('link', { name: '3 payments for Venue' })).toHaveAttribute(
      'href', `/tasks?status=all&category=${VENUE.id}&money=payments`)

    const flowers = within(screen.getByRole('heading', { level: 3, name: 'Flowers and decor' }).closest('li') as HTMLElement)
    expect(flowers.getByText('6,200.00 zł of 5,000.00 zł')).toBeInTheDocument()
    // Over the plan is said in words; the colour and the sign only go with them.
    expect(flowers.getByText(/Over the plan by 1,200\.00 zł/)).toBeInTheDocument()
    expect(flowers.getByRole('img', { name: /1,200\.00.zł more than the 5,000\.00.zł planned$/ })).toBeInTheDocument()

    const rest = within(screen.getByRole('heading', { level: 3, name: 'No category' }).closest('li') as HTMLElement)
    expect(rest.getByText('800.00 zł committed')).toBeInTheDocument()
    expect(rest.getByRole('link', { name: '1 payment without a category' })).toHaveAttribute(
      'href', '/tasks?status=all&category=none&money=payments')

    // A category with nothing in it gets no row of its own.
    expect(screen.queryByRole('heading', { level: 3, name: 'Music' })).not.toBeInTheDocument()
    expect(screen.getByText('Nothing planned or paid yet for: Music.')).toBeInTheDocument()
  })

  it('says so when the payments or the plan go over the budget', async () => {
    planningServer([], {
      'GET /api/budget': Response.json({ ...aBudgetInUse(), total: 2_000_000 }),
    })
    open('/budget')

    await screen.findByRole('heading', { level: 1, name: 'Budget' })
    expect(tile('Budget')).toHaveTextContent('Over by 7,000.00 zł')
    expect(tile('Planned')).toHaveTextContent('5,000.00 zł more than the budget')
    expect(screen.getByRole('img', { name: /^The whole budget: .* 7,000\.00.zł more than the 20,000\.00.zł budget$/ })).toBeInTheDocument()
  })

  it('leads from a line to its payments in the task list', async () => {
    const server = planningServer([entry({ title: 'Pay the venue deposit', amount: 500_000, categoryId: VENUE.id })], {
      'GET /api/budget': Response.json(aBudgetInUse()),
    })
    open('/budget')

    await userEvent.click(await screen.findByRole('link', { name: '3 payments for Venue' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Tasks' })).toBeInTheDocument()
    expect(await screen.findByRole('link', { name: 'Pay the venue deposit' })).toBeInTheDocument()
    const asked = server.requests('GET /api/entries').map(query).filter((params) => params.kind?.[0] === 'TASK').at(-1)
    expect(asked).toMatchObject({ categoryId: [VENUE.id], payment: ['true'] })
    expect(asked).not.toHaveProperty('status')
    expect(screen.getByLabelText('Money')).toHaveValue('payments')
  })

  it('invites to plan while there is no money in the planner at all', async () => {
    planningServer([], { 'GET /api/budget': Response.json(budget()) })
    open('/budget')

    expect(await screen.findByText(/No budget yet\./)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Plan the budget' })).toHaveAttribute('href', '/budget/plan')
    expect(screen.getByRole('link', { name: 'task with an amount' })).toHaveAttribute('href', '/entries/new?kind=TASK')
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
  })

  it('works without a total: the categories are the measure', async () => {
    planningServer([], { 'GET /api/budget': Response.json({ ...aBudgetInUse(), total: null }) })
    open('/budget')

    await screen.findByRole('heading', { level: 1, name: 'Budget' })
    expect(tile('Budget')).toHaveTextContent('Not set')
    expect(screen.getByRole('img', { name: /^The whole budget: .* 2,000\.00.zł more than the 25,000\.00.zł planned$/ })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Change the plan' })).toBeInTheDocument()
  })

  it('is one of the pages in the top bar, and offers to try again when it cannot be loaded', async () => {
    const server = planningServer([])
    open('/')

    await userEvent.click(await screen.findByRole('link', { name: 'Budget' }))
    expect(await screen.findByText('The budget could not be loaded. Try again.')).toBeInTheDocument()

    server.answer('GET /api/budget', Response.json(aBudgetInUse()))
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('heading', { level: 3, name: 'Venue' })).toBeInTheDocument()
  })
})

describe('planning the budget', () => {
  it('sets the total and the planned amounts as whole grosze, based on the version it loaded', async () => {
    const server = planningServer([], {
      'GET /api/budget': Response.json(aBudgetInUse()),
      'PUT /api/budget': Response.json({ ...aBudgetInUse(), version: 4 }),
    })
    open('/budget')
    await userEvent.click(await screen.findByRole('link', { name: 'Change the plan' }))

    expect(await screen.findByLabelText(/^Budget for everything/)).toHaveValue('60000')
    expect(screen.getByLabelText(/^Venue/)).toHaveValue('20000')
    expect(screen.getByLabelText(/^Venue/)).toHaveAccessibleDescription(/^Committed so far: 20,000\.00.zł$/)
    expect(screen.getByLabelText(/^Music/)).toHaveValue('')
    await userEvent.clear(screen.getByLabelText(/^Venue/))
    await userEvent.clear(screen.getByLabelText(/^Flowers and decor/))
    await userEvent.type(screen.getByLabelText(/^Flowers and decor/), '5 000,50')
    await userEvent.type(screen.getByLabelText(/^Music/), '0')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Budget' })).toBeInTheDocument()
    await expect(server.requests('PUT /api/budget')[0]?.json()).resolves.toEqual({
      version: 3,
      total: 6_000_000,
      // What is left empty is not planned; a zero is something the couple said.
      planned: [
        { categoryId: FLOWERS.id, amount: 500_050 },
        { categoryId: 'cccccccc-cccc-4ccc-8ccc-cccccccccc03', amount: 0 },
      ],
    })
  })

  it('adds the categories up while they are typed', async () => {
    planningServer([], { 'GET /api/budget': Response.json(aBudgetInUse()) })
    open('/budget/plan')

    const sum = (await screen.findByText(/The categories together/)).closest('p') as HTMLElement
    expect(sum).toHaveTextContent('The categories together: 25,000.00 zł — 35,000.00 zł of the budget not planned')

    await userEvent.type(screen.getByLabelText(/^Music/), '40000')
    expect(sum).toHaveTextContent('The categories together: 65,000.00 zł — 5,000.00 zł more than the budget for everything')

    await userEvent.clear(screen.getByLabelText(/^Budget for everything/))
    expect(sum).toHaveTextContent(/^The categories together: 65,000\.00 zł$/)
  })

  it('says which field is not an amount and sends nothing', async () => {
    const server = planningServer([], { 'GET /api/budget': Response.json(aBudgetInUse()) })
    open('/budget/plan')

    await userEvent.type(await screen.findByLabelText(/^Music/), 'a lot')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(screen.getByLabelText(/^Music/)).toBeInvalid()
    expect(screen.getByLabelText(/^Music/)).toHaveAccessibleDescription(/Enter an amount like 2500 or 2500\.50, or leave it empty\./)
    expect(screen.getByLabelText(/^Venue/)).toBeValid()
    expect(server.calls).not.toContain('PUT /api/budget')
  })

  it('shows what the server says next to the field it is about', async () => {
    planningServer([], {
      'GET /api/budget': Response.json(aBudgetInUse()),
      'PUT /api/budget': problem(400, 'bad-request', 'Bad request', {
        fieldProblems: [
          { field: 'total', message: 'That amount is too large.' },
          // The second amount that was sent: Venue is left empty here, so this is Music.
          { field: 'planned[1].amount', message: 'That amount is too large.' },
        ],
      }),
    })
    open('/budget/plan')

    await userEvent.clear(await screen.findByLabelText(/^Venue/))
    await userEvent.type(screen.getByLabelText(/^Music/), '99999999999')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(screen.getByLabelText(/^Music/)).toBeInvalid())
    expect(screen.getByLabelText(/^Budget for everything/)).toBeInvalid()
    expect(screen.getByLabelText(/^Flowers and decor/)).toBeValid()
    expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled()
  })

  it('offers the latest version when someone else changed the budget meanwhile, and overwrites nothing', async () => {
    const server = planningServer([], {
      'GET /api/budget': Response.json(aBudgetInUse()),
      'PUT /api/budget': problem(409, 'changed-meanwhile', 'Changed meanwhile'),
    })
    open('/budget/plan')

    await userEvent.type(await screen.findByLabelText(/^Music/), '4000')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByText('Someone else changed the budget in the meantime, so your change was not saved.')).toBeInTheDocument()

    server.answer('GET /api/budget', Response.json({ ...aBudgetInUse(), total: 7_000_000, version: 4 }))
    await userEvent.click(screen.getByRole('button', { name: 'Load the latest version' }))

    await waitFor(() => expect(screen.getByLabelText(/^Budget for everything/)).toHaveValue('70000'))
    expect(screen.getByLabelText(/^Music/)).toHaveValue('')
    expect(server.requests('PUT /api/budget')).toHaveLength(1)
  })
})

describe('money on the home page', () => {
  it('shows the budget at a glance and the next payments, which can be marked paid there', async () => {
    const deposit = entry({ title: 'Pay the venue deposit', amount: 500_000, dueDate: '2026-11-20', priority: 'CRITICAL',
      priorityReason: 'PAYMENT_DUE_WITHIN_7_DAYS' })
    const server = planningServer([], {
      'GET /api/home': home({ next: [deposit], nextPayments: [deposit],
        budget: { total: 6_000_000, planned: 2_500_000, committed: 2_700_000, paid: 500_000 } }),
      [`PUT /api/entries/${deposit.id}/status`]: Response.json({ ...deposit, status: 'DONE', paidOn: '2026-11-17' }),
    })
    open('/')

    const glance = within(await screen.findByRole('region', { name: 'Budget' }))
    expect(glance.getByText(/still/).closest('p')).toHaveTextContent(
      '5,000.00 zł paid, 22,000.00 zł still to pay — 33,000.00 zł of the 60,000.00 zł budget not committed.')
    expect(glance.getByRole('img', { name: /^The whole budget: / })).toBeInTheDocument()
    expect(glance.getByRole('link', { name: 'The budget' })).toHaveAttribute('href', '/budget')

    // Under "Next" it stands in full; under the payments it says only what it costs and when.
    expect(within(screen.getByRole('region', { name: 'Next' })).getByText('Critical').parentElement).toHaveTextContent(
      'Critical — a payment due within 7 days')
    const payments = within(screen.getByRole('region', { name: 'Next payments' }))
    expect(payments.getByText('5,000.00 zł')).toBeInTheDocument()
    expect(payments.getByText('due 20 Nov 2026')).toBeInTheDocument()
    expect(payments.getByRole('link', { name: 'Pay the venue deposit' })).toHaveAttribute('href', `/entries/${deposit.id}`)
    await userEvent.click(payments.getByRole('checkbox', { name: 'Paid: Pay the venue deposit' }))

    await waitFor(() => expect(server.requests(`PUT /api/entries/${deposit.id}/status`)).toHaveLength(1))
    // The page reads everything again: the sums have changed with the payment.
    await waitFor(() => expect(server.requests('GET /api/home').length).toBeGreaterThan(1))
  })

  it('says when the payments go over the budget', async () => {
    planningServer([], {
      'GET /api/home': home({ budget: { total: 2_000_000, planned: 0, committed: 2_700_000, paid: 500_000 } }),
    })
    open('/')

    const glance = within(await screen.findByRole('region', { name: 'Budget' }))
    expect(glance.getByText(/still/).closest('p')).toHaveTextContent('7,000.00 zł more than the 20,000.00 zł budget.')
    // Nothing waits in the plan; the money is shown all the same.
    expect(screen.getByText(/No task is waiting for you\./)).toBeInTheDocument()
  })

  it('says nothing about money while there is none in the planner', async () => {
    planningServer([], { 'GET /api/home': home() })
    open('/')

    expect(await screen.findByText(/No task is waiting for you\./)).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Budget' })).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Next payments' })).not.toBeInTheDocument()
  })
})
