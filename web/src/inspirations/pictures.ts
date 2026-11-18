import { snapshot, subscribe } from '../auth/session.ts'
import { fetchPicture, type PictureSize } from './inspirationsApi.ts'

/**
 * Pictures as the page can show them. The server hands a picture out only with the access token, which
 * an `<img>` cannot send; so the bytes are fetched here and given to the page under an address of the
 * browser's own making. A picture never changes once kept, so each is fetched once per visit.
 */
const loaded = new Map<string, Promise<string>>()

/** Pictures in full size are large, and one is looked at at a time: only the last few stay in memory. */
const MOST_FULL = 4
const full: string[] = []

function letGo(key: string): void {
  const address = loaded.get(key)
  loaded.delete(key)
  void address?.then((url) => URL.revokeObjectURL(url)).catch(() => undefined)
}

/** An address for `<img src>`; rejects when the picture cannot be fetched. */
export function pictureAddress(inspirationId: string, pictureId: string, size: PictureSize): Promise<string> {
  const key = `${inspirationId}/${pictureId}/${size}`
  if (size === 'FULL') {
    // The one asked for last is the one to keep longest.
    const at = full.indexOf(key)
    if (at >= 0) {
      full.splice(at, 1)
    }
    full.push(key)
    while (full.length > MOST_FULL) {
      letGo(full.shift() as string)
    }
  }
  const known = loaded.get(key)
  if (known !== undefined) {
    return known
  }
  const address: Promise<string> = fetchPicture(inspirationId, pictureId, size).then((bytes) => {
    const url = URL.createObjectURL(bytes)
    if (loaded.get(key) !== address) {
      // Let go of while it was on its way, by a sign-out for one: nothing of it stays.
      URL.revokeObjectURL(url)
      throw new Error('The picture is no longer wanted')
    }
    return url
  })
  // A failure is not remembered: the next look tries again.
  address.catch(() => {
    if (loaded.get(key) === address) {
      loaded.delete(key)
    }
  })
  loaded.set(key, address)
  return address
}

/** Lets go of the pictures of one inspiration, or of one picture of it, once they are deleted. */
export function forgetPicturesOf(inspirationId: string, pictureId?: string): void {
  const start = pictureId === undefined ? `${inspirationId}/` : `${inspirationId}/${pictureId}/`
  for (const key of [...loaded.keys()]) {
    if (key.startsWith(start)) {
      letGo(key)
    }
  }
}

/** Lets go of every picture: after signing out nothing of them stays in the page's memory. */
export function forgetPictures(): void {
  for (const key of [...loaded.keys()]) {
    letGo(key)
  }
  full.length = 0
}

let signedIn = snapshot().status === 'signedIn'
subscribe(() => {
  const now = snapshot().status === 'signedIn'
  if (signedIn && !now) {
    forgetPictures()
  }
  signedIn = now
})
