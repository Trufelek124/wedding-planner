/**
 * An id for something this page is about to add (a UUID, version 4). The page chooses it, so that a
 * request sent again after no answer came adds the thing once: the server answers the second
 * request with what it already has.
 *
 * Put together from random numbers by hand: the browser's own `crypto.randomUUID` exists only on
 * pages served securely, the random numbers under it everywhere.
 */
export function newId(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  // The two places that say "a random UUID".
  bytes[6] = ((bytes[6] ?? 0) & 0x0f) | 0x40
  bytes[8] = ((bytes[8] ?? 0) & 0x3f) | 0x80
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
