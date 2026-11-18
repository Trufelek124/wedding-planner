import { Link, useLocation, useParams } from 'react-router'
import { Meter } from '../budget/Meter.tsx'
import { listInspirations } from '../inspirations/inspirationsApi.ts'
import { nameOf } from '../inspirations/labels.ts'
import { money } from '../money/money.ts'
import { EntryMeta } from '../planning/EntryMeta.tsx'
import { listCategories, listEntries } from '../planning/planningApi.ts'
import { TaskRow } from '../planning/TaskRow.tsx'
import { useLoaded } from '../planning/useLoaded.ts'
import { useStatusChange } from '../planning/useStatusChange.ts'
import { PageRetry, PageWaiting } from '../ui/Screen.tsx'
import { Warning } from '../ui/Warning.tsx'
import { useCurrentWedding } from '../wedding/WeddingContext.ts'
import { getVendor, phoneLink, siteName, VENDOR_STATUS_LABEL } from './vendorsApi.ts'

function cameFrom(state: unknown): string | null {
  return typeof state === 'object' && state !== null && 'back' in state && typeof state.back === 'string' ? state.back : null
}

/**
 * Everything about one vendor on one page: how to reach them, what was agreed and what is paid,
 * and the plan items, tasks, payments and inspirations that concern them.
 */
export function VendorPage() {
  const { id = '' } = useParams()
  const { currency } = useCurrentWedding()
  const location = useLocation()
  const list = cameFrom(location.state) ?? '/vendors'
  const here = `/vendors/${id}`

  const vendor = useLoaded(`vendor-${id}`, (signal) => getVendor(id, signal))
  const entries = useLoaded(`vendor-entries-${id}`, (signal) => listEntries({ vendorId: id, sort: 'DUE_DATE' }, signal))
  const inspirations = useLoaded(`vendor-inspirations-${id}`, (signal) => listInspirations({ vendorId: id }, signal))
  const categories = useLoaded('categories', listCategories)
  // A payment ticked off here changes what is paid: the vendor's sums are read again with the list.
  const status = useStatusChange(() => {
    entries.reload()
    vendor.reload()
  })

  const all = [vendor, entries, inspirations, categories]
  if (all.some((one) => one.state.status === 'failed')) {
    return (
      <PageRetry
        message="This vendor could not be loaded. It may have been deleted. Try again, or go back to the vendors."
        onRetry={() => all.forEach((one) => one.retry())}
      />
    )
  }
  if (
    vendor.state.status !== 'ready' ||
    entries.state.status !== 'ready' ||
    inspirations.state.status !== 'ready' ||
    categories.state.status !== 'ready'
  ) {
    return <PageWaiting what="Loading…" />
  }

  const it = vendor.state.data
  const categoryList = categories.state.data
  const category = categoryList.find((one) => one.id === it.categoryId)?.name
  const planItems = entries.state.data.filter((entry) => entry.kind === 'PLAN_ITEM')
  const tasks = entries.state.data.filter((entry) => entry.kind === 'TASK')
  const toPay = it.committed - it.paid
  const contact = it.contactPerson !== '' || it.phone !== '' || it.email !== '' || it.website !== '' || it.address !== ''

  return (
    <div className="vendor-page">
      <div className="page-head">
        <h1>{it.name}</h1>
        <Link className="button" to={`${here}/edit`} state={{ back: here }}>
          Change
        </Link>
      </div>
      <p className="entry-meta vendor-facts">
        <span className="vendor-status" data-status={it.status}>
          {VENDOR_STATUS_LABEL[it.status]}
        </span>
        {category !== undefined && <span>{category}</span>}
      </p>

      <section aria-labelledby="vendor-contact">
        <h2 id="vendor-contact">Contact</h2>
        {contact ? (
          <dl className="details">
            {it.contactPerson !== '' && (
              <>
                <dt>Contact person</dt>
                <dd>{it.contactPerson}</dd>
              </>
            )}
            {it.phone !== '' && (
              <>
                <dt>Phone</dt>
                <dd>
                  <a href={phoneLink(it.phone)}>{it.phone}</a>
                </dd>
              </>
            )}
            {it.email !== '' && (
              <>
                <dt>E-mail</dt>
                <dd>
                  <a href={`mailto:${it.email}`}>{it.email}</a>
                </dd>
              </>
            )}
            {it.website !== '' && (
              <>
                <dt>Website</dt>
                <dd>
                  <a href={it.website} target="_blank" rel="noopener noreferrer">
                    {siteName(it.website)}
                    <span className="visually-hidden"> (opens in a new tab)</span>
                  </a>
                </dd>
              </>
            )}
            {it.address !== '' && (
              <>
                <dt>Address</dt>
                <dd className="as-written">{it.address}</dd>
              </>
            )}
          </dl>
        ) : (
          <p className="muted">No contact details yet.</p>
        )}
        {it.notes !== '' && <p className="as-written vendor-notes">{it.notes}</p>}
      </section>

      <section aria-labelledby="vendor-money">
        <h2 id="vendor-money">Money</h2>
        {it.agreedPrice === null && it.committed === 0 ? (
          <p className="muted">Nothing agreed and no payments yet.</p>
        ) : (
          <div className="stack-tight">
            <p>
              {it.agreedPrice === null ? 'No price agreed. ' : `${money(it.agreedPrice, currency)} agreed. `}
              <strong>{money(it.paid, currency)}</strong> paid
              {toPay > 0 && (
                <>
                  , <strong>{money(toPay, currency)}</strong> still to pay
                </>
              )}
              .
            </p>
            {it.committed > 0 && (
              <Meter limit={it.agreedPrice} committed={it.committed} paid={it.paid} currency={currency} about={it.name} limitName="agreed" />
            )}
            {it.agreedPrice !== null && it.committed !== it.agreedPrice && (
              <p className="hint" data-state={it.committed > it.agreedPrice ? 'over' : undefined}>
                {it.committed > it.agreedPrice ? (
                  <>
                    <Warning /> The payments come to {money(it.committed - it.agreedPrice, currency)} more than was agreed.
                  </>
                ) : (
                  `${money(it.agreedPrice - it.committed, currency)} of the agreed price has no payment yet.`
                )}
              </p>
            )}
          </div>
        )}
      </section>

      {status.failed && (
        <p role="alert" className="alert">
          That change was not saved. Try again.
        </p>
      )}

      <section className="group" aria-labelledby="vendor-plan">
        <h2 id="vendor-plan">Plan items</h2>
        {planItems.length === 0 ? (
          <p className="muted">None yet.</p>
        ) : (
          <ul className="entries">
            {planItems.map((item) => (
              <li key={item.id} className="entry" data-done={item.status === 'DONE' || item.status === 'DROPPED'}>
                <div className="entry-body">
                  <Link className="entry-title" to={`/entries/${item.id}`} state={{ back: here }}>
                    {item.title}
                  </Link>
                  <EntryMeta entry={item} categories={categoryList} />
                </div>
              </li>
            ))}
          </ul>
        )}
        <p className="section-add">
          <Link to={`/entries/new?kind=PLAN_ITEM&vendor=${it.id}`} state={{ back: here }}>
            Add a plan item for this vendor
          </Link>
        </p>
      </section>

      <section className="group" aria-labelledby="vendor-tasks">
        <h2 id="vendor-tasks">Tasks and payments</h2>
        {tasks.length === 0 ? (
          <p className="muted">None yet.</p>
        ) : (
          <ul className="entries">
            {tasks.map((task) => (
              <TaskRow
                key={task.id}
                task={task}
                categories={categoryList}
                status={status.shown(task)}
                busy={status.pending?.id === task.id}
                back={here}
                onStatus={status.change}
              />
            ))}
          </ul>
        )}
        <p className="section-add">
          <Link to={`/entries/new?kind=TASK&vendor=${it.id}`} state={{ back: here }}>
            Add a task or payment for this vendor
          </Link>
        </p>
      </section>

      <section className="group" aria-labelledby="vendor-ideas">
        <h2 id="vendor-ideas">Inspirations</h2>
        {inspirations.state.data.length === 0 ? (
          <p className="muted">None yet.</p>
        ) : (
          <ul className="entries">
            {inspirations.state.data.map((idea) => (
              <li key={idea.id} className="entry">
                <div className="entry-body">
                  <Link className="entry-title" to={`/inspirations/${idea.id}`} state={{ back: here }}>
                    {nameOf(idea)}
                  </Link>
                  {idea.note !== '' && <p className="entry-meta">{idea.note}</p>}
                </div>
              </li>
            ))}
          </ul>
        )}
        <p className="section-add">
          <Link to="/inspirations/new" state={{ back: here, vendorId: it.id }}>
            Add an inspiration for this vendor
          </Link>
        </p>
      </section>

      <p>
        <Link to={list}>Back to the vendors</Link>
      </p>
    </div>
  )
}
