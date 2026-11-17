import { api, unwrap, type Schemas } from '../api/client.ts'
import type { paths } from '../api/generated/schema.ts'

export type Entry = Schemas['Entry']
export type NewEntry = Schemas['NewEntry']
export type EntryChange = Schemas['EntryChange']
export type Category = Schemas['Category']
export type Kind = Entry['kind']
export type Status = Entry['status']
export type Priority = Entry['priority']
/** What a list of entries can be narrowed down by and ordered by. */
export type EntryFilter = NonNullable<paths['/api/entries']['get']['parameters']['query']>

export async function listEntries(filter: EntryFilter, signal?: AbortSignal): Promise<Entry[]> {
  return unwrap(await api.GET('/api/entries', { params: { query: filter }, signal }))
}

export async function getEntry(id: string, signal?: AbortSignal): Promise<Entry> {
  return unwrap(await api.GET('/api/entries/{id}', { params: { path: { id } }, signal }))
}

export async function createEntry(entry: NewEntry): Promise<Entry> {
  return unwrap(await api.POST('/api/entries', { body: entry }))
}

/** @throws ApiError `changed-meanwhile` when someone else saved the entry after it was loaded */
export async function changeEntry(id: string, change: EntryChange): Promise<Entry> {
  return unwrap(await api.PUT('/api/entries/{id}', { params: { path: { id } }, body: change }))
}

export async function setEntryStatus(id: string, status: Status): Promise<Entry> {
  return unwrap(await api.PUT('/api/entries/{id}/status', { params: { path: { id } }, body: { status } }))
}

/** @throws ApiError `entry-has-tasks` for a plan item that still has tasks */
export async function convertEntry(id: string, kind: Kind): Promise<Entry> {
  return unwrap(await api.POST('/api/entries/{id}/convert', { params: { path: { id } }, body: { kind } }))
}

/** @param tasks for a plan item with tasks: what happens to them */
export async function deleteEntry(id: string, tasks?: 'KEEP' | 'DELETE'): Promise<void> {
  const { error } = await api.DELETE('/api/entries/{id}', { params: { path: { id }, query: { tasks } } })
  if (error !== undefined) {
    unwrap({ error })
  }
}

export async function listCategories(signal?: AbortSignal): Promise<Category[]> {
  return unwrap(await api.GET('/api/categories', { signal }))
}
