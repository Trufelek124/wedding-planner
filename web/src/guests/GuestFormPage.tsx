import { useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router'
import { ApiError } from '../api/client.ts'
import { useLoaded } from '../planning/useLoaded.ts'
import { Field } from '../ui/Field.tsx'
import { PageRetry, PageWaiting } from '../ui/Screen.tsx'
import { useCurrentWedding } from '../wedding/WeddingContext.ts'
import {
  changeGuest,
  createGuest,
  deleteGuest,
  getGuest,
  GUEST_STATUS_LABEL,
  GUEST_STATUSES,
  listHouseholds,
  type Guest,
  type GuestStatus,
  type Household,
} from './guestsApi.ts'

/** The page for adding a guest (`/guests/new`) and for changing one (`/guests/:id`). */
export function GuestFormPage() {
  const { id } = useParams()
  const guest = useLoaded(id ?? 'new', (signal) => (id === undefined ? Promise.resolve(null) : getGuest(id, signal)))
  const households = useLoaded('households', listHouseholds)

  if (guest.state.status === 'failed' || households.state.status === 'failed') {
    return (
      <PageRetry
        message="This could not be loaded. It may have been deleted. Try again, or go back to the guest list."
        onRetry={() => {
          guest.retry()
          households.retry()
        }}
      />
    )
  }
  if (guest.state.status === 'loading' || households.state.status === 'loading') {
    return <PageWaiting what="Loading…" />
  }
  // Loading the latest version shows the page waiting in between, so the form starts again from it.
  return <GuestForm key={guest.state.data?.id ?? 'new'} existing={guest.state.data} households={households.state.data} onReload={guest.retry} />
}

function cameFrom(state: unknown): string | null {
  return typeof state === 'object' && state !== null && 'back' in state && typeof state.back === 'string' ? state.back : null
}

/** Whose guest, as the form's choice says it: a member's id, or "both". */
const BOTH = 'both'

function GuestForm({ existing, households, onReload }: { existing: Guest | null; households: Household[]; onReload: () => void }) {
  const wedding = useCurrentWedding()
  const navigate = useNavigate()
  const location = useLocation()
  const back = cameFrom(location.state) ?? '/guests'
  const you = wedding.members.find((member) => member.you)

  const [name, setName] = useState(existing?.name ?? '')
  const [status, setStatus] = useState<GuestStatus>(existing?.status ?? 'NOT_INVITED')
  // A new guest is most often the guest of whoever is typing.
  const [side, setSide] = useState(existing === null ? (you?.id ?? BOTH) : (existing.sideMemberId ?? BOTH))
  const [householdId, setHouseholdId] = useState(existing?.householdId ?? '')
  const [plusOne, setPlusOne] = useState(existing?.plusOne ?? false)
  const [children, setChildren] = useState(String(existing?.children ?? 0))
  const [needsAccommodation, setNeedsAccommodation] = useState(existing?.needsAccommodation ?? false)
  const [dietaryNeeds, setDietaryNeeds] = useState(existing?.dietaryNeeds ?? '')
  const [phone, setPhone] = useState(existing?.phone ?? '')
  const [email, setEmail] = useState(existing?.email ?? '')
  const [notes, setNotes] = useState(existing?.notes ?? '')

  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState<ApiError | 'unreachable' | null>(null)
  const [childrenProblem, setChildrenProblem] = useState<string | null>(null)
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
    const howMany = children.trim() === '' ? 0 : Number(children)
    if (!Number.isInteger(howMany) || howMany < 0 || howMany > 20) {
      setChildrenProblem('Enter a number from 0 to 20.')
      return
    }
    const fields = {
      name: name.trim(),
      sideMemberId: side === BOTH ? null : side,
      householdId: householdId === '' ? null : householdId,
      status,
      plusOne,
      children: howMany,
      dietaryNeeds: dietaryNeeds.trim(),
      needsAccommodation,
      phone: phone.trim(),
      email: email.trim(),
      notes: notes.trim(),
    }
    void attempt(async () => {
      if (existing === null) {
        await createGuest(fields)
      } else {
        await changeGuest(existing.id, { version: existing.version, ...fields })
      }
      await navigate(back)
    })
  }

  function remove() {
    if (existing === null) return
    void attempt(async () => {
      await deleteGuest(existing.id)
      await navigate(back)
    })
  }

  const changedMeanwhile = failure instanceof ApiError && failure.is('urn:wedding-planner:problem:changed-meanwhile')
  const general =
    failure === 'unreachable'
      ? 'The server cannot be reached. Check your connection and try again.'
      : failure !== null && !changedMeanwhile && (failure.problem.fieldProblems ?? []).length === 0
        ? (failure.problem.detail ?? 'That did not work. Try again.')
        : null

  return (
    <div className="form-page">
      <h1>{existing === null ? 'Add a guest' : 'Change this guest'}</h1>

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
          {(control) => (
            <input {...control} name="name" maxLength={120} required autoComplete="off" value={name} onChange={(event) => setName(event.target.value)} />
          )}
        </Field>

        <div className="two-columns">
          <Field label="Status" error={fieldError('status')}>
            {(control) => (
              <select {...control} name="status" value={status} onChange={(event) => setStatus(event.target.value as GuestStatus)}>
                {GUEST_STATUSES.map((one) => (
                  <option key={one} value={one}>
                    {GUEST_STATUS_LABEL[one]}
                  </option>
                ))}
              </select>
            )}
          </Field>
          <Field label="Household" optional hint="Whom the guest is invited with." error={fieldError('householdId')}>
            {(control) => (
              <select {...control} name="householdId" value={householdId} onChange={(event) => setHouseholdId(event.target.value)}>
                <option value="">None</option>
                {households.map((household) => (
                  <option key={household.id} value={household.id}>
                    {household.name}
                  </option>
                ))}
              </select>
            )}
          </Field>
        </div>

        <fieldset className="choice">
          <legend>Whose guest</legend>
          {wedding.members.map((member) => (
            <label key={member.id}>
              <input type="radio" name="side" checked={side === member.id} onChange={() => setSide(member.id)} />
              {member.displayName}’s
            </label>
          ))}
          <label>
            <input type="radio" name="side" checked={side === BOTH} onChange={() => setSide(BOTH)} />
            Both’s
          </label>
          {fieldError('sideMemberId') && <p className="error">{fieldError('sideMemberId')}</p>}
        </fieldset>

        <fieldset className="choice">
          <legend>Comes with</legend>
          <label>
            <input type="checkbox" checked={plusOne} onChange={(event) => setPlusOne(event.target.checked)} />A plus-one
          </label>
          <Field label="Children" error={childrenProblem ?? fieldError('children')}>
            {(control) => (
              <input
                {...control}
                type="number"
                name="children"
                min={0}
                max={20}
                inputMode="numeric"
                value={children}
                onChange={(event) => {
                  setChildren(event.target.value)
                  setChildrenProblem(null)
                }}
              />
            )}
          </Field>
        </fieldset>

        <fieldset className="choice">
          <legend>Needs</legend>
          <label>
            <input type="checkbox" checked={needsAccommodation} onChange={(event) => setNeedsAccommodation(event.target.checked)} />A place to stay
          </label>
          <Field label="Dietary needs" optional error={fieldError('dietaryNeeds')}>
            {(control) => (
              <input {...control} name="dietaryNeeds" maxLength={500} value={dietaryNeeds} onChange={(event) => setDietaryNeeds(event.target.value)} />
            )}
          </Field>
        </fieldset>

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

        <Field label="Notes" optional error={fieldError('notes')}>
          {(control) => (
            <textarea {...control} name="notes" rows={3} maxLength={10000} value={notes} onChange={(event) => setNotes(event.target.value)} />
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
                Take this guest off the list
              </button>
            </div>
          ) : (
            <div className="stack" role="group" aria-label="Delete">
              <p>Take “{existing.name}” off the list? This cannot be undone.</p>
              <div className="actions">
                <button type="button" className="button button-danger" disabled={busy} onClick={remove}>
                  Yes, take them off
                </button>
                <button type="button" className="button button-quiet" disabled={busy} onClick={() => setAsking(false)}>
                  Keep them
                </button>
              </div>
            </div>
          )}
        </section>
      )}
    </div>
  )
}
