import { useSyncExternalStore } from 'react'
import { snapshot, subscribe, type SessionState } from './session.ts'

export function useSession(): SessionState {
  return useSyncExternalStore(subscribe, snapshot)
}
