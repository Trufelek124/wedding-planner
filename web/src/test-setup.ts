import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, beforeEach, vi } from 'vitest'
import { resetSession } from './auth/session.ts'

beforeEach(() => {
  // jsdom lays nothing out and cannot scroll. A test can still ask whether a page asked for its top.
  vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined)
})

afterEach(() => {
  cleanup()
  resetSession()
  // Each test starts on the home page, whatever page the last one ended on.
  window.history.replaceState(null, '', '/')
})
