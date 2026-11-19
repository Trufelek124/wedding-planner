import { Link } from 'react-router'
import { money } from '../money/money.ts'
import { PAYMENT_KIND_LABEL, shortDate } from '../planning/labels.ts'
import { useCurrentWedding } from '../wedding/WeddingContext.ts'
import { atTime, madeThing, mailAddress, memberName, type Suggestion } from './suggestionsApi.ts'

/** What the planner knows by id, to say a category or a vendor by its name. */
export interface Names {
  categories: Map<string, string>
  vendors: Map<string, string>
}

/**
 * What is proposed, in the planner's own words. Everything here was read out of a mail: it is text, and only text.
 */
export function Proposal({ suggestion, names }: { suggestion: Suggestion; names: Names }) {
  const { currency } = useCurrentWedding()
  const rows: [string, string][] = []
  const isVendor = suggestion.kind === 'VENDOR'

  if (suggestion.kind === 'PLAN_ITEM') {
    if (suggestion.startDate !== null) rows.push(['Starts', shortDate(suggestion.startDate)])
    if (suggestion.dueDate !== null) rows.push(['Ends', shortDate(suggestion.dueDate)])
  } else if (suggestion.dueDate !== null) {
    rows.push(['Due', shortDate(suggestion.dueDate)])
  }
  if (suggestion.amount !== null) {
    const kind = suggestion.paymentKind === null ? null : PAYMENT_KIND_LABEL[suggestion.paymentKind].toLowerCase()
    rows.push(['Amount', kind === null ? money(suggestion.amount, currency) : `${money(suggestion.amount, currency)}, ${kind}`])
  }
  if (suggestion.categoryId !== null) {
    rows.push(['Category', names.categories.get(suggestion.categoryId) ?? 'A category the planner no longer has'])
  }
  if (!isVendor) {
    if (suggestion.vendorId !== null) {
      rows.push(['Vendor', names.vendors.get(suggestion.vendorId) ?? 'A vendor the planner no longer has'])
    } else if (suggestion.vendorName !== null && suggestion.vendorName !== '') {
      rows.push(['Vendor', `${suggestion.vendorName} (not in the planner yet)`])
    }
  } else {
    if (suggestion.vendorPhone !== null && suggestion.vendorPhone !== '') rows.push(['Phone', suggestion.vendorPhone])
    if (suggestion.vendorEmail !== null && suggestion.vendorEmail !== '') rows.push(['E-mail', suggestion.vendorEmail])
    if (suggestion.vendorWebsite !== null && suggestion.vendorWebsite !== '') rows.push(['Website', suggestion.vendorWebsite])
  }
  if (suggestion.notes !== '') rows.push(['Notes', suggestion.notes])

  if (rows.length === 0) {
    return null
  }
  return (
    <dl className="details suggestion-facts" aria-label="What is proposed">
      {rows.map(([name, value]) => (
        <div key={name} className="fact">
          <dt>{name}</dt>
          <dd className={name === 'Notes' ? 'as-written' : undefined}>{value}</dd>
        </div>
      ))}
    </dl>
  )
}

/** Where it comes from, the passage it rests on, how sure the import is, and the way to the mail itself. */
export function Source({ suggestion }: { suggestion: Suggestion }) {
  const wedding = useCurrentWedding()
  const inbox = memberName(suggestion.inboxMemberId, wedding.members)
  const sender =
    suggestion.senderName !== '' && suggestion.senderAddress !== ''
      ? `${suggestion.senderName} (${suggestion.senderAddress})`
      : suggestion.senderName !== '' ? suggestion.senderName : suggestion.senderAddress !== '' ? suggestion.senderAddress : 'Unknown sender'
  // The mail can be opened only from the mailbox it is in: the other one's cannot be opened by you, and the card says whose it is.
  const mine = wedding.members.find((member) => member.you)?.id
  const link = mine !== undefined && suggestion.inboxMemberId === mine ? mailAddress(suggestion.mailLink) : null

  return (
    <>
      <dl className="details suggestion-source" aria-label="Where it comes from">
        <div className="fact">
          <dt>From</dt>
          <dd>{sender}</dd>
        </div>
        <div className="fact">
          <dt>Subject</dt>
          <dd>{suggestion.subject === '' ? '(no subject)' : suggestion.subject}</dd>
        </div>
        <div className="fact">
          <dt>Arrived</dt>
          <dd>
            {atTime(suggestion.receivedAt, wedding.timeZone)}, in {inbox === 'you' ? 'your' : `${inbox}’s`} inbox
          </dd>
        </div>
      </dl>
      {suggestion.passage !== '' && <blockquote className="passage as-written">{suggestion.passage}</blockquote>}
      <p className="suggestion-sure">
        <span>{suggestion.confidence}% sure</span>
        {link !== null && (
          <a href={link} target="_blank" rel="noopener noreferrer">
            Open the e-mail
          </a>
        )}
      </p>
    </>
  )
}

/** Who decided it and when, and the way to what accepting made. */
export function Decided({ suggestion, back }: { suggestion: Suggestion; back: string }) {
  const wedding = useCurrentWedding()
  const by = memberName(suggestion.decidedBy, wedding.members)
  const made = suggestion.state === 'ACCEPTED' ? madeThing(suggestion) : null
  const when = suggestion.decidedAt === null ? '' : `, ${atTime(suggestion.decidedAt, wedding.timeZone)}`
  return (
    <p className="suggestion-decided">
      <span>
        {suggestion.state === 'ACCEPTED' ? 'Accepted' : 'Rejected'} by {by}
        {when}
      </span>
      {made !== null && (
        <Link to={made.to} state={{ back }}>
          {made.open}
        </Link>
      )}
    </p>
  )
}
