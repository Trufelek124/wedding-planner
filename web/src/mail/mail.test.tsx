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

type Reading = MailState['reading']

/** Nothing read, reading not set up: what a new server says. */
const QUIET: Reading = {
  state: 'OFF',
  waiting: 0,
  read: 0,
  aboutTheWedding: 0,
  couldNotBeRead: 0,
  suggestions: 0,
  costThisMonthCents: 0,
  costInAllCents: 0,
  monthlyLimitCents: 2000,
}

/** Reading under way, with believable numbers. */
const READ: Reading = {
  state: 'READING',
  waiting: 128,
  read: 47,
  aboutTheWedding: 12,
  couldNotBeRead: 3,
  suggestions: 9,
  costThisMonthCents: 460,
  costInAllCents: 1234,
  monthlyLimitCents: 2000,
}

function mail(overrides: Partial<MailState> = {}): MailState {
  return {
    setUp: true,
    yours: NOT_CONNECTED,
    partner: { joined: true, state: 'NOT_CONNECTED', lastLookAt: null },
    reading: QUIET,
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

    expect(await screen.findByText(/The e-mail import is not set up on this server/)).toBeInTheDocument()
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
    expect(list).toHaveTextContent('Nothing is sent to an AI service on this server yet')
    expect(list).toHaveTextContent('not verified')
    expect(list).toHaveTextContent('“Advanced”')
    expect(list).not.toHaveTextContent('Reading them comes later')
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

    await screen.findByText(/The e-mail import is not set up on this server/)
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

describe('what connecting means, with the reading on', () => {
  it('says that the sender, the subject and the beginning are sent, and a message about the wedding in full', async () => {
    serverWith(mail({ reading: READ }))
    open('/mail')

    const list = await screen.findByRole('list', { name: 'What connecting means' })
    expect(list).toHaveTextContent('The sender, the subject and the beginning of every message are sent to an AI service')
    expect(list).toHaveTextContent('A message that does is sent in full')
    expect(list).toHaveTextContent('Attachments are never sent')
    expect(list).not.toHaveTextContent('Nothing is sent to an AI service')
    expect(list).not.toHaveTextContent('will be sent')
  })

  it('says what would be sent, and that nothing is yet, while the reading is off', async () => {
    serverWith(mail({ reading: QUIET }))
    open('/mail')

    const list = await screen.findByRole('list', { name: 'What connecting means' })
    expect(list).toHaveTextContent('Nothing is sent to an AI service on this server yet.')
    expect(list).toHaveTextContent('will be sent to it')
    expect(list).toHaveTextContent('a message that does will be sent in full')
    expect(list).toHaveTextContent('Attachments are never sent')
  })
})

describe('reading your mail', () => {
  function part() {
    return within(screen.getByRole('region', { name: 'Reading your mail' }))
  }

  it.each([
    ['OFF', /reads none of them yet: reading is not set up on this server\. Nothing is sent to the AI service\./],
    ['READING', /The planner is reading your mail, the newest first, a few messages at a time\./],
    ['WAITING_FOR_THE_SERVICE', /The AI service is busy just now\. The reading goes on by itself\./],
    ['MONTHLY_LIMIT', /This month’s limit of \$20\.00 is reached\. The reading goes on next month\./],
    ['DAILY_LIMIT', /Today’s limit of messages is reached\. The reading goes on tomorrow\./],
  ] as const)('says %s in words, never by its name', async (state, words) => {
    serverWith(mail({ reading: { ...READ, state, read: 5 } }))
    open('/mail')

    const reading = await screen.findByRole('region', { name: 'Reading your mail' })
    expect(reading).toHaveTextContent(words)
    expect(reading.textContent).not.toMatch(/WAITING_FOR|MONTHLY_LIMIT|DAILY_LIMIT|KEY_NOT|READING|\bOFF\b/)
  })

  it('says the limit as the server has it, not as 20 dollars', async () => {
    serverWith(mail({ reading: { ...READ, state: 'MONTHLY_LIMIT', monthlyLimitCents: 3550 } }))
    open('/mail')

    expect(await screen.findByText(/This month’s limit of \$35\.50 is reached/)).toBeInTheDocument()
  })

  it('warns, in words, that the AI service does not accept the key and the reading has stopped', async () => {
    serverWith(mail({ reading: { ...READ, state: 'KEY_NOT_ACCEPTED' } }))
    open('/mail')

    const warning = await screen.findByText(/does not accept the server’s key/)
    expect(warning).toHaveRole('status')
    expect(warning).toHaveClass('alert')
    expect(warning).toHaveTextContent('the reading has stopped')
    expect(warning).toHaveTextContent('starts again when the server is started with a key that works')
    expect(warning.querySelector('svg.warning-sign')).not.toBeNull()
    expect(warning.querySelector('svg.warning-sign')).toHaveAttribute('aria-hidden', 'true')
  })

  it('shows no warning while the key is accepted', async () => {
    serverWith(mail({ reading: READ }))
    open('/mail')

    await screen.findByRole('region', { name: 'Reading your mail' })
    expect(screen.queryByText(/does not accept/)).toBeNull()
  })

  it('shows the numbers as short sentences, and the suggestions as a link to the inbox', async () => {
    serverWith(mail({ reading: READ }))
    open('/mail')

    const numbers = within(await screen.findByRole('list', { name: 'What was read so far' }))
    expect(numbers.getByText('128 messages wait to be read.')).toBeInTheDocument()
    expect(numbers.getByText('47 messages were read.')).toBeInTheDocument()
    expect(numbers.getByText('12 of them concerned the wedding.')).toBeInTheDocument()
    expect(numbers.getByText('3 could not be read.')).toBeInTheDocument()
    expect(numbers.getByRole('link', { name: '9 suggestions' })).toHaveAttribute('href', '/suggestions')
  })

  it('leaves out the line of what could not be read when none could not', async () => {
    serverWith(mail({ reading: { ...READ, couldNotBeRead: 0 } }))
    open('/mail')

    await screen.findByRole('list', { name: 'What was read so far' })
    expect(part().queryByText(/could not be read/)).toBeNull()
    expect(part().queryByText(/None could not|0 could not/)).toBeNull()
  })

  it('says a zero as a word, and has no link when no suggestion came of it', async () => {
    serverWith(mail({ reading: { ...READ, waiting: 0, aboutTheWedding: 0, suggestions: 0 } }))
    open('/mail')

    const numbers = within(await screen.findByRole('list', { name: 'What was read so far' }))
    expect(numbers.getByText('No message waits to be read.')).toBeInTheDocument()
    expect(numbers.getByText('None of them concerned the wedding.')).toBeInTheDocument()
    expect(numbers.getByText('No suggestions came of it.')).toBeInTheDocument()
    expect(numbers.queryByRole('link')).toBeNull()
    expect(part().queryByText(/\b0 /)).toBeNull()
  })

  it('says one as one', async () => {
    serverWith(mail({ reading: { ...READ, waiting: 1, read: 2, aboutTheWedding: 1, couldNotBeRead: 1, suggestions: 1 } }))
    open('/mail')

    const numbers = within(await screen.findByRole('list', { name: 'What was read so far' }))
    expect(numbers.getByText('1 message waits to be read.')).toBeInTheDocument()
    expect(numbers.getByText('2 messages were read.')).toBeInTheDocument()
    expect(numbers.getByText('1 of them concerned the wedding.')).toBeInTheDocument()
    expect(numbers.getByRole('link', { name: '1 suggestion' })).toBeInTheDocument()
  })

  it('writes the estimated cost in dollars from cents, and says it is an estimate', async () => {
    serverWith(mail({ reading: { ...READ, costThisMonthCents: 460, costInAllCents: 123_456, monthlyLimitCents: 2000 } }))
    open('/mail')

    const cost = within(await screen.findByRole('heading', { name: 'Estimated cost' }).then((heading) => heading.parentElement!))
    expect(cost.getByText('This month').nextSibling).toHaveTextContent('$4.60')
    expect(cost.getByText('In all').nextSibling).toHaveTextContent('$1,234.56')
    expect(cost.getByText('Monthly limit').nextSibling).toHaveTextContent('$20.00')
    expect(part().getByText(/estimates/)).toBeInTheDocument()
  })

  it('writes a few cents with their leading zero, and nothing as $0.00', async () => {
    serverWith(mail({ reading: { ...READ, costThisMonthCents: 0, costInAllCents: 5 } }))
    open('/mail')

    expect((await screen.findByText('This month')).nextSibling).toHaveTextContent('$0.00')
    expect(screen.getByText('In all').nextSibling).toHaveTextContent('$0.05')
  })

  it('shows only what is said, not the numbers, while the reading is off and nothing was ever read', async () => {
    serverWith(mail({ reading: QUIET }))
    open('/mail')

    const reading = await screen.findByRole('region', { name: 'Reading your mail' })
    expect(reading).toHaveTextContent('reads none of them yet')
    expect(screen.queryByRole('list', { name: 'What was read so far' })).toBeNull()
    expect(screen.queryByRole('heading', { name: 'Estimated cost' })).toBeNull()
  })

  it('shows the numbers while the reading is off after something was read', async () => {
    serverWith(mail({ reading: { ...QUIET, read: 4, aboutTheWedding: 1, costInAllCents: 80 } }))
    open('/mail')

    expect(await screen.findByRole('list', { name: 'What was read so far' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Estimated cost' })).toBeInTheDocument()
  })

  it('shows the numbers and the cost while the reading is on and nothing was read yet', async () => {
    serverWith(mail({ reading: { ...QUIET, state: 'READING', waiting: 30 } }))
    open('/mail')

    const numbers = within(await screen.findByRole('list', { name: 'What was read so far' }))
    expect(numbers.getByText('30 messages wait to be read.')).toBeInTheDocument()
    expect(numbers.getByText('None has been read yet.')).toBeInTheDocument()
    expect(numbers.queryByText(/concerned the wedding/)).toBeNull()
    expect(screen.getByRole('heading', { name: 'Estimated cost' })).toBeInTheDocument()
  })

  it('is under the two mailboxes, and for both whoever is connected', async () => {
    serverWith(mail({ yours: CONNECTED, reading: READ }))
    open('/mail')

    const reading = await screen.findByRole('region', { name: 'Reading your mail' })
    const yours = screen.getByRole('region', { name: 'Your Gmail' })
    const partner = screen.getByRole('region', { name: 'Piotr' })
    expect(yours.compareDocumentPosition(reading) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(partner.compareDocumentPosition(reading) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(screen.queryByText('For now the planner only notes which messages there are.')).toBeNull()
  })

  it('is not there while the import is not set up', async () => {
    serverWith(mail({ setUp: false }))
    open('/mail')

    await screen.findByText(/The e-mail import is not set up on this server/)
    expect(screen.queryByRole('region', { name: 'Reading your mail' })).toBeNull()
    expect(screen.queryByText('Reading your mail')).toBeNull()
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
    expect(await screen.findByText(/The e-mail import is not set up on this server/)).toBeInTheDocument()
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
    // The keyboard is put there after the page is drawn: the text can be seen a moment before.
    await waitFor(() => expect(screen.getByRole('group', { name: 'What happened' })).toHaveFocus())
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

    expect(await screen.findByText(/The e-mail import is not set up on this server/)).toBeInTheDocument()
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

describe('how sure the import has to be before it adds by itself', () => {
  const settingsServer = (settings = { addsByItself: true, threshold: 85 }, extra: Record<string, Handler> = {}) =>
    serverWith(mail(), { 'GET /api/suggestions/settings': () => Response.json(settings), ...extra })

  it('is not there while the import is not set up', async () => {
    serverWith(mail({ setUp: false }))
    open('/mail')

    await screen.findByText(/The e-mail import is not set up on this server/)
    expect(screen.queryByRole('heading', { name: 'Adding by itself' })).not.toBeInTheDocument()
  })

  it('shows the wedding’s setting, says that both see it, and what is never added by itself', async () => {
    settingsServer()
    open('/mail')

    const section = within(await screen.findByRole('region', { name: 'Adding by itself' }))
    expect(await section.findByRole('radio', { name: 'Add tasks and steps by themselves when the import is at least this sure' })).toBeChecked()
    expect(section.getByRole('radio', { name: /^Never/ })).not.toBeChecked()
    expect(section.getByLabelText('At least this sure, in percent (50 to 100)')).toHaveValue('85')
    expect(section.getByText(/you and Piotr see and change the same one/)).toBeInTheDocument()
    expect(section.getByText(/A payment or a new vendor is never added by itself/)).toBeInTheDocument()
    expect(section.getByText(/it adds nothing that already waits and undoes nothing that was added/)).toBeInTheDocument()
  })

  it('sends both values, and says once that it was saved', async () => {
    const server = settingsServer(undefined, {
      'PUT /api/suggestions/settings': (request) => request.json().then((body) => Response.json(body)),
    })
    open('/mail')
    const field = await screen.findByLabelText('At least this sure, in percent (50 to 100)')

    await userEvent.clear(field)
    await userEvent.type(field, '70')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText('Saved. It applies from now on.')).toBeInTheDocument()
    expect(await server.requests('PUT /api/suggestions/settings')[0]?.json()).toEqual({ addsByItself: true, threshold: 70 })
    expect(server.requests('PUT /api/suggestions/settings')).toHaveLength(1)
    expect(screen.getAllByText('Saved. It applies from now on.')).toHaveLength(1)
  })

  it('puts the server’s message next to the number when it refuses it, and says nothing was saved', async () => {
    settingsServer(undefined, {
      'PUT /api/suggestions/settings': problem(400, 'bad-request', 'Bad request', {
        fieldProblems: [{ field: 'threshold', message: 'Choose a number from 50 to 100.' }],
      }),
    })
    open('/mail')
    const field = await screen.findByLabelText('At least this sure, in percent (50 to 100)')

    await userEvent.clear(field)
    await userEvent.type(field, '30')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText('Choose a number from 50 to 100.')).toBeInTheDocument()
    expect(field).toHaveAccessibleDescription('Choose a number from 50 to 100.')
    expect(screen.queryByText(/Saved\./)).not.toBeInTheDocument()
  })

  it('does not send what is not a number', async () => {
    const server = settingsServer()
    open('/mail')
    const field = await screen.findByLabelText('At least this sure, in percent (50 to 100)')

    await userEvent.clear(field)
    await userEvent.type(field, 'high')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText('Choose a number from 50 to 100.')).toBeInTheDocument()
    expect(server.requests('PUT /api/suggestions/settings')).toHaveLength(0)
  })

  it('switches to Never and keeps the number for when it is switched on again', async () => {
    const server = settingsServer({ addsByItself: true, threshold: 92 }, {
      'PUT /api/suggestions/settings': (request) => request.json().then((body) => Response.json(body)),
    })
    open('/mail')
    const field = await screen.findByLabelText('At least this sure, in percent (50 to 100)')

    await userEvent.click(screen.getByRole('radio', { name: /^Never/ }))
    expect(field).toBeDisabled()
    expect(field).toHaveValue('92')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText('Saved. It applies from now on.')).toBeInTheDocument()
    expect(await server.requests('PUT /api/suggestions/settings')[0]?.json()).toEqual({ addsByItself: false, threshold: 92 })
    expect(screen.getByLabelText('At least this sure, in percent (50 to 100)')).toHaveValue('92')
    expect(screen.getByRole('radio', { name: /^Never/ })).toBeChecked()
  })

  it('shows a setting that is on Never as it is, with the number that was kept', async () => {
    settingsServer({ addsByItself: false, threshold: 60 })
    open('/mail')

    expect(await screen.findByRole('radio', { name: /^Never/ })).toBeChecked()
    expect(screen.getByLabelText('At least this sure, in percent (50 to 100)')).toHaveValue('60')
  })

  it('says so when the server cannot be reached, and changes nothing', async () => {
    settingsServer(undefined, { 'PUT /api/suggestions/settings': new TypeError('Failed to fetch') })
    open('/mail')

    await userEvent.click(await screen.findByRole('button', { name: 'Save' }))

    expect(await screen.findByText('The server cannot be reached, so nothing was changed. Try again.')).toBeInTheDocument()
    expect(screen.queryByText(/Saved\./)).not.toBeInTheDocument()
  })
})
