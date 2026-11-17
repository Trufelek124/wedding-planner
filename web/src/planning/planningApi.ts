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

/**
 * The whole of an entry as a change, with some of it replaced. The server takes an entry as one piece,
 * so a change to its dates alone still says everything else again, and which version it is based on.
 */
export function changeOf(entry: Entry, replaced: Partial<Pick<EntryChange, 'startDate' | 'dueDate'>>): EntryChange {
  return {
    title: entry.title,
    notes: entry.notes,
    categoryId: entry.categoryId,
    startDate: entry.startDate,
    dueDate: entry.dueDate,
    status: entry.status,
    assigneeIds: entry.assigneeIds,
    priorityMode: entry.priorityMode,
    // A level the planner worked out is not sent back as if a person had chosen it.
    priority: entry.priorityMode === 'MANUAL' ? entry.priority : null,
    parentId: entry.parentId,
    version: entry.version,
    ...replaced,
  }
}

/**
 * Makes the plan item wait for another one.
 * @throws ApiError `dependency-cycle` when the other one already waits for this one, directly or through others
 */
export async function addDependency(id: string, dependsOnId: string): Promise<Entry> {
  return unwrap(await api.PUT('/api/entries/{id}/dependencies/{dependsOnId}', { params: { path: { id, dependsOnId } } }))
}

export async function removeDependency(id: string, dependsOnId: string): Promise<Entry> {
  return unwrap(await api.DELETE('/api/entries/{id}/dependencies/{dependsOnId}', { params: { path: { id, dependsOnId } } }))
}

export async function listCategories(signal?: AbortSignal): Promise<Category[]> {
  return unwrap(await api.GET('/api/categories', { signal }))
}
