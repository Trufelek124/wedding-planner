import { createContext, useContext } from 'react'

/**
 * How many suggestions wait, and how many were added by themselves and not looked at yet, as the shell knows them;
 * null while that is not known. They ask different things of a person (decide, have a look) and are never added up.
 * A page that decides one tells it to read again.
 */
export interface Waiting {
  count: number | null
  added: number | null
  reload: () => void
}

export const WaitingContext = createContext<Waiting>({ count: null, added: null, reload: () => undefined })

export function useWaiting(): Waiting {
  return useContext(WaitingContext)
}

/** "1 suggestion waits for you", "3 suggestions wait for you" */
export function waitingInWords(count: number): string {
  return count === 1 ? '1 suggestion waits for you' : `${count} suggestions wait for you`
}

/** "1 entry was added from your e-mail", "3 entries were added from your e-mail" */
export function addedInWords(count: number): string {
  return count === 1 ? '1 entry was added from your e-mail' : `${count} entries were added from your e-mail`
}
