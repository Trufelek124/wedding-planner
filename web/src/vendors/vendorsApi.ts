import { api, unwrap, type Schemas } from '../api/client.ts'
import type { paths } from '../api/generated/schema.ts'

export type Vendor = Schemas['Vendor']
export type NewVendor = Schemas['NewVendor']
export type VendorChange = Schemas['VendorChange']
export type VendorStatus = Vendor['status']
export type VendorFilter = NonNullable<paths['/api/vendors']['get']['parameters']['query']>

export const VENDOR_STATUSES: VendorStatus[] = ['CONSIDERING', 'BOOKED', 'DROPPED']

export const VENDOR_STATUS_LABEL: Record<VendorStatus, string> = {
  CONSIDERING: 'Considering',
  BOOKED: 'Booked',
  DROPPED: 'Dropped',
}

/** By name. */
export async function listVendors(filter: VendorFilter = {}, signal?: AbortSignal): Promise<Vendor[]> {
  return unwrap(await api.GET('/api/vendors', { params: { query: filter }, signal }))
}

export async function getVendor(id: string, signal?: AbortSignal): Promise<Vendor> {
  return unwrap(await api.GET('/api/vendors/{id}', { params: { path: { id } }, signal }))
}

export async function createVendor(vendor: NewVendor): Promise<Vendor> {
  return unwrap(await api.POST('/api/vendors', { body: vendor }))
}

/** @throws ApiError `changed-meanwhile` when someone else saved the vendor after it was loaded */
export async function changeVendor(id: string, change: VendorChange): Promise<Vendor> {
  return unwrap(await api.PUT('/api/vendors/{id}', { params: { path: { id } }, body: change }))
}

/**
 * @param links 'REMOVE' lets what points to the vendor stay without one
 * @throws ApiError `vendor-in-use` when something points to the vendor and nothing was said
 */
export async function deleteVendor(id: string, links?: 'REMOVE'): Promise<void> {
  const { error } = await api.DELETE('/api/vendors/{id}', { params: { path: { id }, query: { links } } })
  if (error !== undefined) {
    unwrap({ error })
  }
}

/** What points to a vendor, in words: "1 plan item, 3 tasks and 2 inspirations"; null when nothing does. */
export function whatPointsTo(vendor: Pick<Vendor, 'planItems' | 'tasks' | 'inspirations'>): string | null {
  const parts = [
    [vendor.planItems, 'plan item'],
    [vendor.tasks, 'task'],
    [vendor.inspirations, 'inspiration'],
  ]
    .filter(([count]) => (count as number) > 0)
    .map(([count, what]) => `${count} ${what}${count === 1 ? '' : 's'}`)
  if (parts.length === 0) {
    return null
  }
  return parts.length === 1 ? (parts[0] as string) : `${parts.slice(0, -1).join(', ')} and ${parts.at(-1)}`
}

/** A phone number as a link takes it: the digits and a leading plus, nothing else. */
export function phoneLink(phone: string): string {
  return `tel:${phone.replace(/[^\d+]/g, '')}`
}

/** "dwor.example" for "https://www.dwor.example/wesela": what a link to a website is called. */
export function siteName(website: string): string {
  try {
    return new URL(website).hostname.replace(/^www\./, '')
  } catch {
    return website
  }
}
