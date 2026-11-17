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

/** "Anna", "Anna & Piotr", or nothing for nobody. */
export function namesOf(wedding: Wedding, memberIds: readonly string[]): string {
  return wedding.members
    .filter((member) => memberIds.includes(member.id))
    .map((member) => member.displayName)
    .join(' & ')
}
