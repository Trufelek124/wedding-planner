import { createContext, useContext } from 'react'

/** How many suggestions wait, as the shell knows it; null while that is not known. A page that decides one tells it to read again. */
export interface Waiting {
  count: number | null
  reload: () => void
}

export const WaitingContext = createContext<Waiting>({ count: null, reload: () => undefined })

export function useWaiting(): Waiting {
  return useContext(WaitingContext)
}

/** "1 suggestion waits for you", "3 suggestions wait for you" */
export function waitingInWords(count: number): string {
  return count === 1 ? '1 suggestion waits for you' : `${count} suggestions wait for you`
}
