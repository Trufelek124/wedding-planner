import { createContext, useContext } from 'react'
import type { Wedding } from './weddingApi.ts'

export const WeddingContext = createContext<Wedding | null>(null)

/** The wedding of the signed-in person. Every page inside the shell has one. */
export function useCurrentWedding(): Wedding {
  const wedding = useContext(WeddingContext)
  if (wedding === null) {
    throw new Error('This page needs a wedding: render it inside the shell')
  }
  return wedding
}

/** How a page tells the rest of the app that it changed the wedding. */
export const WeddingUpdateContext = createContext<((wedding: Wedding) => void) | null>(null)

export function useWeddingUpdate(): (wedding: Wedding) => void {
  const update = useContext(WeddingUpdateContext)
  if (update === null) {
    throw new Error('This page needs a wedding: render it inside the shell')
  }
  return update
}

/** "Anna", "Anna & Piotr", or nothing for nobody. */
export function namesOf(wedding: Wedding, memberIds: readonly string[]): string {
  return wedding.members
    .filter((member) => memberIds.includes(member.id))
    .map((member) => member.displayName)
    .join(' & ')
}
