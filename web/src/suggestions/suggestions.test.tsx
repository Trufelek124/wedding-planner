import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../App.tsx'
import { ANNA_MEMBER, entry, FLOWERS, PIOTR_MEMBER, home, planningServer, problem, suggestion, vendor, VENUE, type Handler } from '../test-server.ts'
import type { Schemas } from '../api/client.ts'
import { alreadyDecided } from './decisions.ts'

type Suggestion = Schemas['Suggestion']

function open(path: string) {
  window.history.pushState(null, '', path)
  render(<App />)
}

const DALE = vendor({ name: 'Palace on the Hill' })

/** A server that answers the suggestions the way the real one does: by the state that was asked for. */
function inbox(all: Suggestion[], extra: Record<string, Handler> = {}) {
  // One suggestion by its id, as it stands in `all` when it is asked for.
  const byId: Record<string, Handler> = {}
  for (const one of all) {
    byId[`GET /api/suggestions/${one.id}`] = () => Response.json(all.find((other) => other.id === one.id) ?? one)
  }
  return {
    dale: DALE,
    server: planningServer([], {
      'GET /api/vendors': Response.json([DALE]),
      'GET /api/suggestions': (request) => {
        const state = new URL(request.url).searchParams.get('state') ?? 'WAITING'
        return Response.json(all.filter((one) => state === 'ALL' || one.state === state))
      },
      ...byId,
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

  it('draws the link to the e-mail for your own inbox and not for the partner’s, which the card names', async () => {
    inbox([
      suggestion({ title: 'In my inbox', inboxMemberId: ANNA_MEMBER, mailLink: 'https://mail.google.com/mail/u/0/#all/mine' }),
      suggestion({ title: 'In theirs', inboxMemberId: PIOTR_MEMBER, mailLink: 'https://mail.google.com/mail/u/0/#all/theirs' }),
      suggestion({ title: 'In nobody’s I know', inboxMemberId: 'f0f0f0f0-0000-4000-8000-000000000000', mailLink: 'https://mail.google.com/mail/u/0/#all/who' }),
    ])
    open('/suggestions')

    await screen.findByRole('article', { name: 'In my inbox' })
    expect(card('In my inbox').getByRole('link', { name: 'Open the e-mail' })).toHaveAttribute('href', 'https://mail.google.com/mail/u/0/#all/mine')
    expect(card('In theirs').queryByRole('link', { name: 'Open the e-mail' })).not.toBeInTheDocument()
    expect(card('In theirs').getByText('Arrived').nextSibling).toHaveTextContent('in Piotr’s inbox')
    expect(card('In nobody’s I know').queryByRole('link', { name: 'Open the e-mail' })).not.toBeInTheDocument()
    expect(document.querySelectorAll('a[href*="theirs"]')).toHaveLength(0)
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
    server.answer(`GET /api/suggestions/${one.id}`, () => Response.json(taken))

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
    server.answer(`GET /api/suggestions/${one.id}`, () => Response.json(taken))

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
      'GET /api/suggestions': () => Response.json(theyDecided ? [] : [one]),
      [`GET /api/suggestions/${one.id}`]: () => Response.json(theyDecided ? taken : one),
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
    inbox([], { 'GET /api/suggestions/55555555-5555-4555-8555-00000000ffff': problem(404, 'not-found') })
    open('/suggestions/55555555-5555-4555-8555-00000000ffff')
    expect(await screen.findByText(/no longer here: the suggestion does not exist any more/)).toBeInTheDocument()
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

const MADE = 'eeeeeeee-eeee-4eee-8eee-0000000000a1'

/** One that the import added by itself and nobody has kept or undone. */
function added(overrides: Partial<Suggestion> = {}): Suggestion {
  return suggestion({ state: 'ADDED', title: 'Send the signed form', confidence: 91, madeEntryId: MADE, ...overrides })
}

/** An inbox whose keep and undo change what it holds, as the real server does. */
function keeping(all: Suggestion[], extra: Record<string, Handler> = {}) {
  const settle = (state: 'ACCEPTED' | 'REJECTED') => (request: Request) => {
    const id = new URL(request.url).pathname.split('/')[3]
    const found = all.find((one) => one.id === id)
    if (found === undefined) return problem(404, 'not-found')
    Object.assign(found, { state, decidedBy: ANNA_MEMBER, decidedAt: '2026-11-19T12:00:00Z', madeEntryId: state === 'REJECTED' ? null : found.madeEntryId })
    return Response.json(found)
  }
  const routes: Record<string, Handler> = { [`GET /api/entries/${MADE}`]: Response.json(entry({ id: MADE })) }
  for (const one of all) {
    routes[`POST /api/suggestions/${one.id}/undo`] = settle('REJECTED')
    routes[`POST /api/suggestions/${one.id}/keep`] = settle('ACCEPTED')
  }
  return inbox(all, { ...routes, ...extra })
}

const posts = (server: { calls: string[] }) => server.calls.filter((call) => call.startsWith('POST /api/suggestions'))

describe('what was added by itself', () => {
  it('is a part of the inbox, kept in the address, with a line saying it is not a list to work through', async () => {
    const one = added()
    inbox([suggestion({ title: 'Still open' }), one])
    open('/suggestions')
    await screen.findByRole('article', { name: 'Still open' })

    await userEvent.selectOptions(screen.getByLabelText('Show'), 'Added by itself')

    expect(await screen.findByRole('article', { name: 'Send the signed form' })).toBeInTheDocument()
    expect(window.location.search).toBe('?state=ADDED')
    expect(
      screen.getByText(
        'These are already in your plan. Undo what should not be there; what nobody undoes counts as kept after thirty days.',
      ),
    ).toBeInTheDocument()
    expect(screen.queryByRole('article', { name: 'Still open' })).not.toBeInTheDocument()
  })

  it('shows what was added, where it comes from, how sure the import was, and Undo and Keep, with a way to the entry', async () => {
    const one = added({ dueDate: '2026-11-30', categoryId: VENUE.id, notes: 'Both signatures.' })
    const step = added({ kind: 'PLAN_ITEM', title: 'Tasting', startDate: '2027-01-10', dueDate: '2027-01-12', madeEntryId: MADE, inboxMemberId: PIOTR_MEMBER })
    inbox([one, step])
    open('/suggestions?state=ADDED')

    await screen.findByRole('article', { name: 'Send the signed form' })
    const t = card('Send the signed form')
    expect(t.getByText('Task')).toBeInTheDocument()
    expect(t.getByText('Due').nextSibling).toHaveTextContent('30 Nov 2026')
    expect(t.getByText('Category').nextSibling).toHaveTextContent('Venue')
    expect(t.getByText('Both signatures.')).toBeInTheDocument()
    expect(t.getByText('From').nextSibling).toHaveTextContent('Jan from Palace on the Hill (jan@palace.example)')
    expect(t.getByText('Subject').nextSibling).toHaveTextContent('Your booking')
    expect(t.getByText('Please send the signed form by the end of the month.').tagName).toBe('BLOCKQUOTE')
    expect(t.getByText('91% sure')).toBeInTheDocument()
    expect(t.getByText('Added by itself')).toBeInTheDocument()
    expect(t.getByRole('link', { name: 'Open the task' })).toHaveAttribute('href', `/entries/${MADE}`)
    expect(t.getByRole('link', { name: 'Open the e-mail' })).toBeInTheDocument()
    expect(t.getByRole('button', { name: 'Undo' })).toBeEnabled()
    expect(t.getByRole('button', { name: 'Keep' })).toBeEnabled()
    // Nothing here is for accepting or rejecting.
    expect(t.queryByRole('button', { name: /accept|reject/i })).not.toBeInTheDocument()

    const s = card('Tasting')
    expect(s.getByText('Step of the plan')).toBeInTheDocument()
    expect(s.getByText('Starts').nextSibling).toHaveTextContent('10 Jan 2027')
    expect(s.getByRole('link', { name: 'Open the step of the plan' })).toBeInTheDocument()
    expect(s.getByText('Arrived').nextSibling).toHaveTextContent('in Piotr’s inbox')
    expect(s.queryByRole('link', { name: 'Open the e-mail' })).not.toBeInTheDocument()
  })

  it('says plainly that nothing was added, with no button that leads nowhere', async () => {
    inbox([suggestion({ title: 'Still open' })])
    open('/suggestions?state=ADDED')

    expect(await screen.findByText('Nothing was added by itself.')).toBeInTheDocument()
    expect(within(screen.getByRole('main')).queryByRole('button')).not.toBeInTheDocument()
  })

  it('undoes with one request, says that the entry was removed, takes the card away and puts the keyboard on the notice', async () => {
    const one = added()
    const other = added({ title: 'Book the florist' })
    const { server } = keeping([one, other])
    open('/suggestions?state=ADDED')
    await screen.findByRole('article', { name: 'Send the signed form' })

    await userEvent.click(card('Send the signed form').getByRole('button', { name: 'Undo' }))

    const said = await screen.findByRole('group', { name: 'What happened' })
    expect(said).toHaveTextContent('Undone: “Send the signed form”. It was removed from your plan.')
    await waitFor(() => expect(screen.queryByRole('article', { name: 'Send the signed form' })).not.toBeInTheDocument())
    expect(screen.getByRole('article', { name: 'Book the florist' })).toBeInTheDocument()
    await waitFor(() => expect(said).toHaveFocus())
    const request = server.requests(`POST /api/suggestions/${one.id}/undo`)[0]
    expect(await (request as Request).text()).toBe('')
    // One press, one request; nothing was asked a second time.
    expect(posts(server)).toEqual([`POST /api/suggestions/${one.id}/undo`])
  })

  it('keeps with one request, says that it stays, leads to the entry and takes the card away', async () => {
    const one = added()
    const { server } = keeping([one])
    open('/suggestions?state=ADDED')
    await screen.findByRole('article', { name: 'Send the signed form' })

    await userEvent.click(card('Send the signed form').getByRole('button', { name: 'Keep' }))

    const said = await screen.findByRole('group', { name: 'What happened' })
    expect(said).toHaveTextContent('Kept: “Send the signed form”.')
    expect(within(said).getByRole('link', { name: 'Send the signed form' })).toHaveAttribute('href', `/entries/${MADE}`)
    await waitFor(() => expect(screen.queryByRole('article', { name: 'Send the signed form' })).not.toBeInTheDocument())
    await waitFor(() => expect(said).toHaveFocus())
    expect(posts(server)).toEqual([`POST /api/suggestions/${one.id}/keep`])
    expect(await screen.findByText('Nothing was added by itself.')).toBeInTheDocument()
  })

  it('says which button is at work and disables both while a request is on its way', async () => {
    const one = added()
    let answer: (response: Response) => void = () => undefined
    keeping([one], {
      [`POST /api/suggestions/${one.id}/undo`]: () => new Promise<Response>((resolve) => (answer = resolve)),
    })
    open('/suggestions?state=ADDED')
    await screen.findByRole('article', { name: 'Send the signed form' })

    await userEvent.click(card('Send the signed form').getByRole('button', { name: 'Undo' }))

    expect(await card('Send the signed form').findByRole('button', { name: 'Undoing…' })).toBeDisabled()
    expect(card('Send the signed form').getByRole('button', { name: 'Keep' })).toBeDisabled()
    answer(Response.json({ ...one, state: 'REJECTED', madeEntryId: null }))
    expect(await screen.findByText(/It was removed from your plan/)).toBeInTheDocument()
  })

  it('shows the server’s sentence when the entry was changed, takes the card away, and leads to the entry that stays', async () => {
    const one = added()
    const sentence =
      'This entry was changed after it was added, so it stays in your plan and this suggestion counts as kept. You can change or remove the entry like any other.'
    const { server } = keeping([one], {
      [`POST /api/suggestions/${one.id}/undo`]: () => {
        Object.assign(one, { state: 'ACCEPTED', decidedBy: ANNA_MEMBER, decidedAt: '2026-11-19T12:00:00Z' })
        return problem(409, 'entry-changed', 'Entry changed', { detail: sentence })
      },
    })
    open('/suggestions?state=ADDED')
    await screen.findByRole('article', { name: 'Send the signed form' })

    await userEvent.click(card('Send the signed form').getByRole('button', { name: 'Undo' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(sentence)
    expect(alert).not.toHaveTextContent(/Undone/)
    expect(within(alert).getByRole('link', { name: 'Open the task' })).toHaveAttribute('href', `/entries/${MADE}`)
    await waitFor(() => expect(screen.queryByRole('article', { name: 'Send the signed form' })).not.toBeInTheDocument())
    expect(posts(server)).toHaveLength(1)
  })

  it('says it as the server says it when the entry is gone, and offers no way to it', async () => {
    const one = added()
    const sentence = 'This entry is no longer in your plan: one of you removed it already. This suggestion counts as kept.'
    keeping([one], {
      [`POST /api/suggestions/${one.id}/undo`]: () => {
        Object.assign(one, { state: 'ACCEPTED', decidedBy: ANNA_MEMBER })
        return problem(409, 'entry-changed', 'Entry changed', { detail: sentence })
      },
      [`GET /api/entries/${MADE}`]: problem(404, 'not-found'),
    })
    open('/suggestions?state=ADDED')
    await screen.findByRole('article', { name: 'Send the signed form' })

    await userEvent.click(card('Send the signed form').getByRole('button', { name: 'Undo' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(sentence)
    expect(within(alert).queryByRole('link')).not.toBeInTheDocument()
  })

  it('says that it was kept, by whom, when the other owner kept it first, and undoes nothing', async () => {
    const one = added()
    const { server } = keeping([one], {
      [`POST /api/suggestions/${one.id}/undo`]: () => {
        Object.assign(one, { state: 'ACCEPTED', decidedBy: PIOTR_MEMBER, decidedAt: '2026-11-19T11:00:00Z' })
        return problem(409, 'already-decided')
      },
    })
    open('/suggestions?state=ADDED')
    await screen.findByRole('article', { name: 'Send the signed form' })

    await userEvent.click(card('Send the signed form').getByRole('button', { name: 'Undo' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('“Send the signed form” was already kept by Piotr.')
    await waitFor(() => expect(screen.queryByRole('article', { name: 'Send the signed form' })).not.toBeInTheDocument())
    expect(posts(server)).toEqual([`POST /api/suggestions/${one.id}/undo`])
  })

  it('says that it was undone, by whom, when keeping comes too late', async () => {
    const one = added()
    keeping([one], {
      [`POST /api/suggestions/${one.id}/keep`]: () => {
        Object.assign(one, { state: 'REJECTED', decidedBy: ANNA_MEMBER, decidedAt: '2026-11-19T11:00:00Z', madeEntryId: null })
        return problem(409, 'already-decided')
      },
    })
    open('/suggestions?state=ADDED')
    await screen.findByRole('article', { name: 'Send the signed form' })

    await userEvent.click(card('Send the signed form').getByRole('button', { name: 'Keep' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('“Send the signed form” was already undone by you.')
  })

  it('never says somebody decided what nobody decided', async () => {
    const one = added()
    const timeOut = { ...one, state: 'ACCEPTED' as const, decidedBy: null, decidedAt: '2026-11-19T00:00:00Z' }
    keeping([one], { [`POST /api/suggestions/${one.id}/undo`]: problem(409, 'already-decided') })
    open('/suggestions?state=ADDED')
    await screen.findByRole('article', { name: 'Send the signed form' })
    // Meanwhile the thirty days have passed.
    Object.assign(one, timeOut)

    await userEvent.click(card('Send the signed form').getByRole('button', { name: 'Undo' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('“Send the signed form” counted as kept after thirty days.')
    expect(alert).not.toHaveTextContent(/somebody|someone|already/i)
  })

  it('says that one that was added by itself is to be kept or undone when it is accepted or rejected', async () => {
    const one = added()
    inbox([one])
    const members = [{ id: ANNA_MEMBER, displayName: 'Anna', you: true }]
    for (const tried of ['accept', 'reject'] as const) {
      const notice = await alreadyDecided(one.id, one.title, tried, members, 'Europe/Warsaw')
      expect(notice.tone).toBe('alert')
      expect(`${notice.lead}“${notice.title}”${notice.tail}`).toBe('“Send the signed form” was added by itself. Keep it or undo it.')
    }
  })

  it('says in the Accepted part that one counted as kept after thirty days, and who decided the others', async () => {
    const lapsed = suggestion({ title: 'Lapsed', state: 'ACCEPTED', decidedBy: null, decidedAt: '2026-11-01T00:00:00Z', madeEntryId: MADE })
    const taken = suggestion({ title: 'Taken', state: 'ACCEPTED', decidedBy: PIOTR_MEMBER, decidedAt: '2026-11-15T09:30:00Z', madeEntryId: MADE })
    inbox([lapsed, taken])
    open('/suggestions?state=ACCEPTED')

    await screen.findByRole('article', { name: 'Lapsed' })
    expect(card('Lapsed').getByText('Added by itself; kept after thirty days')).toBeInTheDocument()
    expect(card('Lapsed').queryByText(/Accepted by/)).not.toBeInTheDocument()
    expect(card('Lapsed').queryByRole('button')).not.toBeInTheDocument()
    expect(card('Taken').getByText('Accepted by Piotr, 15 Nov 2026, 10:30')).toBeInTheDocument()
  })

  it('shows markup in a title or a passage letter for letter, with no link made of it', async () => {
    const one = added({ title: '<b>Pay</b> now', passage: '<a href="https://evil.example">click</a>' })
    keeping([one])
    open('/suggestions?state=ADDED')

    expect(await screen.findByText('<a href="https://evil.example">click</a>')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '<b>Pay</b> now' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'click' })).not.toBeInTheDocument()
    await userEvent.click(card('<b>Pay</b> now').getByRole('button', { name: 'Keep' }))
    const said = await screen.findByRole('group', { name: 'What happened' })
    expect(said).toHaveTextContent('Kept: “<b>Pay</b> now”.')
    expect(said.querySelector('b')).toBeNull()
  })
})

describe('where an entry comes from', () => {
  it('shows an accepted one’s source: who wrote, the subject, when, the passage, and the way to the e-mail', async () => {
    const one = suggestion({ title: 'Taken', state: 'ACCEPTED', decidedBy: PIOTR_MEMBER, decidedAt: '2026-11-15T09:30:00Z', madeEntryId: MADE })
    inbox([one])
    open(`/suggestions/${one.id}`)

    expect(await screen.findByRole('heading', { name: 'Taken' })).toBeInTheDocument()
    expect(screen.getByText('From').nextSibling).toHaveTextContent('Jan from Palace on the Hill (jan@palace.example)')
    expect(screen.getByText('Subject').nextSibling).toHaveTextContent('Your booking')
    expect(screen.getByText('Arrived').nextSibling).toHaveTextContent('12 Nov 2026, 14:05, in your inbox')
    expect(screen.getByText('Please send the signed form by the end of the month.')).toBeInTheDocument()
    expect(screen.getByText('Accepted by Piotr, 15 Nov 2026, 10:30')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open the e-mail' })).toHaveAttribute('href', 'https://mail.google.com/mail/u/0/#all/abc123')
    expect(screen.queryByRole('button', { name: /undo|keep/i })).not.toBeInTheDocument()
  })

  it('shows one that was added by itself with Undo and Keep, and undoes it from there', async () => {
    const one = added()
    const { server } = keeping([one])
    open(`/suggestions/${one.id}`)

    expect(await screen.findByRole('heading', { name: 'Send the signed form' })).toBeInTheDocument()
    expect(screen.getByText('Added by itself')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Keep' })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Undo' }))

    const said = await screen.findByRole('group', { name: 'What happened' })
    expect(said).toHaveTextContent('Undone: “Send the signed form”. It was removed from your plan.')
    await waitFor(() => expect(said).toHaveFocus())
    expect(await screen.findByText('Rejected by you, 19 Nov 2026, 13:00')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Undo' })).not.toBeInTheDocument()
    expect(posts(server)).toEqual([`POST /api/suggestions/${one.id}/undo`])
  })

  it('keeps one from there', async () => {
    const one = added()
    const { server } = keeping([one])
    open(`/suggestions/${one.id}`)

    await userEvent.click(await screen.findByRole('button', { name: 'Keep' }))

    expect(await screen.findByRole('group', { name: 'What happened' })).toHaveTextContent('Kept: “Send the signed form”.')
    expect(await screen.findByText('Accepted by you, 19 Nov 2026, 13:00')).toBeInTheDocument()
    expect(posts(server)).toEqual([`POST /api/suggestions/${one.id}/keep`])
  })

  it('says that one counted as kept after thirty days is the source of an entry, not a thing to decide', async () => {
    const one = suggestion({ title: 'Lapsed', state: 'ACCEPTED', decidedBy: null, decidedAt: '2026-11-01T00:00:00Z', madeEntryId: MADE })
    inbox([one])
    open(`/suggestions/${one.id}`)

    expect(await screen.findByText('Added by itself; kept after thirty days')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /undo|keep/i })).not.toBeInTheDocument()
  })

  it('says that the source is no longer there for a suggestion that cannot be found', async () => {
    inbox([], { 'GET /api/suggestions/55555555-5555-4555-8555-00000000ffff': problem(404, 'not-found') })
    open('/suggestions/55555555-5555-4555-8555-00000000ffff')

    expect(await screen.findByRole('alert')).toHaveTextContent('The e-mail this came from is no longer here')
  })
})

describe('the two numbers', () => {
  function counted(waitingCount: number, addedCount: number) {
    const waiting = Array.from({ length: waitingCount }, () => suggestion())
    const addedOnes = Array.from({ length: addedCount }, () => added())
    return inbox([...waiting, ...addedOnes])
  }

  it('says on Home how many were added, with a way to that part, apart from how many wait', async () => {
    counted(2, 3)
    open('/')

    expect(await screen.findByRole('link', { name: '3 entries were added from your e-mail' })).toHaveAttribute('href', '/suggestions?state=ADDED')
    expect(screen.getByRole('link', { name: '2 suggestions wait for you' })).toBeInTheDocument()
    expect(screen.queryByText(/5 /)).not.toBeInTheDocument()
  })

  it('says it in the singular, and when none wait', async () => {
    counted(0, 1)
    open('/')

    expect(await screen.findByRole('link', { name: '1 entry was added from your e-mail' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /wait/ })).not.toBeInTheDocument()
  })

  it('says nothing about either on Home where there is nothing', async () => {
    counted(0, 0)
    open('/')

    await screen.findByRole('heading', { name: 'Anna & Piotr' })
    expect(screen.queryByRole('link', { name: /added from your e-mail/ })).not.toBeInTheDocument()
  })

  it('says in the top bar how many wait and, quieter, how many were added, each in words for a screen reader', async () => {
    counted(2, 3)
    open('/suggestions')

    const link = await screen.findByRole('link', { name: 'Suggestions, 2 waiting, 3 added by themselves' })
    expect(link).toHaveTextContent('Suggestions2, 2 waiting+3, 3 added by themselves')
  })

  it('counts down what was added when it is kept or undone', async () => {
    const one = added()
    const two = added({ title: 'Book the florist' })
    keeping([one, two])
    open('/suggestions?state=ADDED')
    await screen.findByRole('link', { name: 'Suggestions, 2 added by themselves' })

    await userEvent.click(card('Send the signed form').getByRole('button', { name: 'Keep' }))

    expect(await screen.findByRole('link', { name: 'Suggestions, 1 added by themselves' })).toBeInTheDocument()
  })
})

const FROM_A_DOCUMENT = 'From a document that came with this e-mail'

describe('a suggestion whose passage stands in a document', () => {
  it('says so beside the passage in the inbox, and says nothing of the kind for one whose words are in the mail', async () => {
    const offer = suggestion({ kind: 'PAYMENT', title: 'Pay the deposit', amount: 250_000, passage: 'Deposit: 2500,00 PLN', fromDocument: true })
    const plain = suggestion({ title: 'Reply to Jan', passage: 'Could you reply by Friday?', fromDocument: false })
    inbox([offer, plain])
    open('/suggestions')

    await screen.findByRole('article', { name: 'Pay the deposit' })
    const o = card('Pay the deposit')
    expect(o.getByText(FROM_A_DOCUMENT)).toBeInTheDocument()
    expect(o.getByText('Deposit: 2500,00 PLN').tagName).toBe('BLOCKQUOTE')
    expect(card('Reply to Jan').queryByText(/From a document/)).not.toBeInTheDocument()
  })

  it('says so in the part “Added by itself”, with the amount and the day of the payment, Undo and Keep', async () => {
    const paid = added({
      kind: 'PAYMENT',
      title: 'Pay the deposit',
      amount: 250_000,
      paymentKind: 'DEPOSIT',
      dueDate: '2026-12-01',
      fromDocument: true,
      passage: 'Deposit: 2500,00 PLN by 1 December 2026',
    })
    inbox([paid])
    open('/suggestions?state=ADDED')

    await screen.findByRole('article', { name: 'Pay the deposit' })
    const p = card('Pay the deposit')
    expect(p.getByText('Payment')).toBeInTheDocument()
    expect(p.getByText('Amount').nextSibling).toHaveTextContent('2,500.00 zł, deposit')
    expect(p.getByText('Due').nextSibling).toHaveTextContent('1 Dec 2026')
    expect(p.getByText(FROM_A_DOCUMENT)).toBeInTheDocument()
    expect(p.getByText('Added by itself')).toBeInTheDocument()
    expect(p.getByRole('button', { name: 'Undo' })).toBeEnabled()
    expect(p.getByRole('button', { name: 'Keep' })).toBeEnabled()
  })

  it('says so in the form that changes and accepts one that waits', async () => {
    const waiting = suggestion({ title: 'From the offer', fromDocument: true })
    inbox([waiting])

    open(`/suggestions/${waiting.id}`)
    expect(await screen.findByRole('heading', { level: 1, name: 'Change and accept' })).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Where it comes from' })).getByText(FROM_A_DOCUMENT)).toBeInTheDocument()
  })

  it('says so on the page of an accepted one and of an added one', async () => {
    const kept = suggestion({ title: 'Taken from the offer', state: 'ACCEPTED', decidedBy: PIOTR_MEMBER, decidedAt: '2026-11-15T09:30:00Z', madeEntryId: MADE, fromDocument: true })
    inbox([kept])
    open(`/suggestions/${kept.id}`)

    expect(await screen.findByRole('heading', { name: 'Taken from the offer' })).toBeInTheDocument()
    expect(screen.getByText(FROM_A_DOCUMENT)).toBeInTheDocument()
  })

  it('says nothing of a document on the page of one whose words are in the mail', async () => {
    const plain = suggestion({ title: 'From the mail', state: 'ACCEPTED', decidedBy: PIOTR_MEMBER, decidedAt: '2026-11-15T09:30:00Z', madeEntryId: MADE })
    inbox([plain])
    open(`/suggestions/${plain.id}`)

    expect(await screen.findByRole('heading', { name: 'From the mail' })).toBeInTheDocument()
    expect(screen.queryByText(/From a document/)).not.toBeInTheDocument()
  })

  it('keeps the words as plain text next to a passage that looks like markup', async () => {
    const one = suggestion({ title: 'Marked up', passage: '<a href="https://evil.example">click</a>', fromDocument: true })
    inbox([one])
    open('/suggestions')

    await screen.findByRole('article', { name: 'Marked up' })
    expect(card('Marked up').getByText('<a href="https://evil.example">click</a>')).toBeInTheDocument()
    expect(card('Marked up').getByText(FROM_A_DOCUMENT)).toBeInTheDocument()
    expect(card('Marked up').queryByRole('link', { name: 'click' })).not.toBeInTheDocument()
  })
})
