import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '../App.tsx'
import type { Schemas } from '../api/client.ts'
import { home, planningServer, problem, type Handler } from '../test-server.ts'
import { browser } from './mailApi.ts'

type MailState = Schemas['MailState']

function open(path: string) {
  window.history.pushState(null, '', path)
  render(<App />)
}

const NOT_CONNECTED = { state: 'NOT_CONNECTED', address: null, connectedAt: null, lastLookAt: null, messagesNoted: 0 } as const

function mail(overrides: Partial<MailState> = {}): MailState {
  return {
    setUp: true,
    yours: NOT_CONNECTED,
    partner: { joined: true, state: 'NOT_CONNECTED', lastLookAt: null },
    ...overrides,
  }
}

const CONNECTED = {
  state: 'CONNECTED',
  address: 'anna@planner.example',
  connectedAt: '2026-11-10T09:30:00Z',
  lastLookAt: '2026-11-19T13:05:00Z',
  messagesNoted: 412,
} as const

function serverWith(state: MailState, extra: Record<string, Handler> = {}) {
  return planningServer([], { 'GET /api/mail': () => Response.json(state), ...extra })
}

const GOOGLE = 'https://accounts.google.com/o/oauth2/v2/auth?client_id=made-up&state=ticket'

afterEach(() => {
  vi.restoreAllMocks()
})

describe('the e-mail import page', () => {
  it('says that the import is not set up, and offers nothing to press', async () => {
    serverWith(mail({ setUp: false }))
    open('/mail')

    expect(await screen.findByText(/not set up on this server/)).toBeInTheDocument()
    expect(within(screen.getByRole('main')).queryByRole('button')).toBeNull()
  })

  it('tells what connecting means before the button, when nothing is connected', async () => {
    serverWith(mail())
    open('/mail')

    const heading = await screen.findByRole('heading', { name: 'What connecting means' })
    expect(heading).toBeInTheDocument()
    const list = screen.getByRole('list', { name: 'What connecting means' })
    expect(list).toHaveTextContent('read your mail, and nothing else')
    expect(list).toHaveTextContent('cannot send, change or delete')
    expect(list).toHaveTextContent('last three months')
    expect(list).toHaveTextContent('only that it was looked at')
    expect(list).toHaveTextContent('sender, the subject and the beginning of every message will be sent to the AI service')
    expect(list).toHaveTextContent('not verified')
    expect(list).toHaveTextContent('“Advanced”')
    expect(list).toHaveTextContent('only notes which messages there are')
    const button = screen.getByRole('button', { name: 'Connect Gmail' })
    expect(heading.compareDocumentPosition(button) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Disconnect' })).toBeNull()
  })

  it('shows what stands when connected, and where the server has not yet looked', async () => {
    serverWith(mail({ yours: { ...CONNECTED, lastLookAt: null, messagesNoted: 0 } }))
    open('/mail')

    const facts = within(await screen.findByLabelText('Your connection'))
    expect(facts.getByText('anna@planner.example')).toBeInTheDocument()
    // The wedding's time zone is Warsaw: 09:30 UTC is 10:30 there.
    expect(facts.getByText('10 Nov 2026, 10:30')).toBeInTheDocument()
    expect(facts.getByText('not yet')).toBeInTheDocument()
    expect(facts.getByText('0')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Disconnect' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Connect Gmail' })).toBeNull()
    expect(screen.queryByRole('heading', { name: 'What connecting means' })).toBeNull()
  })

  it('shows when the server last looked, and how many messages it has noted', async () => {
    serverWith(mail({ yours: CONNECTED }))
    open('/mail')

    const facts = within(await screen.findByLabelText('Your connection'))
    expect(facts.getByText('19 Nov 2026, 14:05')).toBeInTheDocument()
    expect(facts.getByText('412')).toBeInTheDocument()
  })

  it('says that it has to be connected again, and offers to connect', async () => {
    serverWith(mail({ yours: { ...CONNECTED, state: 'NEEDS_RECONNECTING' } }))
    open('/mail')

    expect(await screen.findByText(/no longer lets the planner into your mail/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Connect Gmail' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Disconnect' })).toBeNull()
  })

  it('names the partner and says where they stand, never which mailbox', async () => {
    serverWith(
      mail({
        yours: CONNECTED,
        partner: { joined: true, state: 'CONNECTED', lastLookAt: '2026-11-19T12:00:00Z' },
      }),
    )
    open('/mail')

    const partner = await screen.findByRole('region', { name: 'Piotr' })
    expect(partner).toHaveTextContent('Piotr’s Gmail is connected; last looked on 19 Nov 2026, 13:00.')
    expect(document.body.textContent).not.toMatch(/piotr@/i)
  })

  it('says it for a partner who has not connected, has to connect again, or has not joined', async () => {
    const server = serverWith(mail({ partner: { joined: true, state: 'NOT_CONNECTED', lastLookAt: null } }))
    open('/mail')
    expect(await screen.findByText('Piotr has not connected their Gmail.')).toBeInTheDocument()

    server.answer('GET /api/mail', () => Response.json(mail({ partner: { joined: true, state: 'NEEDS_RECONNECTING', lastLookAt: null } })))
    await userEvent.click(screen.getByRole('link', { name: 'Home' }))
    await userEvent.click(await screen.findByRole('link', { name: /Connect your e-mail/ }))
    expect(await screen.findByText('Piotr’s Gmail has to be connected again.')).toBeInTheDocument()
  })

  it('says that a partner who has not signed in yet has nothing to connect', async () => {
    serverWith(mail({ partner: { joined: false, state: 'NOT_CONNECTED', lastLookAt: null } }))
    open('/mail')

    expect(await screen.findByText(/Piotr has not signed in to the planner yet/)).toBeInTheDocument()
  })

  it('does not show a partner while the import is not set up', async () => {
    serverWith(mail({ setUp: false }))
    open('/mail')

    await screen.findByText(/not set up on this server/)
    expect(screen.queryByRole('region', { name: 'Piotr' })).toBeNull()
  })

  it('says so when the server cannot be reached, and tries again', async () => {
    const server = serverWith(mail(), { 'GET /api/mail': new TypeError('Failed to fetch') })
    open('/mail')

    expect(await screen.findByRole('alert')).toHaveTextContent('could not be loaded')
    server.answer('GET /api/mail', () => Response.json(mail()))
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('button', { name: 'Connect Gmail' })).toBeInTheDocument()
  })
})

describe('connecting', () => {
  it('sends one request and then the browser to Google', async () => {
    const goTo = vi.spyOn(browser, 'goTo').mockImplementation(() => undefined)
    const server = serverWith(mail(), { 'POST /api/mail/gmail/connection': Response.json({ authorizationUrl: GOOGLE }) })
    open('/mail')

    const button = await screen.findByRole('button', { name: 'Connect Gmail' })
    await userEvent.dblClick(button)

    await waitFor(() => expect(goTo).toHaveBeenCalledWith(GOOGLE))
    expect(goTo).toHaveBeenCalledTimes(1)
    expect(server.requests('POST /api/mail/gmail/connection')).toHaveLength(1)
    expect(screen.getByRole('button', { name: 'Connecting…' })).toBeDisabled()
  })

  it('says it is on its way while the server answers', async () => {
    vi.spyOn(browser, 'goTo').mockImplementation(() => undefined)
    let answer: (response: Response) => void = () => undefined
    serverWith(mail(), { 'POST /api/mail/gmail/connection': () => new Promise<Response>((resolve) => (answer = resolve)) })
    open('/mail')

    await userEvent.click(await screen.findByRole('button', { name: 'Connect Gmail' }))
    expect(screen.getByRole('button', { name: 'Connecting…' })).toBeDisabled()
    answer(Response.json({ authorizationUrl: GOOGLE }))
  })

  it.each([
    ['http://accounts.google.com/o/oauth2/v2/auth', 'plain http'],
    ['https://accounts.google.com.evil.example/auth', 'a look-alike host'],
    ['https://evil.example/accounts.google.com', 'another host'],
    ['https://accounts.google.com@evil.example/auth', 'a host hidden behind a name'],
    ['https://accounts.google.com:8443/auth', 'another port'],
    ['javascript:alert(1)', 'a script'],
    ['/relative/path', 'no host at all'],
    ['', 'nothing'],
  ])('sends the browser nowhere for %s (%s)', async (address) => {
    const goTo = vi.spyOn(browser, 'goTo').mockImplementation(() => undefined)
    serverWith(mail(), { 'POST /api/mail/gmail/connection': Response.json({ authorizationUrl: address }) })
    open('/mail')

    await userEvent.click(await screen.findByRole('button', { name: 'Connect Gmail' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('That did not work. Nothing was connected.')
    expect(goTo).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Connect Gmail' })).toBeEnabled()
  })

  it('says so when the server cannot be reached, and lets it be tried again', async () => {
    const goTo = vi.spyOn(browser, 'goTo').mockImplementation(() => undefined)
    const server = serverWith(mail(), { 'POST /api/mail/gmail/connection': new TypeError('Failed to fetch') })
    open('/mail')

    await userEvent.click(await screen.findByRole('button', { name: 'Connect Gmail' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('The server cannot be reached, so nothing was changed.')
    expect(goTo).not.toHaveBeenCalled()

    server.answer('POST /api/mail/gmail/connection', () => Response.json({ authorizationUrl: GOOGLE }))
    await userEvent.click(screen.getByRole('button', { name: 'Connect Gmail' }))
    await waitFor(() => expect(goTo).toHaveBeenCalledWith(GOOGLE))
  })

  it('reads the page again when the import turns out not to be set up', async () => {
    const server = serverWith(mail(), { 'POST /api/mail/gmail/connection': problem(409, 'mail-not-set-up') })
    open('/mail')

    const button = await screen.findByRole('button', { name: 'Connect Gmail' })
    server.answer('GET /api/mail', () => Response.json(mail({ setUp: false })))
    await userEvent.click(button)
    expect(await screen.findByText(/not set up on this server/)).toBeInTheDocument()
  })
})

describe('coming back from Google', () => {
  it.each([
    ['yes', 'status', 'Your Gmail is connected.'],
    ['refused', 'alert', 'You did not allow it at Google. Nothing was connected.'],
    ['failed', 'alert', 'That did not work. Nothing was connected.'],
  ])('says what happened for %s, once, with the keyboard on it', async (word, role, text) => {
    serverWith(mail())
    open(`/mail?connected=${word}`)

    expect(await screen.findByText(text)).toHaveRole(role)
    expect(screen.getByRole('group', { name: 'What happened' })).toHaveFocus()
    await waitFor(() => expect(window.location.search).toBe(''))
    expect(window.location.pathname).toBe('/mail')
    // The page still says it: the address lost the word, the page did not.
    expect(screen.getByText(text)).toBeInTheDocument()
  })

  it('keeps the other parts of the address', async () => {
    serverWith(mail())
    open('/mail?connected=yes&other=1')

    await screen.findByText('Your Gmail is connected.')
    await waitFor(() => expect(window.location.search).toBe('?other=1'))
  })

  it('ignores any other word', async () => {
    serverWith(mail())
    open('/mail?connected=maybe')

    await screen.findByRole('button', { name: 'Connect Gmail' })
    expect(screen.queryByRole('group', { name: 'What happened' })).toBeNull()
    await waitFor(() => expect(window.location.search).toBe(''))
  })

  it('shows the state the server has now, beside what was said', async () => {
    serverWith(mail({ yours: CONNECTED }))
    open('/mail?connected=yes')

    expect(await screen.findByText('Your Gmail is connected.')).toBeInTheDocument()
    expect(await screen.findByText('anna@planner.example')).toBeInTheDocument()
  })
})

describe('disconnecting', () => {
  it('asks first, saying what it removes, and sends nothing until it is confirmed', async () => {
    const server = serverWith(mail({ yours: CONNECTED }), { 'DELETE /api/mail/gmail/connection': new Response(null, { status: 204 }) })
    open('/mail')

    await userEvent.click(await screen.findByRole('button', { name: 'Disconnect' }))

    const question = screen.getByRole('group', { name: 'Disconnect' })
    expect(question).toHaveTextContent('permission is withdrawn at Google')
    expect(question).toHaveTextContent('its token and the list of messages it has noted are removed')
    expect(question).toHaveTextContent('Suggestions that already exist stay')
    expect(server.requests('DELETE /api/mail/gmail/connection')).toHaveLength(0)
  })

  it('can be called off', async () => {
    const server = serverWith(mail({ yours: CONNECTED }), { 'DELETE /api/mail/gmail/connection': new Response(null, { status: 204 }) })
    open('/mail')

    await userEvent.click(await screen.findByRole('button', { name: 'Disconnect' }))
    await userEvent.click(screen.getByRole('button', { name: 'Do not disconnect' }))

    expect(screen.queryByRole('group', { name: 'Disconnect' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Disconnect' })).toBeInTheDocument()
    expect(server.requests('DELETE /api/mail/gmail/connection')).toHaveLength(0)
  })

  it('sends DELETE once, then shows the new state and says so', async () => {
    const server = serverWith(mail({ yours: CONNECTED }), {
      'DELETE /api/mail/gmail/connection': () => {
        server.answer('GET /api/mail', () => Response.json(mail()))
        return new Response(null, { status: 204 })
      },
    })
    open('/mail')

    await userEvent.click(await screen.findByRole('button', { name: 'Disconnect' }))
    await userEvent.click(screen.getByRole('button', { name: 'Yes, disconnect' }))

    expect(await screen.findByRole('button', { name: 'Connect Gmail' })).toBeInTheDocument()
    expect(server.requests('DELETE /api/mail/gmail/connection')).toHaveLength(1)
    expect(screen.getByRole('status', { name: '' })).toHaveTextContent('Your Gmail is disconnected.')
    expect(screen.getByRole('group', { name: 'What happened' })).toHaveFocus()
    expect(screen.queryByRole('group', { name: 'Disconnect' })).toBeNull()
  })

  it('says so when the server cannot be reached, and keeps the question', async () => {
    serverWith(mail({ yours: CONNECTED }), { 'DELETE /api/mail/gmail/connection': new TypeError('Failed to fetch') })
    open('/mail')

    await userEvent.click(await screen.findByRole('button', { name: 'Disconnect' }))
    await userEvent.click(screen.getByRole('button', { name: 'Yes, disconnect' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('nothing was changed')
    expect(screen.getByRole('group', { name: 'Disconnect' })).toBeInTheDocument()
  })

  it('reads the page again when the import turns out not to be set up', async () => {
    const server = serverWith(mail({ yours: CONNECTED }), { 'DELETE /api/mail/gmail/connection': problem(409, 'mail-not-set-up') })
    open('/mail')

    await userEvent.click(await screen.findByRole('button', { name: 'Disconnect' }))
    server.answer('GET /api/mail', () => Response.json(mail({ setUp: false })))
    await userEvent.click(screen.getByRole('button', { name: 'Yes, disconnect' }))

    expect(await screen.findByText(/not set up on this server/)).toBeInTheDocument()
  })
})

describe('the ways to the page', () => {
  it('has no entry of its own in the top bar', async () => {
    serverWith(mail())
    open('/mail')

    await screen.findByRole('heading', { name: 'E-mail import' })
    const bar = screen.getByRole('navigation')
    expect(within(bar).queryByRole('link', { name: /e-?mail/i })).toBeNull()
    expect(within(bar).getAllByRole('link')).toHaveLength(8)
  })

  it('is reached from the suggestions inbox', async () => {
    serverWith(mail())
    open('/suggestions')

    await userEvent.click(await screen.findByRole('link', { name: 'E-mail import' }))

    expect(await screen.findByRole('heading', { name: 'E-mail import' })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/mail')
  })

  it('is offered on Home while the mail is not connected', async () => {
    serverWith(mail(), { 'GET /api/home': home() })
    open('/')

    const link = await screen.findByRole('link', { name: /Connect your e-mail/ })
    expect(link).toHaveAttribute('href', '/mail')
  })

  it('is offered on Home when it has to be connected again', async () => {
    serverWith(mail({ yours: { ...CONNECTED, state: 'NEEDS_RECONNECTING' } }))
    open('/')

    expect(await screen.findByRole('link', { name: 'Your e-mail has to be connected again' })).toHaveAttribute('href', '/mail')
  })

  it('is not mentioned on Home when the mail is connected', async () => {
    const server = serverWith(mail({ yours: CONNECTED }))
    open('/')

    await screen.findByRole('heading', { name: 'Anna & Piotr' })
    await waitFor(() => expect(server.calls).toContain('GET /api/mail'))
    await waitFor(() => expect(screen.queryByText(/Loading/)).toBeNull())
    expect(screen.queryByRole('link', { name: /e-mail/i })).toBeNull()
  })

  it('is not mentioned on Home while the import is not set up', async () => {
    const server = serverWith(mail({ setUp: false }))
    open('/')

    await screen.findByRole('heading', { name: 'Anna & Piotr' })
    await waitFor(() => expect(server.calls).toContain('GET /api/mail'))
    await waitFor(() => expect(screen.queryByText(/Loading/)).toBeNull())
    expect(screen.queryByRole('link', { name: /e-mail/i })).toBeNull()
  })

  it('is not mentioned on Home when the server cannot say', async () => {
    const server = serverWith(mail(), { 'GET /api/mail': new TypeError('Failed to fetch') })
    open('/')

    await screen.findByRole('heading', { name: 'Anna & Piotr' })
    await waitFor(() => expect(server.calls).toContain('GET /api/mail'))
    await waitFor(() => expect(screen.queryByText(/Loading/)).toBeNull())
    expect(screen.queryByRole('link', { name: /e-mail/i })).toBeNull()
  })
})
