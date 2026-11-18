import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../App.tsx'
import { ANNA_MEMBER, countsOf, guest, PIOTR_MEMBER, planningServer, problem } from '../test-server.ts'

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

const FAMILY = { id: 'cccccccc-0000-4000-8000-000000000001', name: 'The Example family', guests: 2 }
const COUPLE = { id: 'cccccccc-0000-4000-8000-000000000002', name: 'Ala and Bolek', guests: 0 }

/** Made-up guests: a grandmother with company, an uncle, an aunt on the other side, and a friend of both. */
function someGuests() {
  return [
    guest({ name: 'Babcia Zosia', sideMemberId: ANNA_MEMBER, householdId: FAMILY.id, status: 'CONFIRMED', plusOne: true, children: 2,
      dietaryNeeds: 'No nuts', needsAccommodation: true }),
    guest({ name: 'Wujek Staszek', sideMemberId: ANNA_MEMBER, householdId: FAMILY.id, status: 'INVITED' }),
    guest({ name: 'Ciocia Hela', sideMemberId: PIOTR_MEMBER, status: 'INVITED', plusOne: true }),
    guest({ name: 'Ala from work', status: 'DECLINED' }),
  ]
}

function serverWith(guests: ReturnType<typeof someGuests>, extra = {}) {
  return planningServer([], {
    'GET /api/guests': Response.json(guests),
    'GET /api/guests/counts': Response.json(countsOf(guests)),
    'GET /api/households': Response.json([COUPLE, FAMILY]),
    ...extra,
  })
}

describe('the guest list', () => {
  it('lists the guests household by household, with whom they bring and what they need', async () => {
    const guests = someGuests()
    serverWith(guests)
    open('/guests')

    const family = within(await screen.findByRole('region', { name: 'The Example family' }))
    expect(family.getByRole('heading', { level: 2 })).toHaveTextContent('The Example family 5 people')
    expect(family.getAllByRole('link').map((link) => link.textContent)).toEqual(['Babcia Zosia', 'Wujek Staszek'])
    const zosia = within(family.getByRole('link', { name: 'Babcia Zosia' }).closest('li') as HTMLElement)
    expect(zosia.getByText('4 people')).toBeInTheDocument()
    expect(zosia.getByText('Anna’s side')).toBeInTheDocument()
    expect(zosia.getByText('with a plus-one')).toBeInTheDocument()
    expect(zosia.getByText('2 children')).toBeInTheDocument()
    expect(zosia.getByText('needs a room')).toBeInTheDocument()
    expect(zosia.getByText('No nuts')).toBeInTheDocument()
    expect(family.getByRole('link', { name: 'Babcia Zosia' })).toHaveAttribute('href', `/guests/${guests[0]?.id}`)

    const alone = within(screen.getByRole('region', { name: 'On their own' }))
    expect(alone.getAllByRole('link').map((link) => link.textContent)).toEqual(['Ciocia Hela', 'Ala from work'])
    expect(within(alone.getByRole('link', { name: 'Ala from work' }).closest('li') as HTMLElement).getByText('Both sides')).toBeInTheDocument()
    // A household with nobody in it is not a heading on the list.
    expect(screen.queryByRole('region', { name: 'Ala and Bolek' })).not.toBeInTheDocument()
  })

  it('counts the people status by status and side by side, plus-ones and children included', async () => {
    serverWith(someGuests())
    open('/guests')

    const count = within(await screen.findByRole('region', { name: 'Head count' }))
    expect(count.getByText(/confirmed/).closest('p')).toHaveTextContent('4 people confirmed — 1 guest, 1 plus-one, 2 children')
    const cells = (row: string) => within(count.getByRole('row', { name: new RegExp(`^${row}`) })).getAllByRole('cell').map((cell) => cell.textContent)
    expect(count.getAllByRole('columnheader').map((header) => header.textContent)).toEqual(['Anna', 'Piotr', 'Both', 'All'])
    expect(cells('Not invited yet')).toEqual(['0', '0', '0', '0'])
    expect(cells('Invited')).toEqual(['1', '2', '0', '3'])
    expect(cells('Confirmed')).toEqual(['4', '0', '0', '4'])
    expect(cells('Declined')).toEqual(['0', '0', '1', '1'])
    expect(cells('On the list')).toEqual(['5', '2', '1', '8'])
  })

  it('records an answer in one action from the list, and counts again', async () => {
    const guests = someGuests()
    const hela = guests[2]!
    const server = serverWith(guests, {
      [`PUT /api/guests/${hela.id}/status`]: Response.json({ ...hela, status: 'CONFIRMED', version: 1 }),
    })
    open('/guests')

    const before = { list: 0, counts: 0 }
    const status = await screen.findByRole('combobox', { name: 'Status of Ciocia Hela' })
    expect(status).toHaveValue('INVITED')
    before.list = server.requests('GET /api/guests').length
    before.counts = server.requests('GET /api/guests/counts').length
    await userEvent.selectOptions(status, 'Confirmed')

    await waitFor(() => expect(server.requests(`PUT /api/guests/${hela.id}/status`)).toHaveLength(1))
    await expect(server.requests(`PUT /api/guests/${hela.id}/status`)[0]?.json()).resolves.toEqual({ status: 'CONFIRMED' })
    await waitFor(() => expect(server.requests('GET /api/guests').length).toBeGreaterThan(before.list))
    await waitFor(() => expect(server.requests('GET /api/guests/counts').length).toBeGreaterThan(before.counts))
  })

  it('says so when an answer could not be recorded', async () => {
    const guests = someGuests()
    serverWith(guests, { [`PUT /api/guests/${guests[2]!.id}/status`]: problem(404, 'not-found', 'Not found') })
    open('/guests')

    await userEvent.selectOptions(await screen.findByRole('combobox', { name: 'Status of Ciocia Hela' }), 'Declined')

    expect(await screen.findByRole('alert')).toHaveTextContent('That change was not saved. Try again.')
  })

  it('narrows down by status and by side, and keeps both in the address', async () => {
    const server = serverWith(someGuests())
    open('/guests')

    await userEvent.selectOptions(await screen.findByLabelText('Status'), 'Invited')
    await userEvent.selectOptions(screen.getByLabelText('Side'), 'Piotr’s side')
    await waitFor(() => expect(query(server.requests('GET /api/guests').at(-1))).toEqual({ status: ['INVITED'], sideMemberId: [PIOTR_MEMBER] }))
    expect(window.location.search).toBe(`?status=INVITED&side=${PIOTR_MEMBER}`)

    await userEvent.selectOptions(screen.getByLabelText('Status'), 'Any')
    await userEvent.selectOptions(screen.getByLabelText('Side'), 'Both sides')
    await waitFor(() => expect(query(server.requests('GET /api/guests').at(-1))).toEqual({ bothSides: ['true'] }))
    expect(window.location.search).toBe('?side=both')
  })

  it('shows one plain list while there are no households, and says so when nobody matches or nobody is there', async () => {
    const guests = [guest({ name: 'Wujek Staszek' }), guest({ name: 'Ciocia Hela' })]
    const server = planningServer([], {
      'GET /api/guests': Response.json(guests),
      'GET /api/guests/counts': Response.json(countsOf(guests)),
      'GET /api/households': Response.json([]),
    })
    open('/guests')

    expect(await screen.findByRole('link', { name: 'Wujek Staszek' })).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'On their own' })).not.toBeInTheDocument()

    server.answer('GET /api/guests', Response.json([]))
    await userEvent.selectOptions(screen.getByLabelText('Status'), 'Declined')
    expect(await screen.findByText('Nobody matches. Choose another status or side.')).toBeInTheDocument()
  })

  it('is one of the pages in the top bar and starts empty, without a head count', async () => {
    planningServer([], {
      'GET /api/guests': Response.json([]),
      'GET /api/guests/counts': Response.json(countsOf([])),
      'GET /api/households': Response.json([]),
    })
    open('/')

    await userEvent.click(await screen.findByRole('link', { name: 'Guests' }))

    expect(await screen.findByText('Nobody on the list yet. Add the first guest.')).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Head count' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Add a guest' })).toHaveAttribute('href', '/guests/new')
  })
})

describe('the form of a guest', () => {
  it('adds a guest with everything about them, and returns to the list', async () => {
    const server = serverWith([], { 'POST /api/guests': Response.json(guest(), { status: 201 }) })
    open('/guests?status=INVITED')
    await userEvent.click(await screen.findByRole('link', { name: 'Add a guest' }))

    await userEvent.type(await screen.findByLabelText('Name'), ' Babcia Zosia ')
    // A new guest starts as the guest of whoever is typing.
    expect(screen.getByRole('radio', { name: 'Anna’s' })).toBeChecked()
    await userEvent.click(screen.getByRole('radio', { name: 'Piotr’s' }))
    await userEvent.selectOptions(screen.getByLabelText('Status'), 'Invited')
    await userEvent.selectOptions(screen.getByLabelText(/^Household/), 'The Example family')
    await userEvent.click(screen.getByRole('checkbox', { name: 'A plus-one' }))
    await userEvent.clear(screen.getByLabelText('Children'))
    await userEvent.type(screen.getByLabelText('Children'), '2')
    await userEvent.click(screen.getByRole('checkbox', { name: 'A place to stay' }))
    await userEvent.type(screen.getByLabelText(/^Dietary needs/), 'No nuts')
    await userEvent.type(screen.getByLabelText(/^Phone/), '600 000 001')
    await userEvent.type(screen.getByLabelText(/^E-mail/), 'zosia@example.org')
    await userEvent.type(screen.getByLabelText(/^Notes/), 'Arrives by train')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Guests' })).toBeInTheDocument()
    expect(window.location.search).toBe('?status=INVITED')
    await expect(server.requests('POST /api/guests')[0]?.json()).resolves.toEqual({
      name: 'Babcia Zosia', sideMemberId: PIOTR_MEMBER, householdId: FAMILY.id, status: 'INVITED', plusOne: true, children: 2,
      dietaryNeeds: 'No nuts', needsAccommodation: true, phone: '600 000 001', email: 'zosia@example.org', notes: 'Arrives by train',
    })
  })

  it('needs no more than a name', async () => {
    const server = serverWith([], { 'POST /api/guests': Response.json(guest(), { status: 201 }) })
    open('/guests/new')

    await userEvent.type(await screen.findByLabelText('Name'), 'Wujek Staszek')
    await userEvent.click(screen.getByRole('radio', { name: 'Both’s' }))
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(server.requests('POST /api/guests')).toHaveLength(1))
    await expect(server.requests('POST /api/guests')[0]?.json()).resolves.toEqual({
      name: 'Wujek Staszek', sideMemberId: null, householdId: null, status: 'NOT_INVITED', plusOne: false, children: 0,
      dietaryNeeds: '', needsAccommodation: false, phone: '', email: '', notes: '',
    })
  })

  it('changes a guest based on the version it loaded', async () => {
    const zosia = someGuests()[0]!
    const server = serverWith([zosia], {
      [`GET /api/guests/${zosia.id}`]: Response.json({ ...zosia, version: 3 }),
      [`PUT /api/guests/${zosia.id}`]: Response.json({ ...zosia, version: 4 }),
    })
    open(`/guests/${zosia.id}`)

    expect(await screen.findByLabelText('Name')).toHaveValue('Babcia Zosia')
    expect(screen.getByRole('radio', { name: 'Anna’s' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: 'A plus-one' })).toBeChecked()
    expect(screen.getByLabelText('Children')).toHaveValue(2)
    await userEvent.click(screen.getByRole('checkbox', { name: 'A plus-one' }))
    await userEvent.selectOptions(screen.getByLabelText(/^Household/), 'None')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Guests' })).toBeInTheDocument()
    await expect(server.requests(`PUT /api/guests/${zosia.id}`)[0]?.json()).resolves.toMatchObject({
      version: 3, name: 'Babcia Zosia', sideMemberId: ANNA_MEMBER, householdId: null, status: 'CONFIRMED', plusOne: false, children: 2,
      dietaryNeeds: 'No nuts', needsAccommodation: true,
    })
  })

  it('offers the latest version when an answer came in meanwhile, and overwrites nothing', async () => {
    const zosia = someGuests()[0]!
    const server = serverWith([zosia], {
      [`GET /api/guests/${zosia.id}`]: Response.json(zosia),
      [`PUT /api/guests/${zosia.id}`]: problem(409, 'changed-meanwhile', 'Changed meanwhile'),
    })
    open(`/guests/${zosia.id}`)

    await userEvent.type(await screen.findByLabelText(/^Notes/), 'Likes to dance')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByText('Someone else changed this in the meantime, so your change was not saved.')).toBeInTheDocument()

    server.answer(`GET /api/guests/${zosia.id}`, Response.json({ ...zosia, status: 'DECLINED', version: 1 }))
    await userEvent.click(screen.getByRole('button', { name: 'Load the latest version' }))

    await waitFor(() => expect(screen.getByLabelText('Status')).toHaveValue('DECLINED'))
    expect(server.requests(`PUT /api/guests/${zosia.id}`)).toHaveLength(1)
  })

  it('refuses a number of children that is none before sending, and shows the server’s word next to a field', async () => {
    const server = serverWith([], {
      'POST /api/guests': problem(400, 'bad-request', 'Bad request', {
        fieldProblems: [{ field: 'email', message: 'Enter an e-mail address like name@example.com, or leave it empty.' }],
      }),
    })
    open('/guests/new')

    await userEvent.type(await screen.findByLabelText('Name'), 'Wujek Staszek')
    await userEvent.clear(screen.getByLabelText('Children'))
    await userEvent.type(screen.getByLabelText('Children'), '25')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(screen.getByLabelText('Children')).toHaveAccessibleDescription('Enter a number from 0 to 20.')
    expect(server.calls).not.toContain('POST /api/guests')

    await userEvent.clear(screen.getByLabelText('Children'))
    await userEvent.type(screen.getByLabelText(/^E-mail/), 'staszek at example')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(screen.getByLabelText(/^E-mail/)).toBeInvalid())
    expect(screen.getByLabelText(/^E-mail/)).toHaveAccessibleDescription('Enter an e-mail address like name@example.com, or leave it empty.')
    // An empty number of children is none.
    await expect(server.requests('POST /api/guests')[0]?.json()).resolves.toMatchObject({ children: 0 })
  })

  it('takes a guest off the list only after asking', async () => {
    const staszek = someGuests()[1]!
    const server = serverWith([staszek], {
      [`GET /api/guests/${staszek.id}`]: Response.json(staszek),
      [`DELETE /api/guests/${staszek.id}`]: new Response(null, { status: 204 }),
    })
    open(`/guests/${staszek.id}`)

    await userEvent.click(await screen.findByRole('button', { name: 'Take this guest off the list' }))
    expect(screen.getByText('Take “Wujek Staszek” off the list? This cannot be undone.')).toBeInTheDocument()
    expect(server.calls).not.toContain(`DELETE /api/guests/${staszek.id}`)
    await userEvent.click(screen.getByRole('button', { name: 'Keep them' }))
    await userEvent.click(screen.getByRole('button', { name: 'Take this guest off the list' }))
    await userEvent.click(screen.getByRole('button', { name: 'Yes, take them off' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Guests' })).toBeInTheDocument()
    expect(server.calls).toContain(`DELETE /api/guests/${staszek.id}`)
  })
})

describe('the households', () => {
  it('adds a household, and says so when the name is taken', async () => {
    const server = serverWith([], {
      'POST /api/households': problem(400, 'bad-request', 'Bad request', {
        fieldProblems: [{ field: 'name', message: 'There is a household of that name already.' }],
      }),
    })
    open('/guests')
    await userEvent.click(await screen.findByRole('link', { name: 'Edit the households' }))

    await userEvent.type(await screen.findByLabelText('New household'), 'The Example family')
    await userEvent.click(screen.getByRole('button', { name: 'Add household' }))
    await waitFor(() => expect(screen.getByLabelText('New household')).toHaveAccessibleDescription('There is a household of that name already.'))

    const added = { id: 'cccccccc-0000-4000-8000-000000000003', name: 'The neighbours', guests: 0 }
    server.answer('POST /api/households', Response.json(added, { status: 201 }))
    server.answer('GET /api/households', Response.json([COUPLE, FAMILY, added]))
    await userEvent.clear(screen.getByLabelText('New household'))
    await userEvent.type(screen.getByLabelText('New household'), ' The neighbours ')
    await userEvent.click(screen.getByRole('button', { name: 'Add household' }))

    expect(await screen.findByText('The neighbours')).toBeInTheDocument()
    await expect(server.requests('POST /api/households')[1]?.json()).resolves.toEqual({ name: 'The neighbours' })
    expect(screen.getByRole('status')).toHaveTextContent('“The neighbours” was added.')
  })

  it('renames a household in its row, with the keyboard following along', async () => {
    const server = serverWith([], { [`PUT /api/households/${COUPLE.id}`]: Response.json({ ...COUPLE, name: 'Ala and Bolek Example' }) })
    open('/guests/households')

    await userEvent.click(await screen.findByRole('button', { name: 'Rename “Ala and Bolek”' }))
    const name = screen.getByLabelText('New name for “Ala and Bolek”')
    expect(name).toHaveFocus()
    server.answer('GET /api/households', Response.json([{ ...COUPLE, name: 'Ala and Bolek Example' }, FAMILY]))
    await userEvent.type(name, ' Example{Enter}')

    const renamed = await screen.findByRole('button', { name: 'Rename “Ala and Bolek Example”' })
    await expect(server.requests(`PUT /api/households/${COUPLE.id}`)[0]?.json()).resolves.toEqual({ name: 'Ala and Bolek Example' })
    expect(renamed).toHaveFocus()
  })

  it('removes a household after saying that its guests stay, and leads back to the list', async () => {
    const server = serverWith([], { [`DELETE /api/households/${FAMILY.id}`]: new Response(null, { status: 204 }) })
    open('/guests/households')

    expect(await screen.findByText('2 guests')).toBeInTheDocument()
    expect(screen.getByText('Nobody in it yet')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Remove “The Example family”' }))
    const question = screen.getByRole('group', { name: 'Remove “The Example family”' })
    expect(question).toHaveFocus()
    expect(question).toHaveTextContent('Remove “The Example family”? Its 2 guests stay on the list, on their own.')
    expect(server.calls).not.toContain(`DELETE /api/households/${FAMILY.id}`)

    server.answer('GET /api/households', Response.json([COUPLE]))
    await userEvent.click(within(question).getByRole('button', { name: 'Yes, remove it' }))

    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('“The Example family” was removed.'))
    expect(server.calls).toContain(`DELETE /api/households/${FAMILY.id}`)
    await userEvent.click(screen.getByRole('link', { name: 'Back to the guest list' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Guests' })).toBeInTheDocument()
  })
})
