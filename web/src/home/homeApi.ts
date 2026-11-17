import { api, unwrap, type Schemas } from '../api/client.ts'

/** What the first page shows. The server counts the days, so that every device agrees on what today is. */
export type Home = Schemas['Home']

export async function fetchHome(signal?: AbortSignal): Promise<Home> {
  return unwrap(await api.GET('/api/home', { signal }))
}
