import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../App.tsx'
import { entry, FLOWERS, inspiration, planningServer, problem, vendor, VENUE } from '../test-server.ts'

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

/** A made-up venue with everything filled in. */
function manor() {
  return vendor({
    name: 'Dwór pod Lipami',
    categoryId: VENUE.id,
    status: 'BOOKED',
    contactPerson: 'Maria Example',
    phone: '+48 600 000 000',
    email: 'maria@dwor.example',
    website: 'https://www.dwor.example/wesela',
    address: 'Lipowa 1\n00-001 Przykładowo',
    agreedPrice: 2_000_000,
    notes: 'Garden only until 22:00.',
    planItems: 1,
    tasks: 3,
    payments: 2,
    committed: 2_000_000,
    paid: 500_000,
    inspirations: 2,
    version: 4,
  })
}

describe('the vendors list', () => {
  it('lists the vendors with their status, category, contact and money', async () => {
    const venue = manor()
    const band = vendor({ name: 'zespół Echo', status: 'CONSIDERING' })
    const dj = vendor({ name: 'DJ Antek', status: 'DROPPED', committed: 30_000 })
    planningServer([], { 'GET /api/vendors': Response.json([dj, venue, band]) })
    open('/vendors')

    const row = (name: string) => within(screen.getByRole('link', { name }).closest('li') as HTMLElement)
    expect(await screen.findByRole('link', { name: 'Dwór pod Lipami' })).toHaveAttribute('href', `/vendors/${venue.id}`)
    expect(row('Dwór pod Lipami').getByText('Booked')).toBeInTheDocument()
    expect(row('Dwór pod Lipami').getByText('Venue')).toBeInTheDocument()
    expect(row('Dwór pod Lipami').getByText('Maria Example')).toBeInTheDocument()
    expect(row('Dwór pod Lipami').getByText('20,000.00 zł agreed')).toBeInTheDocument()
    expect(row('Dwór pod Lipami').getByText('5,000.00 zł paid')).toBeInTheDocument()
    expect(row('zespół Echo').getByText('Considering')).toBeInTheDocument()
    // Nothing agreed and nothing to pay: no money is shown at all.
    expect(row('zespół Echo').queryByText(/paid/)).not.toBeInTheDocument()
    expect(row('DJ Antek').getByText('Dropped')).toBeInTheDocument()
    expect(row('DJ Antek').getByText('0.00 zł paid')).toBeInTheDocument()
  })

  it('narrows down by status and category, and keeps both in the address', async () => {
    const server = planningServer([])
    open('/vendors')

    await userEvent.selectOptions(await screen.findByLabelText('Status'), 'Booked')
    await userEvent.selectOptions(screen.getByLabelText('Category'), 'Flowers and decor')

    await waitFor(() => expect(query(server.requests('GET /api/vendors').at(-1))).toEqual({ status: ['BOOKED'], categoryId: [FLOWERS.id] }))
    expect(window.location.search).toBe(`?status=BOOKED&category=${FLOWERS.id}`)
    expect(screen.getByText('No vendor matches. Choose another status or category.')).toBeInTheDocument()
  })

  it('is one of the pages in the top bar and says so when there is no vendor yet', async () => {
    planningServer([])
    open('/')

    await userEvent.click(await screen.findByRole('link', { name: 'Vendors' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Vendors' })).toBeInTheDocument()
    expect(screen.getByText('No vendors yet. Add the first one you are thinking about.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Add a vendor' })).toHaveAttribute('href', '/vendors/new')
  })
})

describe('a vendor’s page', () => {
  function serverFor(it: ReturnType<typeof manor>, entries = [] as ReturnType<typeof entry>[], extra = {}) {
    return planningServer(entries, {
      [`GET /api/vendors/${it.id}`]: Response.json(it),
      'GET /api/inspirations': Response.json([]),
      ...extra,
    })
  }

  it('shows how to reach the vendor, as links that call, write and open', async () => {
    const it = manor()
    serverFor(it)
    open(`/vendors/${it.id}`)

    expect(await screen.findByRole('heading', { level: 1, name: 'Dwór pod Lipami' })).toBeInTheDocument()
    expect(screen.getByText('Booked')).toBeInTheDocument()
    expect(screen.getByText('Maria Example')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '+48 600 000 000' })).toHaveAttribute('href', 'tel:+48600000000')
    expect(screen.getByRole('link', { name: 'maria@dwor.example' })).toHaveAttribute('href', 'mailto:maria@dwor.example')
    const site = screen.getByRole('link', { name: /^dwor\.example/ })
    expect(site).toHaveAttribute('href', 'https://www.dwor.example/wesela')
    expect(site).toHaveAttribute('target', '_blank')
    expect(site).toHaveAttribute('rel', 'noopener noreferrer')
    expect(screen.getByText(/Lipowa 1/)).toBeInTheDocument()
    expect(screen.getByText('Garden only until 22:00.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Change' })).toHaveAttribute('href', `/vendors/${it.id}/edit`)
  })

  it('sets what is paid against the agreed price, in words and as a bar', async () => {
    const it = manor()
    serverFor(it)
    open(`/vendors/${it.id}`)

    const money = within(await screen.findByRole('region', { name: 'Money' }))
    expect(money.getByText(/agreed\./).closest('p')).toHaveTextContent('20,000.00 zł agreed. 5,000.00 zł paid, 15,000.00 zł still to pay.')
    expect(money.getByRole('img', { name: /^Dwór pod Lipami: 5,000\.00.zł paid, 15,000\.00.zł still to pay, of 20,000\.00.zł agreed$/ })).toBeInTheDocument()
    // The payments add up to the price exactly: there is nothing more to say.
    expect(money.queryByText(/has no payment yet|more than was agreed/)).not.toBeInTheDocument()
  })

  it('says how much of the agreed price has no payment yet', async () => {
    const short = { ...manor(), committed: 1_200_000, paid: 0 }
    serverFor(short)
    open(`/vendors/${short.id}`)

    expect(await screen.findByText('8,000.00 zł of the agreed price has no payment yet.')).toBeInTheDocument()
  })

  it('says so when the payments come to more than was agreed', async () => {
    const over = { ...manor(), committed: 2_150_000, paid: 2_150_000 }
    serverFor(over)
    open(`/vendors/${over.id}`)

    expect(await screen.findByText(/The payments come to 1,500\.00 zł more than was agreed\./)).toBeInTheDocument()
  })

  it('lists its plan items, tasks, payments and inspirations, and a payment can be marked paid there', async () => {
    const it = manor()
    const book = entry({ kind: 'PLAN_ITEM', title: 'Book the venue', vendorId: it.id })
    const deposit = entry({ title: 'Pay the deposit', vendorId: it.id, amount: 500_000, status: 'DONE', completedAt: '2026-11-10T09:00:00Z', paidOn: '2026-11-10' })
    const rest = entry({ title: 'Pay the rest', vendorId: it.id, amount: 1_500_000 })
    const idea = inspiration({ title: 'The garden at dusk', note: 'For the first dance', vendorId: it.id })
    const server = serverFor(it, [book, deposit, rest], {
      'GET /api/inspirations': Response.json([idea]),
      [`PUT /api/entries/${rest.id}/status`]: Response.json({ ...rest, status: 'DONE', paidOn: '2026-11-17' }),
    })
    open(`/vendors/${it.id}`)

    const planItems = within(await screen.findByRole('region', { name: 'Plan items' }))
    expect(planItems.getByRole('link', { name: 'Book the venue' })).toHaveAttribute('href', `/entries/${book.id}`)
    const tasks = within(screen.getByRole('region', { name: 'Tasks and payments' }))
    expect(tasks.getByText('5,000.00 zł, paid 10 Nov 2026')).toBeInTheDocument()
    expect(tasks.getByText('15,000.00 zł, to pay')).toBeInTheDocument()
    const ideas = within(screen.getByRole('region', { name: 'Inspirations' }))
    expect(ideas.getByRole('link', { name: 'The garden at dusk' })).toHaveAttribute('href', `/inspirations/${idea.id}`)
    expect(ideas.getByText('For the first dance')).toBeInTheDocument()
    // Both lists were asked for this vendor only.
    expect(server.requests('GET /api/entries').map(query)).toContainEqual({ vendorId: [it.id], sort: ['DUE_DATE'] })
    expect(query(server.requests('GET /api/inspirations')[0])).toEqual({ vendorId: [it.id] })

    const before = server.requests(`GET /api/vendors/${it.id}`).length
    await userEvent.click(tasks.getByRole('checkbox', { name: 'Paid: Pay the rest' }))

    await waitFor(() => expect(server.requests(`PUT /api/entries/${rest.id}/status`)).toHaveLength(1))
    // What is paid has changed: the vendor's sums are read again.
    await waitFor(() => expect(server.requests(`GET /api/vendors/${it.id}`).length).toBeGreaterThan(before))
  })

  it('adds a task for this vendor with the vendor already chosen, and returns here', async () => {
    const it = manor()
    const server = serverFor(it, [], {
      'GET /api/vendors': Response.json([it]),
      'POST /api/entries': Response.json(entry(), { status: 201 }),
    })
    open(`/vendors/${it.id}`)

    await userEvent.click(await screen.findByRole('link', { name: 'Add a task or payment for this vendor' }))
    expect(await screen.findByLabelText(/^Vendor/)).toHaveValue(it.id)
    await userEvent.type(screen.getByLabelText('Title'), 'Ask about parking')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Dwór pod Lipami' })).toBeInTheDocument()
    await expect(server.requests('POST /api/entries')[0]?.json()).resolves.toMatchObject({ kind: 'TASK', title: 'Ask about parking', vendorId: it.id })
  })

  it('adds an inspiration for this vendor with the vendor already chosen', async () => {
    const it = manor()
    const server = serverFor(it, [], {
      'GET /api/vendors': Response.json([it]),
      'GET /api/boards': Response.json([]),
      'POST /api/inspirations': Response.json(inspiration(), { status: 201 }),
    })
    open(`/vendors/${it.id}`)

    await userEvent.click(await screen.findByRole('link', { name: 'Add an inspiration for this vendor' }))
    expect(await screen.findByLabelText(/^Vendor/)).toHaveValue(it.id)
    await userEvent.type(screen.getByLabelText(/^Link/), 'https://dwor.example/galeria')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Dwór pod Lipami' })).toBeInTheDocument()
    await expect(server.requests('POST /api/inspirations')[0]?.json()).resolves.toMatchObject({ url: 'https://dwor.example/galeria', vendorId: it.id })
  })

  it('says little about a vendor little is known about', async () => {
    const bare = vendor({ name: 'A florist from the market' })
    planningServer([], { [`GET /api/vendors/${bare.id}`]: Response.json(bare), 'GET /api/inspirations': Response.json([]) })
    open(`/vendors/${bare.id}`)

    expect(await screen.findByText('No contact details yet.')).toBeInTheDocument()
    expect(screen.getByText('Nothing agreed and no payments yet.')).toBeInTheDocument()
    expect(screen.getAllByText('None yet.')).toHaveLength(3)
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
  })

  it('says so when the vendor is not there any more', async () => {
    planningServer([], { 'GET /api/inspirations': Response.json([]) })
    open('/vendors/aaaaaaaa-0000-4000-8000-00000000dead')

    expect(await screen.findByText(/This vendor could not be loaded\. It may have been deleted\./)).toBeInTheDocument()
  })
})

describe('the form of a vendor', () => {
  it('adds a vendor, the price as whole grosze, and shows its page', async () => {
    const saved = vendor({ name: 'Bistro Catering' })
    const server = planningServer([], {
      'POST /api/vendors': Response.json(saved, { status: 201 }),
      [`GET /api/vendors/${saved.id}`]: Response.json(saved),
      'GET /api/inspirations': Response.json([]),
    })
    open('/vendors/new')

    await userEvent.type(await screen.findByLabelText('Name'), ' Bistro Catering ')
    await userEvent.selectOptions(screen.getByLabelText('Status'), 'Booked')
    await userEvent.selectOptions(screen.getByLabelText(/^Category/), 'Venue')
    await userEvent.type(screen.getByLabelText(/^Contact person/), 'Jan Example')
    await userEvent.type(screen.getByLabelText(/^Phone/), '600 000 000')
    await userEvent.type(screen.getByLabelText(/^E-mail/), 'jan@bistro.example')
    await userEvent.type(screen.getByLabelText(/^Website/), 'bistro.example')
    await userEvent.type(screen.getByLabelText(/^Address/), 'Rynek 2')
    await userEvent.type(screen.getByLabelText(/^Agreed price in PLN/), '9 000,50')
    await userEvent.type(screen.getByLabelText(/^Notes/), 'Tasting in March')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Bistro Catering' })).toBeInTheDocument()
    expect(window.location.pathname).toBe(`/vendors/${saved.id}`)
    await expect(server.requests('POST /api/vendors')[0]?.json()).resolves.toEqual({
      name: 'Bistro Catering', categoryId: VENUE.id, status: 'BOOKED', contactPerson: 'Jan Example', phone: '600 000 000',
      email: 'jan@bistro.example', website: 'bistro.example', address: 'Rynek 2', agreedPrice: 900_050, notes: 'Tasting in March',
    })
  })

  it('needs no more than a name: everything else is sent as empty', async () => {
    const saved = vendor({ name: 'A florist' })
    const server = planningServer([], {
      'POST /api/vendors': Response.json(saved, { status: 201 }),
      [`GET /api/vendors/${saved.id}`]: Response.json(saved),
      'GET /api/inspirations': Response.json([]),
    })
    open('/vendors/new')

    await userEvent.type(await screen.findByLabelText('Name'), 'A florist')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(server.requests('POST /api/vendors')).toHaveLength(1))
    await expect(server.requests('POST /api/vendors')[0]?.json()).resolves.toEqual({
      name: 'A florist', categoryId: null, status: 'CONSIDERING', contactPerson: '', phone: '', email: '', website: '', address: '',
      agreedPrice: null, notes: '',
    })
  })

  it('changes a vendor based on the version it loaded, and returns to its page', async () => {
    const it = manor()
    const server = planningServer([], {
      [`GET /api/vendors/${it.id}`]: Response.json(it),
      [`PUT /api/vendors/${it.id}`]: Response.json({ ...it, status: 'DROPPED', version: 5 }),
      'GET /api/inspirations': Response.json([]),
    })
    open(`/vendors/${it.id}`)
    await userEvent.click(await screen.findByRole('link', { name: 'Change' }))

    expect(await screen.findByLabelText('Name')).toHaveValue('Dwór pod Lipami')
    expect(screen.getByLabelText(/^Agreed price in PLN/)).toHaveValue('20000')
    await userEvent.selectOptions(screen.getByLabelText('Status'), 'Dropped')
    await userEvent.clear(screen.getByLabelText(/^Agreed price in PLN/))
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Dwór pod Lipami' })).toBeInTheDocument()
    await expect(server.requests(`PUT /api/vendors/${it.id}`)[0]?.json()).resolves.toMatchObject({
      version: 4, name: 'Dwór pod Lipami', status: 'DROPPED', agreedPrice: null, phone: '+48 600 000 000',
    })
  })

  it('shows what the server says next to the field, and what is not an amount before anything is sent', async () => {
    const server = planningServer([], {
      'POST /api/vendors': problem(400, 'bad-request', 'Bad request', {
        fieldProblems: [
          { field: 'email', message: 'Enter an e-mail address like name@example.com, or leave it empty.' },
          { field: 'website', message: 'Enter a web address like example.com, or leave it empty.' },
        ],
      }),
    })
    open('/vendors/new')

    await userEvent.type(await screen.findByLabelText('Name'), 'A florist')
    await userEvent.type(screen.getByLabelText(/^Agreed price in PLN/), 'cheap')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(screen.getByLabelText(/^Agreed price in PLN/)).toHaveAccessibleDescription(/Enter an amount like 2500 or 2500\.50, or leave it empty\./)
    expect(server.calls).not.toContain('POST /api/vendors')

    await userEvent.clear(screen.getByLabelText(/^Agreed price in PLN/))
    await userEvent.type(screen.getByLabelText(/^E-mail/), 'jan at bistro')
    await userEvent.type(screen.getByLabelText(/^Website/), 'not a site')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(screen.getByLabelText(/^E-mail/)).toBeInvalid())
    expect(screen.getByLabelText(/^E-mail/)).toHaveAccessibleDescription('Enter an e-mail address like name@example.com, or leave it empty.')
    expect(screen.getByLabelText(/^Website/)).toHaveAccessibleDescription('Enter a web address like example.com, or leave it empty.')
    expect(screen.getByLabelText('Name')).toHaveValue('A florist')
  })

  it('offers the latest version when someone else changed the vendor meanwhile', async () => {
    const it = manor()
    const server = planningServer([], {
      [`GET /api/vendors/${it.id}`]: Response.json(it),
      [`PUT /api/vendors/${it.id}`]: problem(409, 'changed-meanwhile', 'Changed meanwhile'),
    })
    open(`/vendors/${it.id}/edit`)

    await userEvent.type(await screen.findByLabelText(/^Notes/), ' Ask again.')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByText('Someone else changed this in the meantime, so your change was not saved.')).toBeInTheDocument()

    server.answer(`GET /api/vendors/${it.id}`, Response.json({ ...it, notes: 'Piotr called them.', version: 5 }))
    await userEvent.click(screen.getByRole('button', { name: 'Load the latest version' }))

    await waitFor(() => expect(screen.getByLabelText(/^Notes/)).toHaveValue('Piotr called them.'))
    expect(server.requests(`PUT /api/vendors/${it.id}`)).toHaveLength(1)
  })

  it('deletes a vendor only after saying what happens to what points to it', async () => {
    const it = manor()
    const server = planningServer([], {
      [`GET /api/vendors/${it.id}`]: Response.json(it),
      [`DELETE /api/vendors/${it.id}`]: new Response(null, { status: 204 }),
    })
    open(`/vendors/${it.id}/edit`)

    await userEvent.click(await screen.findByRole('button', { name: 'Delete this vendor' }))
    expect(screen.getByRole('group', { name: 'Delete' })).toHaveTextContent(
      'Delete “Dwór pod Lipami”? 1 plan item, 3 tasks and 2 inspirations point to it and stay, without a vendor. This cannot be undone.')
    expect(server.calls).not.toContain(`DELETE /api/vendors/${it.id}`)
    await userEvent.click(screen.getByRole('button', { name: 'Yes, delete it' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Vendors' })).toBeInTheDocument()
    expect(query(server.requests(`DELETE /api/vendors/${it.id}`)[0])).toEqual({ links: ['REMOVE'] })
  })

  it('says so when nothing points to the vendor, or just one thing does', async () => {
    const alone = vendor({ name: 'A florist' })
    const one = vendor({ name: 'A baker', tasks: 1 })
    planningServer([], { [`GET /api/vendors/${alone.id}`]: Response.json(alone), [`GET /api/vendors/${one.id}`]: Response.json(one) })
    open(`/vendors/${alone.id}/edit`)
    await userEvent.click(await screen.findByRole('button', { name: 'Delete this vendor' }))
    expect(screen.getByRole('group', { name: 'Delete' })).toHaveTextContent('Delete “A florist”? Nothing points to it. This cannot be undone.')
    await userEvent.click(screen.getByRole('button', { name: 'Do not delete' }))
    expect(screen.getByRole('button', { name: 'Delete this vendor' })).toBeInTheDocument()
  })
})

describe('vendors on plan items, tasks and inspirations', () => {
  it('names the vendor in the task list and in the plan', async () => {
    const it = manor()
    const book = entry({ kind: 'PLAN_ITEM', title: 'Book the venue', vendorId: it.id })
    const call = entry({ title: 'Ask about parking', vendorId: it.id })
    const other = entry({ title: 'Buy a guest book' })
    planningServer([book, call, other], { 'GET /api/vendors': Response.json([it]) })
    open('/tasks')

    const row = (title: string) => within(screen.getByRole('link', { name: title }).closest('li') as HTMLElement)
    expect(await screen.findByRole('link', { name: 'Ask about parking' })).toBeInTheDocument()
    expect(row('Ask about parking').getByText('Dwór pod Lipami')).toBeInTheDocument()
    expect(row('Buy a guest book').queryByText('Dwór pod Lipami')).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('link', { name: 'Plan' }))
    expect(await screen.findByRole('link', { name: 'Book the venue' })).toBeInTheDocument()
    expect(row('Book the venue').getByText('Dwór pod Lipami')).toBeInTheDocument()
  })

  it('lets a task choose its vendor, saying who is only being considered or was dropped', async () => {
    const it = manor()
    const band = vendor({ name: 'zespół Echo', status: 'CONSIDERING' })
    const dj = vendor({ name: 'DJ Antek', status: 'DROPPED' })
    const call = entry({ title: 'Ask about parking' })
    const path = `/api/entries/${call.id}`
    const server = planningServer([call], {
      'GET /api/vendors': Response.json([dj, it, band]),
      [`GET ${path}`]: Response.json(call),
      [`PUT ${path}`]: Response.json({ ...call, vendorId: it.id, version: 1 }),
    })
    open(`/entries/${call.id}`)

    const choice = await screen.findByLabelText(/^Vendor/)
    expect(within(choice).getAllByRole('option').map((option) => option.textContent)).toEqual(
      ['No vendor', 'DJ Antek (dropped)', 'Dwór pod Lipami', 'zespół Echo (considering)'])
    await userEvent.selectOptions(choice, 'Dwór pod Lipami')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(server.requests(`PUT ${path}`)).toHaveLength(1))
    await expect(server.requests(`PUT ${path}`)[0]?.json()).resolves.toMatchObject({ vendorId: it.id })
  })

  it('takes the vendor away from an entry again', async () => {
    const it = manor()
    const call = entry({ title: 'Ask about parking', vendorId: it.id })
    const path = `/api/entries/${call.id}`
    const server = planningServer([call], {
      'GET /api/vendors': Response.json([it]),
      [`GET ${path}`]: Response.json(call),
      [`PUT ${path}`]: Response.json({ ...call, vendorId: null, version: 1 }),
    })
    open(`/entries/${call.id}`)

    expect(await screen.findByLabelText(/^Vendor/)).toHaveValue(it.id)
    await userEvent.selectOptions(screen.getByLabelText(/^Vendor/), 'No vendor')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(server.requests(`PUT ${path}`)).toHaveLength(1))
    await expect(server.requests(`PUT ${path}`)[0]?.json()).resolves.toMatchObject({ vendorId: null })
  })
})
