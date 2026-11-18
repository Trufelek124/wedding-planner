import { useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router'
import { ApiError } from '../api/client.ts'
import { moneyToType, moneyTyped } from '../money/money.ts'
import { listCategories, type Category } from '../planning/planningApi.ts'
import { useLoaded } from '../planning/useLoaded.ts'
import { Field } from '../ui/Field.tsx'
import { PageRetry, PageWaiting } from '../ui/Screen.tsx'
import { useCurrentWedding } from '../wedding/WeddingContext.ts'
import {
  changeVendor,
  createVendor,
  deleteVendor,
  getVendor,
  VENDOR_STATUS_LABEL,
  VENDOR_STATUSES,
  whatPointsTo,
  type Vendor,
  type VendorStatus,
} from './vendorsApi.ts'

/** The page for adding a vendor (`/vendors/new`) and for changing one (`/vendors/:id/edit`). */
export function VendorFormPage() {
  const { id } = useParams()
  const vendor = useLoaded(id ?? 'new', (signal) => (id === undefined ? Promise.resolve(null) : getVendor(id, signal)))
  const categories = useLoaded('categories', listCategories)

  if (vendor.state.status === 'failed' || categories.state.status === 'failed') {
    return (
      <PageRetry
        message="This could not be loaded. It may have been deleted. Try again, or go back to the vendors."
        onRetry={() => {
          vendor.retry()
          categories.retry()
        }}
      />
    )
  }
  if (vendor.state.status === 'loading' || categories.state.status === 'loading') {
    return <PageWaiting what="Loading…" />
  }
  // Loading the latest version shows the page waiting in between, so the form starts again from it.
  return <VendorForm key={vendor.state.data?.id ?? 'new'} existing={vendor.state.data} categories={categories.state.data} onReload={vendor.retry} />
}

function cameFrom(state: unknown): string | null {
  return typeof state === 'object' && state !== null && 'back' in state && typeof state.back === 'string' ? state.back : null
}

function VendorForm({ existing, categories, onReload }: { existing: Vendor | null; categories: Category[]; onReload: () => void }) {
  const { currency } = useCurrentWedding()
  const navigate = useNavigate()
  const location = useLocation()
  const back = cameFrom(location.state) ?? (existing === null ? '/vendors' : `/vendors/${existing.id}`)

  const [name, setName] = useState(existing?.name ?? '')
  const [categoryId, setCategoryId] = useState(existing?.categoryId ?? '')
  const [status, setStatus] = useState<VendorStatus>(existing?.status ?? 'CONSIDERING')
  const [contactPerson, setContactPerson] = useState(existing?.contactPerson ?? '')
  const [phone, setPhone] = useState(existing?.phone ?? '')
  const [email, setEmail] = useState(existing?.email ?? '')
  const [website, setWebsite] = useState(existing?.website ?? '')
  const [address, setAddress] = useState(existing?.address ?? '')
  const [price, setPrice] = useState(existing?.agreedPrice == null ? '' : moneyToType(existing.agreedPrice, currency))
  const [notes, setNotes] = useState(existing?.notes ?? '')

  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState<ApiError | 'unreachable' | null>(null)
  const [priceProblem, setPriceProblem] = useState<string | null>(null)
  const [asking, setAsking] = useState(false)

  function fieldError(field: string): string | undefined {
    return failure instanceof ApiError ? failure.fieldMessage(field) : undefined
  }

  async function attempt(action: () => Promise<void>) {
    setBusy(true)
    setFailure(null)
    try {
      await action()
    } catch (error) {
      setFailure(error instanceof ApiError ? error : 'unreachable')
      setBusy(false)
    }
  }

  function save(event: FormEvent) {
    event.preventDefault()
    const agreedPrice = price.trim() === '' ? null : moneyTyped(price, currency)
    if (price.trim() !== '' && agreedPrice === null) {
      setPriceProblem('Enter an amount like 2500 or 2500.50, or leave it empty.')
      return
    }
    const fields = {
      name: name.trim(),
      categoryId: categoryId === '' ? null : categoryId,
      status,
      contactPerson: contactPerson.trim(),
      phone: phone.trim(),
      email: email.trim(),
      website: website.trim(),
      address: address.trim(),
      agreedPrice,
      notes: notes.trim(),
    }
    void attempt(async () => {
      const saved = existing === null ? await createVendor(fields) : await changeVendor(existing.id, { version: existing.version, ...fields })
      // A new vendor is shown on its own page; a changed one where the person came from.
      await navigate(existing === null ? `/vendors/${saved.id}` : back)
    })
  }

  function remove() {
    if (existing === null) return
    void attempt(async () => {
      // Having been asked, the person has said that what points to the vendor may lose it.
      await deleteVendor(existing.id, 'REMOVE')
      await navigate('/vendors')
    })
  }

  const changedMeanwhile = failure instanceof ApiError && failure.is('urn:wedding-planner:problem:changed-meanwhile')
  const general =
    failure === 'unreachable'
      ? 'The server cannot be reached. Check your connection and try again.'
      : failure !== null && !changedMeanwhile && (failure.problem.fieldProblems ?? []).length === 0
        ? (failure.problem.detail ?? 'That did not work. Try again.')
        : null
  const pointing = existing === null ? null : whatPointsTo(existing)

  return (
    <div className="form-page">
      <h1>{existing === null ? 'Add a vendor' : 'Change this vendor'}</h1>

      {changedMeanwhile && (
        <div role="alert" className="alert stack">
          <p>Someone else changed this in the meantime, so your change was not saved.</p>
          <button type="button" className="button button-quiet" onClick={onReload}>
            Load the latest version
          </button>
        </div>
      )}
      {general !== null && (
        <p role="alert" className="alert">
          {general}
        </p>
      )}

      <form className="stack" onSubmit={save} noValidate>
        <Field label="Name" error={fieldError('name')}>
          {(control) => <input {...control} name="name" maxLength={120} required value={name} onChange={(event) => setName(event.target.value)} />}
        </Field>

        <div className="two-columns">
          <Field label="Status" error={fieldError('status')}>
            {(control) => (
              <select {...control} name="status" value={status} onChange={(event) => setStatus(event.target.value as VendorStatus)}>
                {VENDOR_STATUSES.map((one) => (
                  <option key={one} value={one}>
                    {VENDOR_STATUS_LABEL[one]}
                  </option>
                ))}
              </select>
            )}
          </Field>
          <Field label="Category" optional error={fieldError('categoryId')}>
            {(control) => (
              <select {...control} name="categoryId" value={categoryId} onChange={(event) => setCategoryId(event.target.value)}>
                <option value="">None</option>
                {categories.map((category) => (
                  <option key={category.id} value={category.id}>
                    {category.name}
                  </option>
                ))}
              </select>
            )}
          </Field>
        </div>

        <Field label="Contact person" optional error={fieldError('contactPerson')}>
          {(control) => (
            <input
              {...control}
              name="contactPerson"
              maxLength={120}
              autoComplete="off"
              value={contactPerson}
              onChange={(event) => setContactPerson(event.target.value)}
            />
          )}
        </Field>

        <div className="two-columns">
          <Field label="Phone" optional error={fieldError('phone')}>
            {(control) => (
              <input {...control} type="tel" name="phone" maxLength={40} autoComplete="off" value={phone} onChange={(event) => setPhone(event.target.value)} />
            )}
          </Field>
          <Field label="E-mail" optional error={fieldError('email')}>
            {(control) => (
              <input
                {...control}
                type="email"
                name="email"
                maxLength={254}
                autoComplete="off"
                autoCapitalize="none"
                value={email}
                onChange={(event) => setEmail(event.target.value)}
              />
            )}
          </Field>
        </div>

        <Field label="Website" optional error={fieldError('website')}>
          {(control) => (
            <input
              {...control}
              name="website"
              inputMode="url"
              maxLength={2000}
              autoComplete="off"
              autoCapitalize="none"
              value={website}
              onChange={(event) => setWebsite(event.target.value)}
            />
          )}
        </Field>

        <Field label="Address" optional error={fieldError('address')}>
          {(control) => (
            <textarea {...control} name="address" rows={2} maxLength={500} value={address} onChange={(event) => setAddress(event.target.value)} />
          )}
        </Field>

        <Field
          label={`Agreed price in ${currency}`}
          optional
          hint="What you agreed on in all. The payments themselves are tasks with an amount."
          error={priceProblem ?? fieldError('agreedPrice')}
        >
          {(control) => (
            <input
              {...control}
              name="agreedPrice"
              inputMode="decimal"
              autoComplete="off"
              value={price}
              onChange={(event) => {
                setPrice(event.target.value)
                setPriceProblem(null)
              }}
            />
          )}
        </Field>

        <Field label="Notes" optional error={fieldError('notes')}>
          {(control) => (
            <textarea {...control} name="notes" rows={4} maxLength={10000} value={notes} onChange={(event) => setNotes(event.target.value)} />
          )}
        </Field>

        <div className="actions">
          <button type="submit" className="button" disabled={busy}>
            {busy ? 'Saving…' : 'Save'}
          </button>
          <Link className="button button-quiet" to={back}>
            Cancel
          </Link>
        </div>
      </form>

      {existing !== null && (
        <section className="danger-zone stack" aria-label="More">
          <h2>More</h2>
          {!asking ? (
            <div>
              <button type="button" className="button button-danger" disabled={busy} onClick={() => setAsking(true)}>
                Delete this vendor
              </button>
            </div>
          ) : (
            <div className="stack" role="group" aria-label="Delete">
              <p>
                Delete “{existing.name}”?{' '}
                {pointing === null
                  ? 'Nothing points to it.'
                  : existing.planItems + existing.tasks + existing.inspirations === 1
                    ? `${pointing} points to it and stays, without a vendor.`
                    : `${pointing} point to it and stay, without a vendor.`}{' '}
                This cannot be undone.
              </p>
              <div className="actions">
                <button type="button" className="button button-danger" disabled={busy} onClick={remove}>
                  Yes, delete it
                </button>
                <button type="button" className="button button-quiet" disabled={busy} onClick={() => setAsking(false)}>
                  Do not delete
                </button>
              </div>
            </div>
          )}
        </section>
      )}
    </div>
  )
}
