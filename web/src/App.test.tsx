import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import App from './App.tsx'
import { serverAnswers } from './test-server.ts'

const healthy = { '/actuator/health': Response.json({ status: 'UP' }) }

describe('App', () => {
  it('says it is checking until the server has answered', () => {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>(() => new Promise<Response>(() => {})))

    render(<App />)

    expect(screen.getByRole('status')).toHaveTextContent('Checking the server…')
  })

  it('says the server is running when health reports UP', async () => {
    const fetchMock = serverAnswers(healthy)

    render(<App />)

    expect(await screen.findByText('The server is running.')).toBeInTheDocument()
    expect(fetchMock.mock.calls.map(([input]) => (input instanceof Request ? input.url : input))).toContain(
      '/actuator/health',
    )
  })

  it("shows the server's version once the server has told it", async () => {
    serverAnswers({ ...healthy, '/api/app-info': Response.json({ name: 'wedding-planner', version: '1.2.3' }) })

    render(<App />)

    expect(await screen.findByText('Server version 1.2.3')).toBeInTheDocument()
  })

  it('shows no version when the server does not tell one', async () => {
    serverAnswers(healthy)

    render(<App />)

    expect(await screen.findByText('The server is running.')).toBeInTheDocument()
    expect(screen.queryByText(/Server version/)).not.toBeInTheDocument()
  })

  it('says the server cannot be reached when health answers 503', async () => {
    serverAnswers({ '/actuator/health': Response.json({ status: 'DOWN' }, { status: 503 }) })

    render(<App />)

    expect(await screen.findByText('The server cannot be reached.')).toBeInTheDocument()
  })

  it('says the server cannot be reached when the request fails', async () => {
    serverAnswers({})

    render(<App />)

    expect(await screen.findByText('The server cannot be reached.')).toBeInTheDocument()
  })

  it('does not take an answer without status UP for a running server', async () => {
    serverAnswers({ '/actuator/health': new Response('<html>a proxy error page</html>', { status: 200 }) })

    render(<App />)

    expect(await screen.findByText('The server cannot be reached.')).toBeInTheDocument()
  })
})
