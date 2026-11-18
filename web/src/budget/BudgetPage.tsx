import { Link } from 'react-router'
import { money } from '../money/money.ts'
import { NO_CATEGORY } from '../planning/filters.ts'
import { useLoaded } from '../planning/useLoaded.ts'
import { PageRetry, PageWaiting } from '../ui/Screen.tsx'
import { useCurrentWedding } from '../wedding/WeddingContext.ts'
import { fetchBudget, type Budget } from './budgetApi.ts'
import { Meter } from './Meter.tsx'

function count(payments: number): string {
  return payments === 1 ? '1 payment' : `${payments} payments`
}

/** The payments behind a line, in the task list: dropped ones too, so that nothing seems to be missing. */
function paymentsOf(categoryId: string): string {
  return `/tasks?status=all&category=${categoryId}&money=payments`
}

/** The money: what the couple means to spend, what their payments come to, and what is paid. */
export function BudgetPage() {
  const budget = useLoaded('budget', fetchBudget)

  if (budget.state.status === 'failed') {
    return <PageRetry message="The budget could not be loaded. Try again." onRetry={budget.retry} />
  }
  if (budget.state.status === 'loading') {
    return <PageWaiting what="Loading…" />
  }
  return <BudgetView budget={budget.state.data} />
}

function BudgetView({ budget }: { budget: Budget }) {
  const { currency } = useCurrentWedding()
  const nothingYet = budget.total === null && budget.planned === 0 && budget.committed === 0
  const payments = budget.categories.reduce((sum, line) => sum + line.payments, budget.uncategorised.payments)
  const toPay = budget.committed - budget.paid
  // The whole is measured against the total; while there is none, against what the categories add up to.
  const limit = budget.total ?? (budget.planned > 0 ? budget.planned : null)
  const shown = budget.categories.filter((line) => line.planned !== null || line.payments > 0)
  const quiet = budget.categories.filter((line) => line.planned === null && line.payments === 0)

  return (
    <div className="budget-page">
      <div className="page-head">
        <h1>Budget</h1>
        <Link className="button" to="/budget/plan">
          {budget.total === null && budget.planned === 0 ? 'Plan the budget' : 'Change the plan'}
        </Link>
      </div>

      {nothingYet ? (
        <p className="empty">
          No budget yet. Say what you mean to spend, in all or per category. What you pay goes in by itself: a{' '}
          <Link to="/entries/new?kind=TASK">task with an amount</Link> is a payment.
        </p>
      ) : (
        <>
          <dl className="tiles">
            <div className="tile">
              <dt>Budget</dt>
              <dd>{budget.total === null ? 'Not set' : money(budget.total, currency)}</dd>
              {budget.total !== null && (
                <dd className="tile-note" data-state={budget.committed > budget.total ? 'over' : undefined}>
                  {budget.committed > budget.total ? (
                    <>
                      <Warning /> Over by {money(budget.committed - budget.total, currency)}
                    </>
                  ) : (
                    `${money(budget.total - budget.committed, currency)} not committed yet`
                  )}
                </dd>
              )}
            </div>
            <div className="tile">
              <dt>Planned</dt>
              <dd>{money(budget.planned, currency)}</dd>
              {budget.total !== null && budget.planned > 0 && budget.planned !== budget.total && (
                <dd className="tile-note" data-state={budget.planned > budget.total ? 'over' : undefined}>
                  {budget.planned > budget.total ? (
                    <>
                      <Warning /> {money(budget.planned - budget.total, currency)} more than the budget
                    </>
                  ) : (
                    `${money(budget.total - budget.planned, currency)} of the budget not planned`
                  )}
                </dd>
              )}
            </div>
            <div className="tile">
              <dt>Committed</dt>
              <dd>{money(budget.committed, currency)}</dd>
              <dd className="tile-note">{payments === 0 ? 'No payments yet' : count(payments)}</dd>
            </div>
            <div className="tile">
              <dt>Paid</dt>
              <dd>{money(budget.paid, currency)}</dd>
              <dd className="tile-note">{toPay === 0 ? 'Nothing left to pay' : `${money(toPay, currency)} still to pay`}</dd>
            </div>
          </dl>

          {budget.committed > 0 && (
            <div className="meter-whole">
              <Meter
                limit={limit}
                committed={budget.committed}
                paid={budget.paid}
                currency={currency}
                about="The whole budget"
                limitName={budget.total === null ? 'planned' : 'budget'}
              />
              <ul className="meter-key" aria-hidden="true">
                <li data-part="paid">Paid</li>
                <li data-part="due">Still to pay</li>
                {limit !== null && budget.committed > limit && <li data-part="over">Over</li>}
              </ul>
            </div>
          )}

          <section aria-labelledby="by-category">
            <h2 id="by-category">By category</h2>
            {shown.length === 0 && budget.uncategorised.payments === 0 ? (
              <p className="muted">Nothing is planned or paid in any category yet.</p>
            ) : (
              <ul className="meter-list">
                {shown.map((line) => (
                  <li key={line.categoryId}>
                    <h3>{line.name}</h3>
                    {line.committed > 0 || line.planned !== null ? (
                      <Meter
                        limit={line.planned}
                        committed={line.committed}
                        paid={line.paid}
                        currency={currency}
                        about={line.name}
                        limitName="planned"
                      />
                    ) : (
                      <span />
                    )}
                    <p className="meter-figures">
                      {line.planned === null
                        ? `${money(line.committed, currency)} committed`
                        : `${money(line.committed, currency)} of ${money(line.planned, currency)}`}
                    </p>
                    <p className="meter-state">
                      <State planned={line.planned} committed={line.committed} currency={currency} />
                      <span>{money(line.paid, currency)} paid</span>
                      {line.payments > 0 ? (
                        <Link to={paymentsOf(line.categoryId)} aria-label={`${count(line.payments)} for ${line.name}`}>
                          {count(line.payments)}
                        </Link>
                      ) : (
                        <span>No payments yet</span>
                      )}
                    </p>
                  </li>
                ))}
                {budget.uncategorised.payments > 0 && (
                  <li>
                    <h3>No category</h3>
                    <Meter
                      limit={null}
                      committed={budget.uncategorised.committed}
                      paid={budget.uncategorised.paid}
                      currency={currency}
                      about="No category"
                      limitName="planned"
                    />
                    <p className="meter-figures">{money(budget.uncategorised.committed, currency)} committed</p>
                    <p className="meter-state">
                      <span>Give these a category to count them against a plan</span>
                      <span>{money(budget.uncategorised.paid, currency)} paid</span>
                      <Link to={paymentsOf(NO_CATEGORY)} aria-label={`${count(budget.uncategorised.payments)} without a category`}>
                        {count(budget.uncategorised.payments)}
                      </Link>
                    </p>
                  </li>
                )}
              </ul>
            )}
            {quiet.length > 0 && shown.length > 0 && (
              <p className="hint meter-rest">Nothing planned or paid yet for: {quiet.map((line) => line.name).join(', ')}.</p>
            )}
          </section>
        </>
      )}
    </div>
  )
}

/** Where a category stands against its plan, in words; a sign goes with the words where it is over. */
function State({ planned, committed, currency }: { planned: number | null; committed: number; currency: string }) {
  if (planned === null) {
    return <span>No amount planned</span>
  }
  if (committed > planned) {
    return (
      <span data-state="over">
        <Warning /> Over the plan by {money(committed - planned, currency)}
      </span>
    )
  }
  return <span>{committed === planned ? 'Exactly as planned' : `${money(planned - committed, currency)} left`}</span>
}

function Warning() {
  return (
    <svg className="warning-sign" viewBox="0 0 16 16" aria-hidden="true">
      <path d="M8 1.5 15 14H1L8 1.5Z" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinejoin="round" />
      <path d="M8 6v4M8 11.6v.4" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
    </svg>
  )
}
