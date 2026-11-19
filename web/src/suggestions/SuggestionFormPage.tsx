import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router'
import { ApiError } from '../api/client.ts'
import { moneyToType, moneyTyped } from '../money/money.ts'
import { PAYMENT_KIND_LABEL } from '../planning/labels.ts'
import { listCategories, type Category, type PaymentKind } from '../planning/planningApi.ts'
import { useLoaded } from '../planning/useLoaded.ts'
import { Field } from '../ui/Field.tsx'
import { PageRetry, PageWaiting } from '../ui/Screen.tsx'
import { listVendors, type Vendor } from '../vendors/vendorsApi.ts'
import { useCurrentWedding } from '../wedding/WeddingContext.ts'
import { alreadyDecided, decide, isAlreadyDecided, type Decision, type Notice, type Trouble } from './decisions.ts'
import { NoticeText } from './NoticeText.tsx'
import { Decided, Proposal, Source, type Names } from './parts.tsx'
import {
  acceptSuggestion,
  KIND_LABEL,
  getSuggestion,
  type Suggestion,
  type SuggestionAcceptance,
  type SuggestionKind,
} from './suggestionsApi.ts'
import { useWaiting } from './WaitingContext.ts'

const PAYMENT_KINDS: PaymentKind[] = ['DEPOSIT', 'INSTALMENT', 'FINAL']

/** The fields each kind has: a message about any other field is said above the form, so that none is lost. */
const FIELDS: Record<SuggestionKind, string[]> = {
  TASK: ['title', 'notes', 'dueDate', 'categoryId', 'vendorId'],
  PAYMENT: ['title', 'notes', 'dueDate', 'categoryId', 'vendorId', 'amount', 'paymentKind'],
  PLAN_ITEM: ['title', 'notes', 'startDate', 'dueDate', 'categoryId', 'vendorId'],
  VENDOR: ['title', 'notes', 'categoryId', 'vendorEmail', 'vendorPhone', 'vendorWebsite'],
}

function cameFrom(state: unknown): string | null {
  return typeof state === 'object' && state !== null && 'back' in state && typeof state.back === 'string' ? state.back : null
}

/** `/suggestions/:id`: the proposal's values, to change before accepting, beside the mail's own words. */
export function SuggestionFormPage() {
  const { id = '' } = useParams()
  const wedding = useCurrentWedding()
  const waiting = useWaiting()
  const location = useLocation()
  const back = cameFrom(location.state) ?? '/suggestions'
  const backLabel = back.startsWith('/suggestions') ? 'Back to the suggestions' : 'Back'
  const one = useLoaded(id, (signal) => getSuggestion(id, signal))
  const categories = useLoaded('categories', listCategories)
  const vendors = useLoaded('vendors', (signal) => listVendors({}, signal))
  const [said, setSaid] = useState<Notice | null>(null)
  const sayBox = useRef<HTMLDivElement>(null)
  const saidKey = said?.key

  // What was said about a decision takes the keyboard, as in the inbox.
  useEffect(() => {
    if (saidKey !== undefined) {
      sayBox.current?.focus({ preventScroll: true })
    }
  }, [saidKey])

  // One request at a time, and nothing is sent again by the page itself.
  const sending = useRef(false)
  const [sendingNow, setSendingNow] = useState<Decision | null>(null)
  const [trouble, setTrouble] = useState<Trouble | null>(null)

  if (one.state.status === 'failed' || categories.state.status === 'failed' || vendors.state.status === 'failed') {
    return (
      <PageRetry
        message="This could not be loaded. Try again, or go back to the suggestions."
        onRetry={() => {
          one.retry()
          categories.retry()
          vendors.retry()
        }}
      />
    )
  }
  if (one.state.status === 'loading' || categories.state.status === 'loading' || vendors.state.status === 'loading') {
    return <PageWaiting what="Loading…" />
  }

  const suggestion = one.state.data
  if (suggestion === null) {
    return (
      <div className="stack page-note">
        <p role="alert" className="alert">
          The e-mail this came from is no longer here: the suggestion does not exist any more.
        </p>
        <div>
          <Link className="button" to={back}>
            {backLabel}
          </Link>
        </div>
      </div>
    )
  }

  const shown: Suggestion = suggestion
  const names: Names = {
    categories: new Map(categories.state.data.map((one) => [one.id, one.name])),
    vendors: new Map(vendors.state.data.map((one) => [one.id, one.name])),
  }

  async function tellAlreadyDecided(tried: Suggestion) {
    setSaid(await alreadyDecided(tried.id, tried.title, 'accept', wedding.members, wedding.timeZone))
    // The page then shows the suggestion as it now stands: decided.
    one.reload()
    waiting.reload()
  }

  async function settle(decision: Decision) {
    if (sending.current) {
      return
    }
    sending.current = true
    setSendingNow(decision)
    setTrouble(null)
    const outcome = await decide(shown, decision, wedding.members, wedding.timeZone)
    if ('trouble' in outcome) {
      setTrouble(outcome.trouble)
    } else {
      setSaid(outcome.notice)
      one.reload()
      waiting.reload()
    }
    sending.current = false
    setSendingNow(null)
  }

  return (
    <div className="form-page">
      {said !== null && (
        <div className="notices" role="group" aria-label="What happened" tabIndex={-1} ref={sayBox}>
          <p className={said.tone === 'alert' ? 'alert' : 'notice'} role={said.tone === 'alert' ? 'alert' : 'status'}>
            <NoticeText notice={said} back={back} />
          </p>
        </div>
      )}
      {suggestion.state !== 'WAITING' ? (
        <>
          <h1>{suggestion.title}</h1>
          <section className="suggestion" aria-label="Where it comes from">
            <p className="suggestion-kind">{KIND_LABEL[suggestion.kind]}</p>
            <Proposal suggestion={suggestion} names={names} />
            <Source suggestion={suggestion} />
            <Decided suggestion={suggestion} back={back} />
            {trouble !== null && (
              <div role="alert" className="alert stack-tight">
                {trouble.kind === 'unreachable' ? (
                  <p>The server cannot be reached, so nothing was decided. Try again.</p>
                ) : (
                  <p>{trouble.kind === 'other' ? trouble.text : trouble.messages.join(' ')}</p>
                )}
              </div>
            )}
            {suggestion.state === 'ADDED' && (
              <div className="actions suggestion-actions">
                <button type="button" className="button" disabled={sendingNow !== null} onClick={() => void settle('undo')}>
                  {sendingNow === 'undo' ? 'Undoing…' : 'Undo'}
                </button>
                <button type="button" className="button button-quiet" disabled={sendingNow !== null} onClick={() => void settle('keep')}>
                  {sendingNow === 'keep' ? 'Keeping…' : 'Keep'}
                </button>
              </div>
            )}
          </section>
          <div>
            <Link className="button button-quiet" to={back}>
              {backLabel}
            </Link>
          </div>
        </>
      ) : (
        <SuggestionForm
          key={suggestion.id}
          suggestion={suggestion}
          categories={categories.state.data}
          vendors={vendors.state.data}
          back={back}
          onAlreadyDecided={() => void tellAlreadyDecided(suggestion)}
        />
      )}
    </div>
  )
}

const orNull = (text: string) => (text.trim() === '' ? null : text.trim())

interface FormProps {
  suggestion: Suggestion
  categories: Category[]
  vendors: Vendor[]
  back: string
  onAlreadyDecided: () => void
}

function SuggestionForm({ suggestion, categories, vendors, back, onAlreadyDecided }: FormProps) {
  const { currency } = useCurrentWedding()
  const waiting = useWaiting()
  const navigate = useNavigate()
  const kind = suggestion.kind

  const [title, setTitle] = useState(suggestion.title)
  const [notes, setNotes] = useState(suggestion.notes)
  const [startDate, setStartDate] = useState(suggestion.startDate ?? '')
  const [dueDate, setDueDate] = useState(suggestion.dueDate ?? '')
  const [categoryId, setCategoryId] = useState(suggestion.categoryId ?? '')
  const proposedVendorKnown = suggestion.vendorId !== null && vendors.some((one) => one.id === suggestion.vendorId)
  const [vendorId, setVendorId] = useState(proposedVendorKnown ? (suggestion.vendorId ?? '') : '')
  const [amountTyped, setAmountTyped] = useState(suggestion.amount === null ? '' : moneyToType(suggestion.amount, currency))
  const [paymentKind, setPaymentKind] = useState<PaymentKind | ''>(suggestion.paymentKind ?? '')
  const [email, setEmail] = useState(suggestion.vendorEmail ?? '')
  const [phone, setPhone] = useState(suggestion.vendorPhone ?? '')
  const [website, setWebsite] = useState(suggestion.vendorWebsite ?? '')
  const [amountProblem, setAmountProblem] = useState<string | null>(null)

  // One request at a time, and nothing is sent again by the page itself.
  const sending = useRef(false)
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState<ApiError | 'unreachable' | null>(null)

  function fieldError(field: string): string | undefined {
    return failure instanceof ApiError ? failure.fieldMessage(field) : undefined
  }

  function acceptance(amount: number | null): SuggestionAcceptance {
    const entry = kind !== 'VENDOR'
    return {
      title: title.trim(),
      notes: orNull(notes),
      startDate: kind === 'PLAN_ITEM' ? orNull(startDate) : null,
      dueDate: entry ? orNull(dueDate) : null,
      amount: kind === 'PAYMENT' ? amount : null,
      paymentKind: kind === 'PAYMENT' && paymentKind !== '' ? paymentKind : null,
      categoryId: categoryId === '' ? null : categoryId,
      vendorId: entry && vendorId !== '' ? vendorId : null,
      // Nothing is made of a name the planner does not have; it is kept as the mail gave it.
      vendorName: entry && vendorId === '' ? suggestion.vendorName : null,
      vendorEmail: kind === 'VENDOR' ? orNull(email) : null,
      vendorPhone: kind === 'VENDOR' ? orNull(phone) : null,
      vendorWebsite: kind === 'VENDOR' ? orNull(website) : null,
    }
  }

  async function save(event: FormEvent) {
    event.preventDefault()
    if (sending.current) {
      return
    }
    const amount = amountTyped.trim() === '' ? null : moneyTyped(amountTyped, currency)
    if (kind === 'PAYMENT' && amountTyped.trim() !== '' && amount === null) {
      setAmountProblem('Enter an amount like 2500 or 2500.50.')
      return
    }
    setAmountProblem(null)
    sending.current = true
    setBusy(true)
    setFailure(null)
    try {
      const accepted = await acceptSuggestion(suggestion.id, acceptance(amount))
      waiting.reload()
      await navigate(back, { state: { decided: accepted } })
    } catch (error) {
      if (isAlreadyDecided(error)) {
        onAlreadyDecided()
      } else {
        setFailure(error instanceof ApiError ? error : 'unreachable')
      }
      sending.current = false
      setBusy(false)
    }
  }

  const problems = failure instanceof ApiError ? (failure.problem.fieldProblems ?? []) : []
  // What the server says about a field this form does not have is said above it rather than lost.
  const unplaced = problems.filter((one) => !FIELDS[kind].includes(one.field)).map((one) => one.message)
  const general =
    failure === 'unreachable'
      ? 'The server cannot be reached, so nothing was decided. Check your connection and try again.'
      : failure !== null && problems.length === 0
        ? (failure.problem.detail ?? 'That did not work. Try again.')
        : null

  const vendorHint =
    suggestion.vendorId !== null && !proposedVendorKnown
      ? 'The vendor the import found is not in the planner any more.'
      : suggestion.vendorId === null && suggestion.vendorName !== null && suggestion.vendorName !== ''
        ? `The mail names “${suggestion.vendorName}”, who is not in the planner yet.`
        : undefined

  const categoryField = (
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
  )

  return (
    <>
      <h1>Change and accept</h1>
      <p className="muted">
        Proposed as: {KIND_LABEL[kind]}. What you save here is what is added to the planner; the mail’s own words stay in view.
      </p>

      <section className="suggestion" aria-label="Where it comes from">
        <Source suggestion={suggestion} />
      </section>

      {general !== null && (
        <p role="alert" className="alert">
          {general}
        </p>
      )}
      {unplaced.length > 0 && (
        <ul role="alert" className="alert">
          {unplaced.map((message) => (
            <li key={message}>{message}</li>
          ))}
        </ul>
      )}

      <form className="stack" onSubmit={(event) => void save(event)} noValidate aria-label="The proposal">
        <Field label={kind === 'VENDOR' ? 'Name' : 'Title'} error={fieldError('title')}>
          {(control) => (
            <input {...control} name="title" maxLength={200} required value={title} onChange={(event) => setTitle(event.target.value)} />
          )}
        </Field>

        {kind === 'PLAN_ITEM' && (
          <div className="two-columns">
            <Field label="Starts" optional error={fieldError('startDate')}>
              {(control) => (
                <input {...control} type="date" name="startDate" value={startDate} onChange={(event) => setStartDate(event.target.value)} />
              )}
            </Field>
            <Field label="Ends" optional error={fieldError('dueDate')}>
              {(control) => (
                <input {...control} type="date" name="dueDate" value={dueDate} onChange={(event) => setDueDate(event.target.value)} />
              )}
            </Field>
          </div>
        )}
        {(kind === 'TASK' || kind === 'PAYMENT') && (
          <Field label="Due" optional error={fieldError('dueDate')}>
            {(control) => (
              <input {...control} type="date" name="dueDate" value={dueDate} onChange={(event) => setDueDate(event.target.value)} />
            )}
          </Field>
        )}

        {kind === 'PAYMENT' && (
          <div className="two-columns">
            <Field label={`Amount in ${currency}`} error={amountProblem ?? fieldError('amount')}>
              {(control) => (
                <input
                  {...control}
                  name="amount"
                  inputMode="decimal"
                  autoComplete="off"
                  value={amountTyped}
                  onChange={(event) => {
                    setAmountTyped(event.target.value)
                    setAmountProblem(null)
                  }}
                />
              )}
            </Field>
            <Field label="Kind of payment" optional error={fieldError('paymentKind')}>
              {(control) => (
                <select
                  {...control}
                  name="paymentKind"
                  value={paymentKind}
                  onChange={(event) => setPaymentKind(event.target.value as PaymentKind | '')}
                >
                  <option value="">Not said</option>
                  {PAYMENT_KINDS.map((choice) => (
                    <option key={choice} value={choice}>
                      {PAYMENT_KIND_LABEL[choice]}
                    </option>
                  ))}
                </select>
              )}
            </Field>
          </div>
        )}

        {kind === 'VENDOR' ? (
          <>
            <div className="two-columns">
              <Field label="E-mail" optional error={fieldError('vendorEmail')}>
                {(control) => (
                  <input
                    {...control}
                    type="email"
                    name="vendorEmail"
                    maxLength={254}
                    autoComplete="off"
                    autoCapitalize="none"
                    value={email}
                    onChange={(event) => setEmail(event.target.value)}
                  />
                )}
              </Field>
              <Field label="Phone" optional error={fieldError('vendorPhone')}>
                {(control) => (
                  <input
                    {...control}
                    type="tel"
                    name="vendorPhone"
                    maxLength={40}
                    autoComplete="off"
                    value={phone}
                    onChange={(event) => setPhone(event.target.value)}
                  />
                )}
              </Field>
            </div>
            <Field label="Website" optional error={fieldError('vendorWebsite')}>
              {(control) => (
                <input
                  {...control}
                  name="vendorWebsite"
                  inputMode="url"
                  maxLength={2000}
                  autoComplete="off"
                  autoCapitalize="none"
                  value={website}
                  onChange={(event) => setWebsite(event.target.value)}
                />
              )}
            </Field>
            {categoryField}
          </>
        ) : (
          <div className="two-columns">
            {categoryField}
            <Field label="Vendor" optional hint={vendorHint} error={fieldError('vendorId')}>
              {(control) => (
                <select {...control} name="vendorId" value={vendorId} onChange={(event) => setVendorId(event.target.value)}>
                  <option value="">No vendor</option>
                  {vendors.map((vendor) => (
                    <option key={vendor.id} value={vendor.id}>
                      {vendor.name}
                    </option>
                  ))}
                </select>
              )}
            </Field>
          </div>
        )}

        <Field label="Notes" optional error={fieldError('notes')}>
          {(control) => (
            <textarea {...control} name="notes" rows={4} maxLength={10000} value={notes} onChange={(event) => setNotes(event.target.value)} />
          )}
        </Field>

        <div className="actions">
          <button type="submit" className="button" disabled={busy}>
            {busy ? 'Saving…' : 'Accept with these values'}
          </button>
          <Link className="button button-quiet" to={back}>
            Cancel
          </Link>
        </div>
      </form>
    </>
  )
}
