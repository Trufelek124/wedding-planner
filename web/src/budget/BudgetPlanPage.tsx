import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import { ApiError } from '../api/client.ts'
import { money, moneyToType, moneyTyped } from '../money/money.ts'
import { useLoaded } from '../planning/useLoaded.ts'
import { Field } from '../ui/Field.tsx'
import { PageRetry, PageWaiting } from '../ui/Screen.tsx'
import { useCurrentWedding } from '../wedding/WeddingContext.ts'
import { changeBudget, fetchBudget, type Budget } from './budgetApi.ts'

const NOT_AN_AMOUNT = 'Enter an amount like 2500 or 2500.50, or leave it empty.'

/** The page where the couple says what they mean to spend: in all, and per category. */
export function BudgetPlanPage() {
  const budget = useLoaded('budget', fetchBudget)

  if (budget.state.status === 'failed') {
    return <PageRetry message="The budget could not be loaded. Try again." onRetry={budget.retry} />
  }
  if (budget.state.status === 'loading') {
    return <PageWaiting what="Loading…" />
  }
  // Loading the latest version shows the page waiting in between, so the form starts again from it.
  return <BudgetPlan budget={budget.state.data} onReload={budget.retry} />
}

function BudgetPlan({ budget, onReload }: { budget: Budget; onReload: () => void }) {
  const { currency } = useCurrentWedding()
  const navigate = useNavigate()
  const typed = (minor: number | null) => (minor === null ? '' : moneyToType(minor, currency))

  const [total, setTotal] = useState(typed(budget.total))
  const [planned, setPlanned] = useState<Record<string, string>>(
    Object.fromEntries(budget.categories.map((line) => [line.categoryId, typed(line.planned)])),
  )
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState<ApiError | 'unreachable' | null>(null)
  /** Fields whose text is not an amount, found before anything is sent. */
  const [unread, setUnread] = useState<Set<string>>(new Set())
  /** The categories of the last request, in the order sent: the server names a wrong one by its place. */
  const [sent, setSent] = useState<string[]>([])

  const read = (text: string) => (text.trim() === '' ? null : moneyTyped(text, currency))
  /** What was said about a field's old text does not stand over the new one. */
  const edited = (field: string) => {
    if (unread.has(field)) {
      setUnread(new Set([...unread].filter((other) => other !== field)))
    }
  }
  const sum = budget.categories.reduce((all, line) => all + (read(planned[line.categoryId] ?? '') ?? 0), 0)
  const totalRead = read(total)

  function save(event: FormEvent) {
    event.preventDefault()
    const wrong = new Set<string>()
    if (total.trim() !== '' && totalRead === null) {
      wrong.add('total')
    }
    const amounts: { categoryId: string; amount: number }[] = []
    for (const line of budget.categories) {
      const text = planned[line.categoryId] ?? ''
      const amount = read(text)
      if (text.trim() !== '' && amount === null) {
        wrong.add(line.categoryId)
      } else if (amount !== null) {
        amounts.push({ categoryId: line.categoryId, amount })
      }
    }
    setUnread(wrong)
    if (wrong.size > 0) {
      return
    }
    setBusy(true)
    setFailure(null)
    setSent(amounts.map((one) => one.categoryId))
    changeBudget({ version: budget.version, total: totalRead, planned: amounts })
      .then(() => navigate('/budget'))
      .catch((error: unknown) => {
        setFailure(error instanceof ApiError ? error : 'unreachable')
        setBusy(false)
      })
  }

  function problemOf(field: string): string | undefined {
    if (unread.has(field)) {
      return NOT_AN_AMOUNT
    }
    if (!(failure instanceof ApiError)) {
      return undefined
    }
    if (field === 'total') {
      return failure.fieldMessage('total')
    }
    const place = sent.indexOf(field)
    return place < 0 ? undefined : (failure.fieldMessage(`planned[${place}].amount`) ?? failure.fieldMessage(`planned[${place}].categoryId`))
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
      <h1>Plan the budget</h1>
      <p className="muted">Amounts in {currency}. Leave empty what you have not decided.</p>

      {changedMeanwhile && (
        <div role="alert" className="alert stack">
          <p>Someone else changed the budget in the meantime, so your change was not saved.</p>
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
        <Field label="Budget for everything" optional error={problemOf('total')}>
          {(control) => (
            <input {...control} name="total" inputMode="decimal" autoComplete="off" value={total} onChange={(event) => { setTotal(event.target.value); edited('total') }} />
          )}
        </Field>

        <fieldset className="choice plan-lines">
          <legend>Per category</legend>
          {budget.categories.map((line) => (
            <Field
              key={line.categoryId}
              label={line.name}
              optional
              hint={line.committed > 0 ? `Committed so far: ${money(line.committed, currency)}` : undefined}
              error={problemOf(line.categoryId)}
            >
              {(control) => (
                <input
                  {...control}
                  inputMode="decimal"
                  autoComplete="off"
                  value={planned[line.categoryId] ?? ''}
                  onChange={(event) => {
                    setPlanned({ ...planned, [line.categoryId]: event.target.value })
                    edited(line.categoryId)
                  }}
                />
              )}
            </Field>
          ))}
        </fieldset>

        <p className="plan-sum">
          The categories together: <strong>{money(sum, currency)}</strong>
          {totalRead !== null && sum !== totalRead && (
            <>
              {' — '}
              {sum > totalRead
                ? `${money(sum - totalRead, currency)} more than the budget for everything`
                : `${money(totalRead - sum, currency)} of the budget not planned`}
            </>
          )}
        </p>

        <div className="actions">
          <button type="submit" className="button" disabled={busy}>
            {busy ? 'Saving…' : 'Save'}
          </button>
          <Link className="button button-quiet" to="/budget">
            Cancel
          </Link>
        </div>
      </form>
    </div>
  )
}
