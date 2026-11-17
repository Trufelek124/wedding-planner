import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import App from './App.tsx'
import { fakeServer, problem, session, wedding } from './test-server.ts'

const signedOut = problem(401, 'unauthenticated', 'Sign-in required')
const notSetUp = problem(404, 'wedding-not-set-up', 'The wedding is not set up yet')
const devLoginOnly = Response.json({ googleClientId: null, devLoginEnabled: true })

/** A server where nobody is signed in yet and the development login is on. */
function freshServer(extra: Parameters<typeof fakeServer>[0] = {}) {
  return fakeServer({
    'POST /api/auth/refresh': signedOut,
    'GET /api/auth/config': devLoginOnly,
    'POST /api/auth/dev': session(),
    'GET /api/wedding': notSetUp,
    ...extra,
  })
}

async function signInAsAnna() {
  await userEvent.type(await screen.findByLabelText('Development sign-in'), 'anna@example.com')
  await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))
}

describe('the planner, from a signed-out visitor to the home page', () => {
  it('shows a visitor nothing but the sign-in', async () => {
    const server = freshServer()

    render(<App />)

    expect(await screen.findByRole('heading', { name: 'Wedding planner' })).toBeInTheDocument()
    expect(screen.getByText('Sign in to open your plan.')).toBeInTheDocument()
    expect(server.calls).not.toContain('GET /api/wedding')
  })

  it('leads a new owner from sign-in through the setup to the home page', async () => {
    const server = freshServer({
      'POST /api/wedding': () => wedding({ date: '2027-06-19' }),
    })
    render(<App />)

    await signInAsAnna()

    expect(await screen.findByRole('heading', { name: 'Set up your wedding' })).toBeInTheDocument()
    expect(screen.getByLabelText('Your name')).toHaveValue('Anna')
    await userEvent.type(screen.getByLabelText('Your partner’s name'), 'Piotr')
    await userEvent.type(screen.getByLabelText(/Wedding day/), '2027-06-19')
    await userEvent.selectOptions(screen.getByLabelText('Time zone'), 'Europe/Warsaw')
    await userEvent.click(screen.getByRole('button', { name: 'Save and continue' }))

    expect(await screen.findByRole('heading', { name: 'Anna & Piotr' })).toBeInTheDocument()
    expect(screen.getByText('Saturday 19 June 2027')).toBeInTheDocument()
    expect(screen.getByText('Signed in as Anna')).toBeInTheDocument()
    await expect(server.requests('POST /api/wedding')[0]?.json()).resolves.toEqual({
      yourName: 'Anna',
      partnerName: 'Piotr',
      date: '2027-06-19',
      currency: 'PLN',
      timeZone: 'Europe/Warsaw',
    })
  })

  it('sends an open wedding day as "not fixed", not as an empty text', async () => {
    const server = freshServer({ 'POST /api/wedding': () => wedding({ date: null }) })
    render(<App />)
    await signInAsAnna()

    await userEvent.type(await screen.findByLabelText('Your partner’s name'), 'Piotr')
    await userEvent.click(screen.getByRole('button', { name: 'Save and continue' }))

    expect(await screen.findByText('The day is not fixed yet.')).toBeInTheDocument()
    await expect(server.requests('POST /api/wedding')[0]?.json()).resolves.toMatchObject({ date: null })
  })

  it('takes someone who is still signed in straight to their wedding', async () => {
    fakeServer({ 'POST /api/auth/refresh': session(), 'GET /api/wedding': wedding() })

    render(<App />)

    expect(await screen.findByRole('heading', { name: 'Anna & Piotr' })).toBeInTheDocument()
    expect(screen.queryByText('Sign in to open your plan.')).not.toBeInTheDocument()
  })

  it('tells a stranger that this account cannot open the planner', async () => {
    freshServer({ 'POST /api/auth/dev': problem(403, 'account-not-allowed', 'This account may not use this planner') })
    render(<App />)

    await signInAsAnna()

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'This account cannot open this planner. Sign in with the account you use for the wedding.',
    )
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeEnabled()
  })

  it('says next to each field what the server found wrong with it', async () => {
    freshServer({
      'POST /api/wedding': problem(400, 'bad-request', 'The request is not valid', {
        fieldProblems: [
          { field: 'partnerName', message: 'must not be blank' },
          { field: 'timeZone', message: 'Use a time zone name such as Europe/Warsaw.' },
        ],
      }),
    })
    render(<App />)
    await signInAsAnna()

    await userEvent.click(await screen.findByRole('button', { name: 'Save and continue' }))

    const partner = await screen.findByLabelText('Your partner’s name')
    await waitFor(() => expect(partner).toBeInvalid())
    expect(partner).toHaveAccessibleDescription('must not be blank')
    expect(screen.getByLabelText('Time zone')).toHaveAccessibleDescription(
      'Where the wedding takes place. Use a time zone name such as Europe/Warsaw.',
    )
    expect(screen.getByLabelText('Your name')).toBeValid()
    expect(screen.getByRole('button', { name: 'Save and continue' })).toBeEnabled()
  })

  it('shows the wedding the other owner set up in the meantime instead of an error', async () => {
    const server = freshServer({
      'POST /api/wedding': () => {
        server.answer('GET /api/wedding', wedding({ members: [
          { id: 'm1', displayName: 'Piotr', you: false, signedIn: true },
          { id: 'm2', displayName: 'Anna', you: true, signedIn: true },
        ] }))
        return problem(409, 'wedding-already-set-up', 'The wedding is already set up')
      },
    })
    render(<App />)
    await signInAsAnna()

    await userEvent.type(await screen.findByLabelText('Your partner’s name'), 'Piotr')
    await userEvent.click(screen.getByRole('button', { name: 'Save and continue' }))

    expect(await screen.findByRole('heading', { name: 'Piotr & Anna' })).toBeInTheDocument()
  })

  it('signs out back to the sign-in', async () => {
    const server = fakeServer({
      'POST /api/auth/refresh': session(),
      'GET /api/wedding': wedding(),
      'POST /api/auth/sign-out': new Response(null, { status: 204 }),
      'GET /api/auth/config': devLoginOnly,
    })
    render(<App />)

    await userEvent.click(await screen.findByRole('button', { name: 'Sign out' }))

    expect(await screen.findByText('Sign in to open your plan.')).toBeInTheDocument()
    expect(server.calls).toContain('POST /api/auth/sign-out')
    expect(screen.queryByText('Anna & Piotr')).not.toBeInTheDocument()
  })

  it('offers to try again when the server cannot be reached', async () => {
    const server = fakeServer({})
    render(<App />)

    expect(await screen.findByRole('alert')).toHaveTextContent('The server cannot be reached.')
    server.answer('POST /api/auth/refresh', session())
    server.answer('GET /api/wedding', wedding())
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))

    expect(await screen.findByRole('heading', { name: 'Anna & Piotr' })).toBeInTheDocument()
  })

  it('tells a third person that the planner is taken', async () => {
    fakeServer({
      'POST /api/auth/refresh': session(),
      'GET /api/wedding': problem(403, 'wedding-full', 'The wedding already has its two members'),
    })

    render(<App />)

    expect(await screen.findByRole('alert')).toHaveTextContent('This planner already belongs to two people')
  })

  it('says so when no way of signing in is set up', async () => {
    fakeServer({
      'POST /api/auth/refresh': signedOut,
      'GET /api/auth/config': Response.json({ googleClientId: null, devLoginEnabled: false }),
    })

    render(<App />)

    expect(await screen.findByRole('alert')).toHaveTextContent('Signing in is not set up on this server yet.')
  })
})

describe('signing in with Google', () => {
  it("hosts Google's button and hands the token Google issues to the server", async () => {
    const initialize = vi.fn()
    const renderButton = vi.fn()
    const disableAutoSelect = vi.fn()
    vi.stubGlobal('google', { accounts: { id: { initialize, renderButton, disableAutoSelect } } })
    const server = freshServer({
      'GET /api/auth/config': Response.json({ googleClientId: 'client-123.apps.googleusercontent.com', devLoginEnabled: false }),
      'POST /api/auth/google': session(),
      'GET /api/wedding': wedding(),
      'POST /api/auth/sign-out': new Response(null, { status: 204 }),
    })
    render(<App />)

    // The library is a script from Google; the test stands in for its arrival.
    // The button asks for it just after it appears, so the script is waited for and not looked up at once.
    await screen.findByTestId('google-button')
    const library = () => document.querySelector<HTMLScriptElement>('script[src="https://accounts.google.com/gsi/client"]')
    await waitFor(() => expect(library()).not.toBeNull())
    library()?.dispatchEvent(new Event('load'))

    await waitFor(() => expect(renderButton).toHaveBeenCalled())
    const config = initialize.mock.calls[0]?.[0] as google.accounts.id.IdConfiguration
    expect(config.client_id).toBe('client-123.apps.googleusercontent.com')
    expect(config.auto_select).toBe(false)
    expect(renderButton.mock.calls[0]?.[0]).toBe(screen.getByTestId('google-button'))

    config.callback?.({ credential: 'the-id-token', select_by: 'btn' })

    expect(await screen.findByRole('heading', { name: 'Anna & Piotr' })).toBeInTheDocument()
    await expect(server.requests('POST /api/auth/google')[0]?.json()).resolves.toEqual({ idToken: 'the-id-token' })

    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }))
    await screen.findByText('Sign in to open your plan.')
    expect(disableAutoSelect).toHaveBeenCalled()
  })
})
