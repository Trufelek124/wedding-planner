import { useId } from 'react'
import { Link, useLocation, useSearchParams } from 'react-router'
import { money } from '../money/money.ts'
import { listCategories } from '../planning/planningApi.ts'
import { useLoaded } from '../planning/useLoaded.ts'
import { PageRetry, PageWaiting } from '../ui/Screen.tsx'
import { useCurrentWedding } from '../wedding/WeddingContext.ts'
import { listVendors, VENDOR_STATUS_LABEL, VENDOR_STATUSES, type VendorFilter, type VendorStatus } from './vendorsApi.ts'

/** Everyone the wedding is made with: who is being considered, who is booked, and what is agreed and paid. */
export function VendorsPage() {
  const { currency } = useCurrentWedding()
  const id = useId()
  const { search } = useLocation()
  const [params, setParams] = useSearchParams()
  const asked = params.get('status') ?? ''
  const status = (VENDOR_STATUSES as string[]).includes(asked) ? (asked as VendorStatus) : ''
  const category = params.get('category') ?? ''

  const query: VendorFilter = {}
  if (status !== '') {
    query.status = [status]
  }
  if (category !== '') {
    query.categoryId = category
  }
  const vendors = useLoaded(JSON.stringify(query), (signal) => listVendors(query, signal))
  const categories = useLoaded('categories', listCategories)

  function show(name: 'status' | 'category', value: string) {
    const next = new URLSearchParams(params)
    if (value === '') {
      next.delete(name)
    } else {
      next.set(name, value)
    }
    setParams(next, { replace: true })
  }

  if (vendors.state.status === 'failed' || categories.state.status === 'failed') {
    return (
      <PageRetry
        message="The vendors could not be loaded. Try again."
        onRetry={() => {
          vendors.retry()
          categories.retry()
        }}
      />
    )
  }
  if (vendors.state.status === 'loading' || categories.state.status === 'loading') {
    return <PageWaiting what="Loading…" />
  }

  const categoryNames = new Map(categories.state.data.map((one) => [one.id, one.name]))
  const back = `/vendors${search}`

  return (
    <>
      <div className="page-head">
        <h1>Vendors</h1>
        <Link className="button" to="/vendors/new" state={{ back }}>
          Add a vendor
        </Link>
      </div>

      <form className="filters vendor-filters" aria-label="Narrow down" onSubmit={(event) => event.preventDefault()}>
        <div className="filter">
          <label htmlFor={`${id}-status`}>Status</label>
          <select id={`${id}-status`} value={status} onChange={(event) => show('status', event.target.value)}>
            <option value="">Any</option>
            {VENDOR_STATUSES.map((one) => (
              <option key={one} value={one}>
                {VENDOR_STATUS_LABEL[one]}
              </option>
            ))}
          </select>
        </div>
        <div className="filter">
          <label htmlFor={`${id}-category`}>Category</label>
          <select id={`${id}-category`} value={category} onChange={(event) => show('category', event.target.value)}>
            <option value="">Any</option>
            {categories.state.data.map((one) => (
              <option key={one.id} value={one.id}>
                {one.name}
              </option>
            ))}
          </select>
        </div>
      </form>

      {vendors.state.data.length === 0 ? (
        <p className="empty">
          {status === '' && category === ''
            ? 'No vendors yet. Add the first one you are thinking about.'
            : 'No vendor matches. Choose another status or category.'}
        </p>
      ) : (
        <ul className="entries">
          {vendors.state.data.map((vendor) => {
            const itsCategory = vendor.categoryId === null ? undefined : categoryNames.get(vendor.categoryId)
            return (
              <li key={vendor.id} className="entry vendor-row" data-done={vendor.status === 'DROPPED'}>
                <div className="entry-body">
                  <Link className="entry-title" to={`/vendors/${vendor.id}`} state={{ back }}>
                    {vendor.name}
                  </Link>
                  <p className="entry-meta">
                    {/* The status is a word; how it is set off only goes with the word. */}
                    <span className="vendor-status" data-status={vendor.status}>
                      {VENDOR_STATUS_LABEL[vendor.status]}
                    </span>
                    {itsCategory !== undefined && <span>{itsCategory}</span>}
                    {vendor.contactPerson !== '' && <span>{vendor.contactPerson}</span>}
                  </p>
                </div>
                {(vendor.agreedPrice !== null || vendor.committed > 0) && (
                  <p className="vendor-money">
                    {vendor.agreedPrice !== null && <span>{money(vendor.agreedPrice, currency)} agreed</span>}
                    <span>{money(vendor.paid, currency)} paid</span>
                  </p>
                )}
              </li>
            )
          })}
        </ul>
      )}
    </>
  )
}
