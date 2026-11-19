import { api, unwrap, type Schemas } from '../api/client.ts'

export type MailState = Schemas['MailState']
export type MailConnection = Schemas['MailConnection']
export type PartnersMailConnection = Schemas['PartnersMailConnection']
export type ConnectionState = MailConnection['state']
export type MailReading = Schemas['MailReading']
export type MailLookBack = Schemas['MailLookBack']

/** How far back an owner may ask, in days. The server takes 7 to 90 and says so; these are the ones the page offers. */
export const LOOK_BACK_CHOICES = [7, 14, 30, 60, 90] as const
export const LOOK_BACK_TO_BEGIN_WITH = 30

export async function fetchMail(signal?: AbortSignal): Promise<MailState> {
  return unwrap(await api.GET('/api/mail', { signal }))
}

/** Google's address to send the browser to. @throws ApiError `mail-not-set-up` while the import is off */
export async function startConnecting(): Promise<string> {
  return unwrap(await api.POST('/api/mail/gmail/connection')).authorizationUrl
}

/** @throws ApiError `mail-not-set-up` while the import is off */
export async function disconnect(): Promise<void> {
  unwrap(await api.DELETE('/api/mail/gmail/connection'))
}

/**
 * Asks the server to look through the last `days` days of the owner's own mailbox. Answers with what the page is told,
 * which already says that the look back is under way. Asked once: nothing here tries again by itself.
 * @throws ApiError `mail-not-connected` without a connected mailbox, `mail-not-set-up` while the import is off, and a
 *   field problem on `days` outside 7 to 90
 */
export async function lookBack(days: number): Promise<MailState> {
  return unwrap(await api.POST('/api/mail/gmail/look-back', { body: { days } }))
}

/**
 * Where the browser may be sent to connect: an https address at accounts.google.com and nowhere else.
 * Returns the address as the server wrote it, or null.
 */
export function googleAddress(address: string): string | null {
  try {
    const url = new URL(address)
    const ours = url.protocol === 'https:' && url.hostname === 'accounts.google.com' && url.port === '' && url.username === '' && url.password === ''
    return ours ? address : null
  } catch {
    return null
  }
}

/** Whether the planner should ask an owner on Home to connect: the import works here and this owner's mail is not in. */
export function needsConnecting(mail: MailState): boolean {
  return mail.setUp && mail.yours.state !== 'CONNECTED'
}

const US_DOLLARS = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' })

/**
 * US dollars from whole cents: "$1.23", "$20.00". The AI service is paid in dollars whatever the wedding's currency is,
 * so this is not `money()`, which is for the wedding's.
 */
export function dollars(cents: number): string {
  return US_DOLLARS.format(cents / 100)
}

/** "19 Nov 2026, 14:05" in the time zone of the wedding, put together from parts so that browsers agree. */
export function moment(iso: string, timeZone: string): string {
  const parts = new Intl.DateTimeFormat('en-GB', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
    timeZone,
  }).formatToParts(new Date(iso))
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find((entry) => entry.type === type)?.value ?? ''
  return `${part('day')} ${part('month')} ${part('year')}, ${part('hour')}:${part('minute')}`
}

/** "last looked on 19 Nov 2026, 14:05", or "not looked at yet". */
export function lastLook(iso: string | null, timeZone: string): string {
  return iso === null ? 'not looked at yet' : `last looked on ${moment(iso, timeZone)}`
}

/** Where the browser is sent: one place, so that a test can see where it would go. */
export const browser = {
  goTo(address: string): void {
    window.location.assign(address)
  },
}
