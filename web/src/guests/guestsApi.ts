import { api, unwrap, type Schemas } from '../api/client.ts'
import type { paths } from '../api/generated/schema.ts'

export type Guest = Schemas['Guest']
export type NewGuest = Schemas['NewGuest']
export type GuestChange = Schemas['GuestChange']
export type GuestStatus = Guest['status']
export type Household = Schemas['Household']
export type GuestCounts = Schemas['GuestCounts']
export type HeadCount = Schemas['HeadCount']
export type GuestFilter = NonNullable<paths['/api/guests']['get']['parameters']['query']>

/** In the order the head count lists them. */
export const GUEST_STATUSES: GuestStatus[] = ['NOT_INVITED', 'INVITED', 'CONFIRMED', 'DECLINED', 'MAYBE']

export const GUEST_STATUS_LABEL: Record<GuestStatus, string> = {
  NOT_INVITED: 'Not invited yet',
  INVITED: 'Invited',
  CONFIRMED: 'Confirmed',
  DECLINED: 'Declined',
  MAYBE: 'Maybe',
}

/** By name. */
export async function listGuests(filter: GuestFilter = {}, signal?: AbortSignal): Promise<Guest[]> {
  return unwrap(await api.GET('/api/guests', { params: { query: filter }, signal }))
}

export async function fetchGuestCounts(signal?: AbortSignal): Promise<GuestCounts> {
  return unwrap(await api.GET('/api/guests/counts', { signal }))
}

export async function getGuest(id: string, signal?: AbortSignal): Promise<Guest> {
  return unwrap(await api.GET('/api/guests/{id}', { params: { path: { id } }, signal }))
}

export async function createGuest(guest: NewGuest): Promise<Guest> {
  return unwrap(await api.POST('/api/guests', { body: guest }))
}

/** @throws ApiError `changed-meanwhile` when someone else saved the guest after it was loaded */
export async function changeGuest(id: string, change: GuestChange): Promise<Guest> {
  return unwrap(await api.PUT('/api/guests/{id}', { params: { path: { id } }, body: change }))
}

/** An answer that came in: the status alone, in one action. */
export async function setGuestStatus(id: string, status: GuestStatus): Promise<Guest> {
  return unwrap(await api.PUT('/api/guests/{id}/status', { params: { path: { id } }, body: { status } }))
}

export async function deleteGuest(id: string): Promise<void> {
  const { error } = await api.DELETE('/api/guests/{id}', { params: { path: { id } } })
  if (error !== undefined) {
    unwrap({ error })
  }
}

/** By name. */
export async function listHouseholds(signal?: AbortSignal): Promise<Household[]> {
  return unwrap(await api.GET('/api/households', { signal }))
}

export async function addHousehold(name: string): Promise<Household> {
  return unwrap(await api.POST('/api/households', { body: { name } }))
}

export async function renameHousehold(id: string, name: string): Promise<Household> {
  return unwrap(await api.PUT('/api/households/{id}', { params: { path: { id } }, body: { name } }))
}

/** Its guests stay on the list, without a household. */
export async function deleteHousehold(id: string): Promise<void> {
  const { error } = await api.DELETE('/api/households/{id}', { params: { path: { id } } })
  if (error !== undefined) {
    unwrap({ error })
  }
}

/** "1 person", "4 people" */
export function people(heads: number): string {
  return heads === 1 ? '1 person' : `${heads} people`
}

/** Whom a guest brings and what they need, as short facts: "with a plus-one", "2 children", "needs a room". */
export function whatComesAlong(guest: Pick<Guest, 'plusOne' | 'children' | 'needsAccommodation' | 'dietaryNeeds'>): string[] {
  const facts: string[] = []
  if (guest.plusOne) {
    facts.push('with a plus-one')
  }
  if (guest.children > 0) {
    facts.push(guest.children === 1 ? '1 child' : `${guest.children} children`)
  }
  if (guest.needsAccommodation) {
    facts.push('needs a room')
  }
  if (guest.dietaryNeeds !== '') {
    facts.push(guest.dietaryNeeds)
  }
  return facts
}
