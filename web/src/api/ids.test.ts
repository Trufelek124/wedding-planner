import { describe, expect, it } from 'vitest'
import { newId } from './ids.ts'

describe('an id for something new', () => {
  it('is a random UUID as the server reads one', () => {
    expect(newId()).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
  })

  it('is another one each time', () => {
    const ids = new Set(Array.from({ length: 200 }, newId))
    expect(ids.size).toBe(200)
  })
})
