import { api, unwrap, type Schemas } from '../api/client.ts'

export type TaskFileReport = Schemas['TaskFileReport']
export type TaskFileRow = Schemas['TaskFileRow']

/** What the server takes: the same 1 MB it says in its own words. */
export const MOST_BYTES = 1_048_576

/**
 * The file's text goes out as it stands. The client library would turn a string into a quoted JSON string,
 * so the body is given a serializer that hands the text through untouched.
 */
const asItIs = (text: string) => text

/** What the file would do. Changes nothing, however often it is asked. */
export async function checkTaskFile(text: string): Promise<TaskFileReport> {
  return unwrap(await api.POST('/api/tasks/file/check', { body: text, bodySerializer: asItIs }))
}

/**
 * Does it: new tasks are added, known ones changed, in one piece.
 * @throws ApiError `task-file-refused` when anything is wrong now, with nothing changed
 */
export async function applyTaskFile(text: string): Promise<TaskFileReport> {
  return unwrap(await api.POST('/api/tasks/file', { body: text, bodySerializer: asItIs }))
}
