import { useEffect, useRef } from 'react'
import { loadGoogle } from './google.ts'

interface GoogleButtonProps {
  clientId: string
  /** Called with the ID token Google issued; the server decides what it is worth. */
  onIdToken: (idToken: string) => void
  /** Called when Google's library cannot be loaded (blocked, offline). */
  onUnavailable: () => void
}

/** Google's own "Sign in with Google" button. Google draws it; this component only hosts it. */
export function GoogleButton({ clientId, onIdToken, onUnavailable }: GoogleButtonProps) {
  const host = useRef<HTMLDivElement>(null)
  const handlers = useRef({ onIdToken, onUnavailable })

  useEffect(() => {
    handlers.current = { onIdToken, onUnavailable }
  })

  useEffect(() => {
    let cancelled = false
    loadGoogle()
      .then((id) => {
        if (cancelled || host.current === null) {
          return
        }
        id.initialize({
          client_id: clientId,
          callback: (response) => handlers.current.onIdToken(response.credential),
          auto_select: false,
          ux_mode: 'popup',
        })
        id.renderButton(host.current, {
          type: 'standard',
          theme: 'outline',
          size: 'large',
          text: 'signin_with',
          shape: 'pill',
          logo_alignment: 'left',
          width: 300,
          locale: 'en',
        })
      })
      .catch(() => {
        if (!cancelled) {
          handlers.current.onUnavailable()
        }
      })
    return () => {
      cancelled = true
    }
  }, [clientId])

  return <div ref={host} className="google-button" data-testid="google-button" />
}
