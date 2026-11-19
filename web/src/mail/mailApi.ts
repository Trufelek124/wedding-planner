import { api, unwrap, type Schemas } from '../api/client.ts'

export type MailState = Schemas['MailState']
export type MailConnection = Schemas['MailConnection']
export type PartnersMailConnection = Schemas['PartnersMailConnection']
export type ConnectionState = MailConnection['state']

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
