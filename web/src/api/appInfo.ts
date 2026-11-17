import { api, ApiError, type AppInfo } from './client.ts'

/** What the server says about itself; needs no sign-in. */
export async function fetchAppInfo(signal?: AbortSignal): Promise<AppInfo> {
  const { data, error } = await api.GET('/api/app-info', { signal })
  if (error) {
    throw new ApiError(error)
  }
  return data
}
