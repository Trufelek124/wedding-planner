import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../App.tsx'
import { ANNA_MEMBER, FLOWERS, PIOTR_MEMBER, home, planningServer, problem, suggestion, vendor, VENUE, type Handler } from '../test-server.ts'
import type { Schemas } from '../api/client.ts'

type Suggestion = Schemas['Suggestion']

function open(path: string) {
  window.history.pushState(null, '', path)
  render(<App />)
}

const DALE = vendor({ name: 'Palace on the Hill' })

/** A server that answers the suggestions the way the real one does: by the state that was asked for. */
function inbox(all: Suggestion[], extra: Record<string, Handler> = {}) {
  return {
    dale: DALE,
    server: planningServer([], {
      'GET /api/vendors': Response.json([DALE]),
      'GET /api/suggestions': (request) => {
        const state = new URL(request.url).searchParams.get('state') ?? 'WAITING'
        return Response.json(all.filter((one) => state === 'ALL' || one.state === state))
      },
      ...extra,
    }),
  }
}

const card = (title: string) => within(screen.getByRole('article', { name: title }))

describe('the waiting suggestions', () => {
  it('shows each kind with every fact the import found', async () => {
    const dale = DALE
    const task = suggestion({
      title: 'Send the signed form',
      dueDate: '2026-11-30',
      categoryId: VENUE.id,
      vendorId: dale.id,
      notes: 'Both signatures.',
    })
    const payment = suggestion({
      kind: 'PAYMENT',
      title: 'Pay the deposit',
      dueDate: '2026-12-05',
      amount: 250_050,
      paymentKind: 'DEPOSIT',
      categoryId: FLOWERS.id,
      vendorName: 'Kwiaty Example',
      confidence: 88,
    })
    const step = suggestion({ kind: 'PLAN_ITEM', title: 'Tasting', startDate: '2027-01-10', dueDate: '2027-01-12', inboxMemberId: PIOTR_MEMBER })
    const newVendor = suggestion({
      kind: 'VENDOR',
      title: 'DJ Antek',
      vendorPhone: '+48 600 000 000',
      vendorEmail: 'antek@dj.example',
      vendorWebsite: 'https://dj.example',
    })
    inbox([task, payment, step, newVendor])
    open('/suggestions')

    await screen.findByRole('article', { name: 'Send the signed form' })
    const t = card('Send the signed form')
    expect(t.getByText('Task')).toBeInTheDocument()
    expect(t.getByText('Due').nextSibling).toHaveTextContent('30 Nov 2026')
    expect(t.getByText('Category').nextSibling).toHaveTextContent('Venue')
    expect(t.getByText('Vendor').nextSibling).toHaveTextContent('Palace on the Hill')
    expect(t.getByText('Both signatures.')).toBeInTheDocument()
    expect(t.getByText('From').nextSibling).toHaveTextContent('Jan from Palace on the Hill (jan@palace.example)')
    expect(t.getByText('Subject').nextSibling).toHaveTextContent('Your booking')
    expect(t.getByText('Arrived').nextSibling).toHaveTextContent('12 Nov 2026, 14:05, in your inbox')
    expect(t.getByText('Please send the signed form by the end of the month.').tagName).toBe('BLOCKQUOTE')
    expect(t.getByText('62% sure')).toBeInTheDocument()

    const p = card('Pay the deposit')
    expect(p.getByText('Payment')).toBeInTheDocument()
    expect(p.getByText('Amount').nextSibling).toHaveTextContent('2,500.50 zł, deposit')
    expect(p.getByText('Category').nextSibling).toHaveTextContent('Flowers and decor')
    expect(p.getByText('Vendor').nextSibling).toHaveTextContent('Kwiaty Example (not in the planner yet)')
    expect(p.getByText('88% sure')).toBeInTheDocument()

    const s = card('Tasting')
    expect(s.getByText('Step of the plan')).toBeInTheDocument()
    expect(s.getByText('Starts').nextSibling).toHaveTextContent('10 Jan 2027')
    expect(s.getByText('Ends').nextSibling).toHaveTextContent('12 Jan 2027')
    expect(s.getByText('Arrived').nextSibling).toHaveTextContent('in Piotr’s inbox')

    const v = card('DJ Antek')
    expect(v.getByText('Vendor', { selector: 'p' })).toBeInTheDocument()
    expect(v.getByText('Phone').nextSibling).toHaveTextContent('+48 600 000 000')
    expect(v.getByText('E-mail').nextSibling).toHaveTextContent('antek@dj.example')
    expect(v.getByText('Website').nextSibling).toHaveTextContent('https://dj.example')
    // What came out of a mail is not turned into a way to call, write or open.
    expect(v.queryByRole('link', { name: /antek|dj\.example|600/ })).not.toBeInTheDocument()

    for (const title of ['Send the signed form', 'Pay the deposit', 'Tasting', 'DJ Antek']) {
      expect(card(title).getByRole('button', { name: 'Accept' })).toBeEnabled()
      expect(card(title).getByRole('button', { name: 'Reject' })).toBeEnabled()
      expect(card(title).getByRole('link', { name: 'Change and accept' })).toBeInTheDocument()
    }
  })

  it('says plainly that nothing waits, with no button that leads nowhere', async () => {
    inbox([])
    open('/suggestions')

    expect(await screen.findByText('Nothing waits for you.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /accept|reject/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /change and accept/i })).not.toBeInTheDocument()
  })

  it('draws the link to the e-mail only for an https address, opening in a new tab', async () => {
    inbox([
      suggestion({ title: 'Safe', mailLink: 'https://mail.google.com/mail/u/0/#all/x1' }),
      suggestion({ title: 'Plain http', mailLink: 'http://mail.google.com/mail/u/0/#all/x2' }),
      suggestion({ title: 'Script', mailLink: 'javascript:alert(1)' }),
      suggestion({ title: 'Nothing', mailLink: '' }),
    ])
    open('/suggestions')

    await screen.findByRole('article', { name: 'Safe' })
    const link = card('Safe').getByRole('link', { name: 'Open the e-mail' })
    expect(link).toHaveAttribute('href', 'https://mail.google.com/mail/u/0/#all/x1')
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
    for (const title of ['Plain http', 'Script', 'Nothing']) {
      expect(card(title).queryByRole('link', { name: 'Open the e-mail' })).not.toBeInTheDocument()
    }
    expect(document.querySelectorAll('a[href^="javascript"], a[href^="http:"]')).toHaveLength(0)
  })

  it('shows what a mail wrote letter for letter, never as markup or a link', async () => {
    inbox([
      suggestion({
        title: '<b>Pay now</b> <a href="https://evil.example">here</a>',
        passage: 'Click <img src=x onerror="alert(1)"> or visit https://evil.example/pay',
        senderName: '<i>Jan</i>',
        subject: '<script>alert(1)</script>',
        notes: '<u>urgent</u>',
        vendorName: '<b>Shady</b>',
      }),
    ])
    open('/suggestions')

    expect(await screen.findByRole('heading', { level: 2, name: '<b>Pay now</b> <a href="https://evil.example">here</a>' })).toBeInTheDocument()
    expect(screen.getByText('Click <img src=x onerror="alert(1)"> or visit https://evil.example/pay')).toBeInTheDocument()
    expect(screen.getByText(/<i>Jan<\/i>/)).toBeInTheDocument()
    expect(screen.getByText('<script>alert(1)</script>')).toBeInTheDocument()
    expect(screen.getByText('<u>urgent</u>')).toBeInTheDocument()
    expect(screen.getByText('<b>Shady</b> (not in the planner yet)')).toBeInTheDocument()
    const page = document.querySelector('main') as HTMLElement
    expect(page.querySelector('b, i, u, img, script')).toBeNull()
    expect(within(page).queryByRole('link', { name: /evil|here|pay/i })).not.toBeInTheDocument()
  })
})

describe('where a suggestion stands, in the address', () => {
  it('shows the waiting ones by default and asks for the others when they are chosen', async () => {
    const waiting = suggestion({ title: 'Still open' })
    const accepted = suggestion({
      title: 'Taken',
      state: 'ACCEPTED',
      decidedBy: PIOTR_MEMBER,
      decidedAt: '2026-11-15T09:30:00Z',
      madeEntryId: 'eeeeeeee-eeee-4eee-8eee-000000000001',
    })
    const rejected = suggestion({ title: 'Turned down', state: 'REJECTED', decidedBy: ANNA_MEMBER, decidedAt: '2026-11-16T18:00:00Z' })
    const { server } = inbox([waiting, accepted, rejected])
    open('/suggestions')

    expect(await screen.findByRole('article', { name: 'Still open' })).toBeInTheDocument()
    expect(screen.queryByRole('article', { name: 'Taken' })).not.toBeInTheDocument()
    expect(window.location.search).toBe('')

    await userEvent.selectOptions(screen.getByLabelText('Show'), 'Accepted')
    expect(await screen.findByRole('article', { name: 'Taken' })).toBeInTheDocument()
    expect(window.location.search).toBe('?state=ACCEPTED')
    expect(screen.queryByRole('article', { name: 'Still open' })).not.toBeInTheDocument()
    expect(server.requests('GET /api/suggestions').map((one) => new URL(one.url).searchParams.get('state'))).toContain('ACCEPTED')

    await userEvent.selectOptions(screen.getByLabelText('Show'), 'Rejected')
    expect(await screen.findByRole('article', { name: 'Turned down' })).toBeInTheDocument()
    expect(window.location.search).toBe('?state=REJECTED')

    await userEvent.selectOptions(screen.getByLabelText('Show'), 'Waiting')
    expect(await screen.findByRole('article', { name: 'Still open' })).toBeInTheDocument()
    expect(window.location.search).toBe('')
  })

  it('opens on the state the address names', async () => {
    inbox([suggestion({ title: 'Turned down', state: 'REJECTED', decidedBy: ANNA_MEMBER, decidedAt: '2026-11-16T18:00:00Z' })])
    open('/suggestions?state=REJECTED')

    expect(await screen.findByRole('article', { name: 'Turned down' })).toBeInTheDocument()
    expect(screen.getByLabelText('Show')).toHaveValue('REJECTED')
  })

  it('says who decided and when, and leads to what accepting made; a decided card has no buttons', async () => {
    const task = suggestion({
      title: 'Taken task',
      state: 'ACCEPTED',
      decidedBy: PIOTR_MEMBER,
      decidedAt: '2026-11-15T09:30:00Z',
      madeEntryId: 'eeeeeeee-eeee-4eee-8eee-000000000001',
    })
    const made = suggestion({
      title: 'Taken vendor',
      kind: 'VENDOR',
      state: 'ACCEPTED',
      decidedBy: ANNA_MEMBER,
      decidedAt: '2026-11-15T10:00:00Z',
      madeVendorId: 'aaaaaaaa-0000-4000-8000-000000000042',
    })
    inbox([task, made])
    open('/suggestions?state=ACCEPTED')

    await screen.findByRole('article', { name: 'Taken task' })
    expect(card('Taken task').getByText('Accepted by Piotr, 15 Nov 2026, 10:30')).toBeInTheDocument()
    expect(card('Taken task').getByRole('link', { name: 'Open the task' })).toHaveAttribute('href', '/entries/eeeeeeee-eeee-4eee-8eee-000000000001')
    expect(card('Taken vendor').getByText('Accepted by you, 15 Nov 2026, 11:00')).toBeInTheDocument()
    expect(card('Taken vendor').getByRole('link', { name: 'Open the vendor' })).toHaveAttribute('href', '/vendors/aaaaaaaa-0000-4000-8000-000000000042')
    expect(screen.queryByRole('button', { name: /accept|reject/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Change and accept' })).not.toBeInTheDocument()
  })

  it('says a rejected one was rejected, by whom, and made nothing', async () => {
    inbox([suggestion({ title: 'Turned down', state: 'REJECTED', decidedBy: PIOTR_MEMBER, decidedAt: '2026-11-16T18:00:00Z' })])
    open('/suggestions?state=REJECTED')

    await screen.findByRole('article', { name: 'Turned down' })
    expect(card('Turned down').getByText('Rejected by Piotr, 16 Nov 2026, 19:00')).toBeInTheDocument()
    expect(card('Turned down').queryByRole('link', { name: /^Open the (task|payment|step|vendor)/ })).not.toBeInTheDocument()
  })
})

describe('deciding', () => {
  function deciding(all: Suggestion[]) {
    const decide = (state: 'ACCEPTED' | 'REJECTED') => (request: Request) => {
      const id = new URL(request.url).pathname.split('/')[3]
      const found = all.find((one) => one.id === id)
      if (found === undefined) return problem(404, 'not-found')
      Object.assign(found, { state, decidedBy: ANNA_MEMBER, decidedAt: '2026-11-19T12:00:00Z' })
      if (state === 'ACCEPTED') {
        found.madeEntryId = found.kind === 'VENDOR' ? null : 'eeeeeeee-eeee-4eee-8eee-0000000000aa'
        found.madeVendorId = found.kind === 'VENDOR' ? 'aaaaaaaa-0000-4000-8000-0000000000bb' : null
      }
      return Response.json(found)
    }
    const routes: Record<string, Handler> = {}
    for (const one of all) {
      routes[`POST /api/suggestions/${one.id}/accept`] = decide('ACCEPTED')
      routes[`POST /api/suggestions/${one.id}/reject`] = decide('REJECTED')
    }
    return inbox(all, routes)
  }

  it('accepts as proposed with a request that has no body, then says what was added and leads to it', async () => {
    const first = suggestion({ title: 'Send the signed form', dueDate: '2026-11-30' })
    const second = suggestion({ title: 'Book the florist' })
    const { server } = deciding([first, second])
    open('/suggestions')
    await screen.findByRole('article', { name: 'Send the signed form' })

    await userEvent.click(card('Send the signed form').getByRole('button', { name: 'Accept' }))

    const request = server.requests(`POST /api/suggestions/${first.id}/accept`)[0]
    expect(request).toBeDefined()
    expect(await (request as Request).text()).toBe('')
    const said = await screen.findByRole('group', { name: 'What happened' })
    const link = within(said).getByRole('link', { name: 'Send the signed form' })
    expect(said).toHaveTextContent('Added to the tasks: Send the signed form')
    expect(link).toHaveAttribute('href', '/entries/eeeeeeee-eeee-4eee-8eee-0000000000aa')
    await waitFor(() => expect(screen.queryByRole('article', { name: 'Send the signed form' })).not.toBeInTheDocument())
    expect(screen.getByRole('article', { name: 'Book the florist' })).toBeInTheDocument()
    // The keyboard is not lost with the card: it is on what was said.
    expect(said).toHaveFocus()
    // Nothing was asked a second time.
    expect(server.calls.filter((call) => call.startsWith('POST /api/suggestions'))).toEqual([`POST /api/suggestions/${first.id}/accept`])
  })

  it('says a new vendor was added to the vendors', async () => {
    const one = suggestion({ kind: 'VENDOR', title: 'DJ Antek' })
    deciding([one])
    open('/suggestions')
    await screen.findByRole('article', { name: 'DJ Antek' })

    await userEvent.click(card('DJ Antek').getByRole('button', { name: 'Accept' }))

    const said = await screen.findByRole('group', { name: 'What happened' })
    expect(said).toHaveTextContent('Added to the vendors: DJ Antek')
    expect(within(said).getByRole('link', { name: 'DJ Antek' })).toHaveAttribute('href', '/vendors/aaaaaaaa-0000-4000-8000-0000000000bb')
    expect(await screen.findByText('Nothing waits for you.')).toBeInTheDocument()
  })

  it('rejects with a request that has no body and says so', async () => {
    const one = suggestion({ title: 'Buy a thing from a stranger' })
    const { server } = deciding([one])
    open('/suggestions')
    await screen.findByRole('article', { name: 'Buy a thing from a stranger' })

    await userEvent.click(card('Buy a thing from a stranger').getByRole('button', { name: 'Reject' }))

    expect(await screen.findByRole('group', { name: 'What happened' })).toHaveTextContent('Rejected: Buy a thing from a stranger')
    expect(await (server.requests(`POST /api/suggestions/${one.id}/reject`)[0] as Request).text()).toBe('')
    await waitFor(() => expect(screen.queryByRole('article')).not.toBeInTheDocument())
  })

  it('sends a decision once, however often the button is pressed', async () => {
    const one = suggestion({ title: 'Once only' })
    let release: (response: Response) => void = () => undefined
    const { server } = inbox([one], {
      [`POST /api/suggestions/${one.id}/accept`]: () => new Promise<Response>((resolve) => (release = resolve)),
    })
    open('/suggestions')
    await screen.findByRole('article', { name: 'Once only' })

    const accept = card('Once only').getByRole('button', { name: 'Accept' })
    await userEvent.dblClick(accept)
    await userEvent.click(card('Once only').getByRole('button', { name: 'Reject' }))
    release(Response.json({ ...one, state: 'ACCEPTED', decidedBy: ANNA_MEMBER, decidedAt: '2026-11-19T12:00:00Z' }))

    await screen.findByRole('group', { name: 'What happened' })
    expect(server.calls.filter((call) => call.startsWith('POST /api/suggestions'))).toEqual([`POST /api/suggestions/${one.id}/accept`])
  })

  it('does not decide again by itself when the server cannot be reached; the person may try again', async () => {
    const one = suggestion({ title: 'Flaky' })
    const { server } = inbox([one], { [`POST /api/suggestions/${one.id}/accept`]: new TypeError('Failed to fetch') })
    open('/suggestions')
    await screen.findByRole('article', { name: 'Flaky' })

    await userEvent.click(card('Flaky').getByRole('button', { name: 'Accept' }))

    expect(await screen.findByText(/The server cannot be reached, so nothing was decided/)).toBeInTheDocument()
    expect(card('Flaky').getByRole('button', { name: 'Accept' })).toBeEnabled()
    expect(server.calls.filter((call) => call.startsWith('POST /api/suggestions'))).toHaveLength(1)
  })

  it('shows what the server refuses to make and points to changing it, leaving the card', async () => {
    const one = suggestion({ kind: 'PAYMENT', title: 'Pay the deposit', amount: 100 })
    inbox([one], {
      [`POST /api/suggestions/${one.id}/accept`]: problem(400, 'bad-request', 'Not valid', {
        fieldProblems: [
          { field: 'vendorId', message: 'That vendor is not in the planner any more.' },
          { field: 'amount', message: 'Enter an amount.' },
        ],
      }),
    })
    open('/suggestions')
    await screen.findByRole('article', { name: 'Pay the deposit' })

    await userEvent.click(card('Pay the deposit').getByRole('button', { name: 'Accept' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('That vendor is not in the planner any more.')
    expect(alert).toHaveTextContent('Enter an amount.')
    expect(alert).toHaveTextContent('Use “Change and accept”')
    expect(screen.getByRole('article', { name: 'Pay the deposit' })).toBeInTheDocument()
    expect(screen.queryByRole('group', { name: 'What happened' })).not.toBeInTheDocument()
  })

  it('says what the other owner decided, and by whom, when the other decision was made first; nothing is overwritten', async () => {
    const one = suggestion({ title: 'Contested' })
    const taken = { ...one, state: 'REJECTED' as const, decidedBy: PIOTR_MEMBER, decidedAt: '2026-11-19T11:00:00Z' }
    const { server } = inbox([one], {
      [`POST /api/suggestions/${one.id}/accept`]: problem(409, 'already-decided', 'Already decided'),
    })
    open('/suggestions')
    await screen.findByRole('article', { name: 'Contested' })
    // By now the other owner has rejected it.
    server.answer('GET /api/suggestions', (request) => {
      const state = new URL(request.url).searchParams.get('state')
      return Response.json(state === 'REJECTED' || state === 'ALL' ? [taken] : [])
    })

    await userEvent.click(card('Contested').getByRole('button', { name: 'Accept' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Piotr had already rejected this on 19 Nov 2026, 12:00, so it was not accepted: Contested')
    await waitFor(() => expect(screen.queryByRole('article', { name: 'Contested' })).not.toBeInTheDocument())
    expect(await screen.findByText('Nothing waits for you.')).toBeInTheDocument()
    // The page asked once, and did not turn to the other decision by itself.
    expect(server.calls.filter((call) => call.startsWith('POST /api/suggestions'))).toEqual([`POST /api/suggestions/${one.id}/accept`])
  })

  it('says it the other way round for a rejection', async () => {
    const one = suggestion({ title: 'Contested' })
    const taken = { ...one, state: 'ACCEPTED' as const, decidedBy: PIOTR_MEMBER, decidedAt: '2026-11-19T11:00:00Z' }
    const { server } = inbox([one], { [`POST /api/suggestions/${one.id}/reject`]: problem(409, 'already-decided') })
    open('/suggestions')
    await screen.findByRole('article', { name: 'Contested' })
    server.answer('GET /api/suggestions', (request) =>
      Response.json(new URL(request.url).searchParams.get('state') === 'ALL' ? [taken] : []),
    )

    await userEvent.click(card('Contested').getByRole('button', { name: 'Reject' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Piotr had already accepted this on 19 Nov 2026, 12:00, so it was not rejected: Contested')
  })

  it('counts what waits beside the name of the page, in words for a screen reader, and keeps count', async () => {
    const one = suggestion({ title: 'One' })
    const two = suggestion({ title: 'Two' })
    deciding([one, two])
    open('/suggestions')

    expect(await screen.findByRole('link', { name: 'Suggestions, 2 waiting' })).toHaveAttribute('href', '/suggestions')
    await userEvent.click(card('One').getByRole('button', { name: 'Reject' }))
    expect(await screen.findByRole('link', { name: 'Suggestions, 1 waiting' })).toBeInTheDocument()
    await userEvent.click(card('Two').getByRole('button', { name: 'Reject' }))
    await waitFor(() => expect(screen.getByRole('link', { name: 'Suggestions' })).toBeInTheDocument())
  })
})

describe('change and accept', () => {
  function formServer(one: Suggestion, extra: Record<string, Handler> = {}) {
    const { dale, server } = inbox([one], {
      [`POST /api/suggestions/${one.id}/accept`]: (request: Request) =>
        request.json().then((body: Partial<Suggestion>) =>
          Response.json({ ...one, ...body, state: 'ACCEPTED', decidedBy: ANNA_MEMBER, decidedAt: '2026-11-19T12:00:00Z', madeEntryId: 'eeeeeeee-eeee-4eee-8eee-0000000000cc' }),
        ),
      ...extra,
    })
    return { dale, server }
  }

  it('keeps where it comes from and the passage above the form, with the proposal’s values in it', async () => {
    const one = suggestion({
      kind: 'PAYMENT',
      title: 'Pay the deposit',
      notes: 'Bank transfer.',
      dueDate: '2026-12-05',
      amount: 250_050,
      paymentKind: 'DEPOSIT',
      categoryId: VENUE.id,
      vendorId: null,
      vendorName: 'Kwiaty Example',
    })
    formServer(one)
    open(`/suggestions/${one.id}`)

    expect(await screen.findByRole('heading', { level: 1, name: 'Change and accept' })).toBeInTheDocument()
    const source = within(screen.getByRole('region', { name: 'Where it comes from' }))
    expect(source.getByText('Jan from Palace on the Hill (jan@palace.example)')).toBeInTheDocument()
    expect(source.getByText('Please send the signed form by the end of the month.')).toBeInTheDocument()
    expect(source.getByText('62% sure')).toBeInTheDocument()
    expect(screen.getByLabelText('Title')).toHaveValue('Pay the deposit')
    expect(screen.getByLabelText(/Notes/)).toHaveValue('Bank transfer.')
    expect(screen.getByLabelText(/Due/)).toHaveValue('2026-12-05')
    expect(screen.getByLabelText('Amount in PLN')).toHaveValue('2500.50')
    expect(screen.getByLabelText(/Kind of payment/)).toHaveValue('DEPOSIT')
    expect(screen.getByLabelText(/Category/)).toHaveValue(VENUE.id)
    expect(screen.getByText('The mail names “Kwiaty Example”, who is not in the planner yet.')).toBeInTheDocument()
  })

  it('sends every value as the person changed it, an amount as it is typed, and returns to the list as it was', async () => {
    const one = suggestion({
      kind: 'PAYMENT',
      title: 'Pay the deposit',
      dueDate: '2026-12-05',
      amount: 250_050,
      paymentKind: 'DEPOSIT',
      vendorName: 'Kwiaty Example',
    })
    const { dale, server } = formServer(one)
    open('/suggestions?state=WAITING')
    await userEvent.click(await screen.findByRole('link', { name: 'Change and accept' }))
    expect(window.location.pathname).toBe(`/suggestions/${one.id}`)

    await userEvent.clear(await screen.findByLabelText('Title'))
    await userEvent.type(screen.getByLabelText('Title'), '  Deposit for the venue ')
    await userEvent.clear(screen.getByLabelText('Amount in PLN'))
    await userEvent.type(screen.getByLabelText('Amount in PLN'), '1 250,50')
    await userEvent.selectOptions(screen.getByLabelText(/Kind of payment/), 'Instalment')
    await userEvent.selectOptions(screen.getByLabelText(/Category/), 'Flowers and decor')
    await userEvent.selectOptions(screen.getByLabelText(/Vendor/), 'Palace on the Hill')
    await userEvent.clear(screen.getByLabelText(/Due/))
    await userEvent.type(screen.getByLabelText(/Notes/), 'Pay by transfer')
    await userEvent.click(screen.getByRole('button', { name: 'Accept with these values' }))

    await waitFor(() => expect(window.location.pathname).toBe('/suggestions'))
    expect(window.location.search).toBe('?state=WAITING')
    const [request] = server.requests(`POST /api/suggestions/${one.id}/accept`)
    expect(await (request as Request).json()).toEqual({
      title: 'Deposit for the venue',
      notes: 'Pay by transfer',
      startDate: null,
      dueDate: null,
      amount: 125_050,
      paymentKind: 'INSTALMENT',
      categoryId: FLOWERS.id,
      vendorId: dale.id,
      vendorName: null,
      vendorEmail: null,
      vendorPhone: null,
      vendorWebsite: null,
    })
    expect(await screen.findByRole('group', { name: 'What happened' })).toHaveTextContent('Added to the tasks: ')
  })

  it('has the fields of a step of the plan, and sends its days', async () => {
    const one = suggestion({ kind: 'PLAN_ITEM', title: 'Tasting', startDate: '2027-01-10', dueDate: '2027-01-12', categoryId: VENUE.id })
    const { server } = formServer(one)
    open(`/suggestions/${one.id}`)

    expect(await screen.findByLabelText(/Starts/)).toHaveValue('2027-01-10')
    expect(screen.getByLabelText(/Ends/)).toHaveValue('2027-01-12')
    expect(screen.queryByLabelText(/Amount/)).not.toBeInTheDocument()
    await userEvent.clear(screen.getByLabelText(/Ends/))
    await userEvent.type(screen.getByLabelText(/Ends/), '2027-01-20')
    await userEvent.click(screen.getByRole('button', { name: 'Accept with these values' }))

    await waitFor(() => expect(server.requests(`POST /api/suggestions/${one.id}/accept`)).toHaveLength(1))
    expect(await (server.requests(`POST /api/suggestions/${one.id}/accept`)[0] as Request).json()).toMatchObject({
      title: 'Tasting',
      startDate: '2027-01-10',
      dueDate: '2027-01-20',
      categoryId: VENUE.id,
      amount: null,
      paymentKind: null,
    })
  })

  it('has the fields of a vendor, whose name is the title, and sends them', async () => {
    const one = suggestion({
      kind: 'VENDOR',
      title: 'DJ Antek',
      vendorPhone: '+48 600 000 000',
      vendorEmail: 'antek@dj.example',
      vendorWebsite: 'https://dj.example',
    })
    const { server } = formServer(one)
    open(`/suggestions/${one.id}`)

    expect(await screen.findByLabelText('Name')).toHaveValue('DJ Antek')
    expect(screen.getByLabelText(/E-mail/)).toHaveValue('antek@dj.example')
    expect(screen.getByLabelText(/Phone/)).toHaveValue('+48 600 000 000')
    expect(screen.getByLabelText(/Website/)).toHaveValue('https://dj.example')
    expect(screen.queryByLabelText(/Vendor$/)).not.toBeInTheDocument()
    await userEvent.clear(screen.getByLabelText(/Phone/))
    await userEvent.click(screen.getByRole('button', { name: 'Accept with these values' }))

    await waitFor(() => expect(server.requests(`POST /api/suggestions/${one.id}/accept`)).toHaveLength(1))
    expect(await (server.requests(`POST /api/suggestions/${one.id}/accept`)[0] as Request).json()).toEqual({
      title: 'DJ Antek',
      notes: null,
      startDate: null,
      dueDate: null,
      amount: null,
      paymentKind: null,
      categoryId: null,
      vendorId: null,
      vendorName: null,
      vendorEmail: 'antek@dj.example',
      vendorPhone: null,
      vendorWebsite: 'https://dj.example',
    })
  })

  it('puts the server’s messages next to their fields, and says one for a field the form lacks above it', async () => {
    const one = suggestion({ kind: 'PAYMENT', title: 'Pay the deposit' })
    const { server } = formServer(one, {
      [`POST /api/suggestions/${one.id}/accept`]: problem(400, 'bad-request', 'Not valid', {
        fieldProblems: [
          { field: 'amount', message: 'A payment needs an amount.' },
          { field: 'dueDate', message: 'Choose a day that exists.' },
          { field: 'vendorName', message: 'Odd name.' },
        ],
      }),
    })
    open(`/suggestions/${one.id}`)
    await userEvent.click(await screen.findByRole('button', { name: 'Accept with these values' }))

    expect(await screen.findByText('A payment needs an amount.')).toBeInTheDocument()
    expect(screen.getByLabelText('Amount in PLN')).toHaveAccessibleDescription('A payment needs an amount.')
    expect(screen.getByLabelText(/Due/)).toHaveAccessibleDescription('Choose a day that exists.')
    expect(screen.getByRole('alert')).toHaveTextContent('Odd name.')
    // Still the form, and still asking nothing by itself.
    expect(server.requests(`POST /api/suggestions/${one.id}/accept`)).toHaveLength(1)
    expect(window.location.pathname).toBe(`/suggestions/${one.id}`)
  })

  it('does not send an amount it cannot read', async () => {
    const one = suggestion({ kind: 'PAYMENT', title: 'Pay the deposit' })
    const { server } = formServer(one)
    open(`/suggestions/${one.id}`)
    await userEvent.type(await screen.findByLabelText('Amount in PLN'), 'a lot')
    await userEvent.click(screen.getByRole('button', { name: 'Accept with these values' }))

    expect(await screen.findByText('Enter an amount like 2500 or 2500.50.')).toBeInTheDocument()
    expect(server.calls.filter((call) => call.startsWith('POST /api/suggestions'))).toEqual([])
  })

  it('shows a suggestion that is already decided as decided, not as a form', async () => {
    const one = suggestion({ title: 'Taken', state: 'ACCEPTED', decidedBy: PIOTR_MEMBER, decidedAt: '2026-11-15T09:30:00Z', madeEntryId: 'eeeeeeee-eeee-4eee-8eee-000000000001' })
    formServer(one)
    open(`/suggestions/${one.id}`)

    expect(await screen.findByText('Accepted by Piotr, 15 Nov 2026, 10:30')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /accept/i })).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Title')).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open the task' })).toBeInTheDocument()
  })

  it('shows what was decided first, and by whom, when the other decision came in while the form was open', async () => {
    const one = suggestion({ title: 'Contested' })
    const taken = { ...one, state: 'REJECTED' as const, decidedBy: PIOTR_MEMBER, decidedAt: '2026-11-19T11:00:00Z' }
    let theyDecided = false
    const { server } = formServer(one, {
      [`POST /api/suggestions/${one.id}/accept`]: () => {
        theyDecided = true
        return problem(409, 'already-decided')
      },
      'GET /api/suggestions': () => Response.json([theyDecided ? taken : one]),
    })
    open(`/suggestions/${one.id}`)
    await userEvent.click(await screen.findByRole('button', { name: 'Accept with these values' }))

    // Nothing was decided on the page's own: it asked once, and shows what stands now.
    expect(await screen.findByText('Rejected by Piotr, 19 Nov 2026, 12:00')).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('Piotr had already rejected this')
    expect(screen.queryByRole('button', { name: /accept/i })).not.toBeInTheDocument()
    expect(server.calls.filter((call) => call.startsWith('POST /api/suggestions'))).toHaveLength(1)
  })

  it('says so for a suggestion that does not exist', async () => {
    inbox([])
    open('/suggestions/55555555-5555-4555-8555-00000000ffff')
    expect(await screen.findByText('This suggestion does not exist.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to the suggestions' })).toHaveAttribute('href', '/suggestions')
  })

  it('shows the passage and title of a mail as plain text in the form too', async () => {
    const one = suggestion({ title: '<b>Pay</b>', passage: '<a href="https://evil.example">click</a>' })
    formServer(one)
    open(`/suggestions/${one.id}`)

    expect(await screen.findByText('<a href="https://evil.example">click</a>')).toBeInTheDocument()
    expect(screen.getByLabelText('Title')).toHaveValue('<b>Pay</b>')
    expect(screen.queryByRole('link', { name: 'click' })).not.toBeInTheDocument()
  })
})

describe('home and the way around', () => {
  it('says on the home page how many wait, with a way in', async () => {
    inbox([suggestion(), suggestion(), suggestion()])
    open('/')

    expect(await screen.findByRole('link', { name: '3 suggestions wait for you' })).toHaveAttribute('href', '/suggestions')
  })

  it('says it in the singular', async () => {
    inbox([suggestion()])
    open('/')
    expect(await screen.findByRole('link', { name: '1 suggestion waits for you' })).toBeInTheDocument()
  })

  it('says nothing about suggestions on the home page where none wait', async () => {
    inbox([])
    planningServer([], { 'GET /api/home': home() })
    open('/')

    await screen.findByRole('heading', { name: 'Anna & Piotr' })
    await screen.findByText(/No task is waiting for you/)
    expect(within(screen.getByRole('main')).queryByText(/suggestion/i)).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Suggestions' })).toHaveAttribute('href', '/suggestions')
  })
})
