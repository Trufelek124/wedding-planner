import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import App from './App.tsx'

function backendAnswers(response: Response | Error) {
  const fetchMock = vi.fn<typeof fetch>(() =>
    response instanceof Error ? Promise.reject(response) : Promise.resolve(response),
  )
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

describe('App', () => {
  it('says it is checking until the server has answered', () => {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>(() => new Promise<Response>(() => {})))

    render(<App />)

    expect(screen.getByRole('status')).toHaveTextContent('Checking the server…')
  })

  it('says the server is running when health reports UP', async () => {
    const fetchMock = backendAnswers(Response.json({ status: 'UP' }))

    render(<App />)

    expect(await screen.findByText('The server is running.')).toBeInTheDocument()
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/actuator/health')
  })

  it('says the server cannot be reached when health answers 503', async () => {
    backendAnswers(Response.json({ status: 'DOWN' }, { status: 503 }))

    render(<App />)

    expect(await screen.findByText('The server cannot be reached.')).toBeInTheDocument()
  })

  it('says the server cannot be reached when the request fails', async () => {
    backendAnswers(new TypeError('Failed to fetch'))

    render(<App />)

    expect(await screen.findByText('The server cannot be reached.')).toBeInTheDocument()
  })

  it('does not take an answer without status UP for a running server', async () => {
    backendAnswers(new Response('<html>a proxy error page</html>', { status: 200 }))

    render(<App />)

    expect(await screen.findByText('The server cannot be reached.')).toBeInTheDocument()
  })
})
