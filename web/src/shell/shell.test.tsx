import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '../App.tsx'
import type { Schemas } from '../api/client.ts'
import { planningServer, suggestion } from '../test-server.ts'

type Suggestion = Schemas['Suggestion']

function open(path: string) {
  window.history.pushState(null, '', path)
  render(<App />)
}

/** Answers the suggestions by the state that was asked for: the ones that wait, and the ones added by themselves. */
function serving(waiting: Suggestion[], added: Suggestion[]) {
  return (request: Request) =>
    Response.json(new URL(request.url).searchParams.get('state') === 'ADDED' ? added.map((one) => ({ ...one, state: 'ADDED' })) : waiting)
}

// jsdom has no scrollIntoView; the shell asks for it when it is there.
const asked: Element[] = []
Element.prototype.scrollIntoView = function scrollIntoView(this: Element) {
  asked.push(this)
}

afterEach(() => {
  asked.length = 0
  vi.restoreAllMocks()
})

describe('the top bar', () => {
  it('lists the eight pages as links, in their order', async () => {
    planningServer([], {})
    open('/')

    const nav = await screen.findByRole('navigation', { name: 'Main' })
    const links = within(nav).getAllByRole('link')
    expect(links.map((link) => link.textContent)).toEqual([
      'Home',
      'Plan',
      'Tasks',
      'Inspirations',
      'Budget',
      'Vendors',
      'Guests',
      'Suggestions',
    ])
    expect(within(nav).getAllByRole('listitem')).toHaveLength(8)
    expect(screen.queryByRole('button', { name: /menu/i })).not.toBeInTheDocument()
  })

  it('brings the entry of the shown page into view, at first and when the page changes', async () => {
    planningServer([], {})
    open('/suggestions')

    const nav = await screen.findByRole('navigation', { name: 'Main' })
    const suggestions = within(nav).getByRole('link', { name: 'Suggestions' })
    expect(asked).toContain(suggestions)

    await userEvent.click(within(nav).getByRole('link', { name: 'Plan' }))
    expect(asked.at(-1)).toBe(within(nav).getByRole('link', { name: 'Plan' }))
  })

  it('says for a screen reader how many suggestions wait, and writes the count beside the name', async () => {
    planningServer([], { 'GET /api/suggestions': serving([suggestion({}), suggestion({}), suggestion({}), suggestion({})], []) })
    open('/')

    const link = await screen.findByRole('link', { name: 'Suggestions, 4 waiting' })
    expect(link).toHaveTextContent('Suggestions4, 4 waiting')
  })

  it('says only how many were added, when none wait, and never adds the two together', async () => {
    planningServer([], { 'GET /api/suggestions': serving([], [suggestion({}), suggestion({})]) })
    open('/')

    const link = await screen.findByRole('link', { name: 'Suggestions, 2 added by themselves' })
    expect(link).toHaveTextContent('Suggestions+2, 2 added by themselves')
    expect(link).not.toHaveTextContent('waiting')
  })

  it('shows no mark when nothing waits and nothing was added', async () => {
    const server = planningServer([], {})
    open('/')

    const nav = await screen.findByRole('navigation', { name: 'Main' })
    // Both counts were asked for, and both were nothing.
    await waitFor(() => expect(server.requests('GET /api/suggestions').map((one) => new URL(one.url).searchParams.get('state')).sort()).toEqual(['ADDED', 'WAITING']))
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(within(nav).getByRole('link', { name: 'Suggestions' })).toHaveTextContent(/^Suggestions$/)
  })

  it('names who is signed in, with the words a screen reader needs', async () => {
    planningServer([], {})
    open('/')

    expect(await screen.findByRole('banner')).toHaveTextContent('Signed in as Anna')
  })
})
