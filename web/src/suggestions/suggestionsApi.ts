import { api, ApiError, unwrap, type Schemas } from '../api/client.ts'
import type { paths } from '../api/generated/schema.ts'

export type Suggestion = Schemas['Suggestion']
export type SuggestionAcceptance = Schemas['SuggestionAcceptance']
export type SuggestionKind = Suggestion['kind']
export type SuggestionState = NonNullable<NonNullable<paths['/api/suggestions']['get']['parameters']['query']>['state']>

/** The four places a suggestion can stand, in the order the inbox offers them. */
export const SHOWN_STATES = ['WAITING', 'ADDED', 'ACCEPTED', 'REJECTED'] as const
export type ShownState = (typeof SHOWN_STATES)[number]

export const STATE_LABEL: Record<ShownState, string> = {
  WAITING: 'Waiting',
  ADDED: 'Added by itself',
  ACCEPTED: 'Accepted',
  REJECTED: 'Rejected',
}

export type SuggestionSettings = Schemas['SuggestionSettings']

export const KIND_LABEL: Record<SuggestionKind, string> = {
  TASK: 'Task',
  PAYMENT: 'Payment',
  PLAN_ITEM: 'Step of the plan',
  VENDOR: 'Vendor',
}

/** The newest message first. */
export async function listSuggestions(state: SuggestionState, signal?: AbortSignal): Promise<Suggestion[]> {
  return unwrap(await api.GET('/api/suggestions', { params: { query: { state } }, signal }))
}

/** One suggestion; null when there is none of that id (any more). */
export async function getSuggestion(id: string, signal?: AbortSignal): Promise<Suggestion | null> {
  try {
    return unwrap(await api.GET('/api/suggestions/{id}', { params: { path: { id } }, signal }))
  } catch (error) {
    if (error instanceof ApiError && error.is('urn:wedding-planner:problem:not-found')) {
      return null
    }
    throw error
  }
}

/** Says "this belongs in our plan" of one that was added by itself. @throws ApiError `already-decided` when it was undone */
export async function keepSuggestion(id: string): Promise<Suggestion> {
  return unwrap(await api.POST('/api/suggestions/{id}/keep', { params: { path: { id } } }))
}

/** Removes the entry that was added and rejects the suggestion. @throws ApiError `entry-changed` (it then counts as kept), `already-decided` */
export async function undoSuggestion(id: string): Promise<Suggestion> {
  return unwrap(await api.POST('/api/suggestions/{id}/undo', { params: { path: { id } } }))
}

export async function getSettings(signal?: AbortSignal): Promise<SuggestionSettings> {
  return unwrap(await api.GET('/api/suggestions/settings', { signal }))
}

export async function changeSettings(settings: SuggestionSettings): Promise<SuggestionSettings> {
  return unwrap(await api.PUT('/api/suggestions/settings', { body: settings }))
}

/**
 * Without `acceptance` the proposal is made as it stands; with one, exactly those values (a full replacement).
 * @throws ApiError `already-decided` when it was rejected first
 */
export async function acceptSuggestion(id: string, acceptance?: SuggestionAcceptance): Promise<Suggestion> {
  return unwrap(await api.POST('/api/suggestions/{id}/accept', { params: { path: { id } }, body: acceptance }))
}

/** @throws ApiError `already-decided` when it was accepted first */
export async function rejectSuggestion(id: string): Promise<Suggestion> {
  return unwrap(await api.POST('/api/suggestions/{id}/reject', { params: { path: { id } } }))
}

/**
 * The link to the e-mail, only when it is an https address: whatever else a mail's data holds is not drawn as a link.
 * Returns the address as written, or null.
 */
export function mailAddress(link: string): string | null {
  try {
    return new URL(link).protocol === 'https:' ? link : null
  } catch {
    return null
  }
}

/** Where what accepting made lives, and what to call it. */
export function madeThing(suggestion: Suggestion): { to: string; where: string; open: string } | null {
  switch (suggestion.kind) {
    case 'TASK':
    case 'PAYMENT':
      return suggestion.madeEntryId === null
        ? null
        : {
            to: `/entries/${suggestion.madeEntryId}`,
            where: 'the tasks',
            open: suggestion.kind === 'TASK' ? 'Open the task' : 'Open the payment',
          }
    case 'PLAN_ITEM':
      return suggestion.madeEntryId === null
        ? null
        : { to: `/entries/${suggestion.madeEntryId}`, where: 'the plan', open: 'Open the step of the plan' }
    case 'VENDOR':
      return suggestion.madeVendorId === null
        ? null
        : { to: `/vendors/${suggestion.madeVendorId}`, where: 'the vendors', open: 'Open the vendor' }
  }
}

/** "19 Nov 2026, 14:05" in the time zone of the wedding. */
export function atTime(iso: string, timeZone: string): string {
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

/** "you", "Piotr", or "someone" for a member this wedding does not know. */
export function memberName(memberId: string | null, members: { id: string; displayName: string; you: boolean }[]): string {
  const member = members.find((one) => one.id === memberId)
  return member === undefined ? 'someone' : member.you ? 'you' : member.displayName
}

/** How it stands: waiting for nobody, kept after thirty days, or who decided it and when. */
export function standing(suggestion: Suggestion, members: { id: string; displayName: string; you: boolean }[], timeZone: string): string {
  if (suggestion.state === 'ADDED') {
    return 'Added by itself'
  }
  if (suggestion.state === 'ACCEPTED' && suggestion.decidedBy === null) {
    // Nobody decided: the thirty days passed.
    return 'Added by itself; kept after thirty days'
  }
  const by = memberName(suggestion.decidedBy, members)
  const when = suggestion.decidedAt === null ? '' : `, ${atTime(suggestion.decidedAt, timeZone)}`
  return `${suggestion.state === 'ACCEPTED' ? 'Accepted' : 'Rejected'} by ${by}${when}`
}
