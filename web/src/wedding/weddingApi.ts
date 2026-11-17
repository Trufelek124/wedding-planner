import { api, unwrap, type Schemas } from '../api/client.ts'

export type Wedding = Schemas['Wedding']
export type WeddingSetup = Schemas['WeddingSetup']

/** @throws ApiError `wedding-not-set-up` until someone has set the wedding up */
export async function fetchWedding(signal?: AbortSignal): Promise<Wedding> {
  return unwrap(await api.GET('/api/wedding', { signal }))
}

/** @throws ApiError with field problems, or `wedding-already-set-up` when the other owner was faster */
export async function setUpWedding(setup: WeddingSetup): Promise<Wedding> {
  return unwrap(await api.POST('/api/wedding', { body: setup }))
}
