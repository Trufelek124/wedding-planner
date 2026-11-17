const LIBRARY = 'https://accounts.google.com/gsi/client'

let loading: Promise<typeof google.accounts.id> | null = null

/** Adds Google's sign-in library to the page once. */
export function loadGoogle(): Promise<typeof google.accounts.id> {
  loading ??= new Promise((resolve, reject) => {
    const script = document.createElement('script')
    script.src = LIBRARY
    script.async = true
    script.onload = () => resolve(google.accounts.id)
    script.onerror = () => {
      loading = null
      script.remove()
      reject(new Error('The Google sign-in library could not be loaded'))
    }
    document.head.append(script)
  })
  return loading
}

/** After a sign-out Google must not sign the same person back in by itself. */
export function forgetGoogleSignIn(): void {
  if (typeof google !== 'undefined') {
    google.accounts.id.disableAutoSelect()
  }
}
