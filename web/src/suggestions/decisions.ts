import { ApiError } from '../api/client.ts'
import { atTime, listSuggestions, madeThing, memberName, type Suggestion } from './suggestionsApi.ts'

export type Decision = 'accept' | 'reject'

/** What a page tells the person after a decision: said once, kept until the page is left. */
export interface Notice {
  key: number
  tone: 'done' | 'alert'
  /** Plain words, then optionally a link; the quoted title was written by a mail's sender and stays plain text. */
  lead: string
  title?: string
  link?: { to: string; label: string }
}

let noticeNumber = 0

/** "Added to the tasks: ‹title›", or "Rejected: ‹title›". */
export function whatHappened(suggestion: Suggestion): Notice {
  noticeNumber += 1
  if (suggestion.state === 'REJECTED') {
    return { key: noticeNumber, tone: 'done', lead: 'Rejected', title: suggestion.title }
  }
  const made = madeThing(suggestion)
  return {
    key: noticeNumber,
    tone: 'done',
    lead: made === null ? 'Accepted' : `Added to ${made.where}`,
    title: suggestion.title,
    ...(made === null ? {} : { link: { to: made.to, label: suggestion.title } }),
  }
}

export function isAlreadyDecided(error: unknown): error is ApiError {
  return error instanceof ApiError && error.is('urn:wedding-planner:problem:already-decided')
}

/**
 * The other decision was made first. Says which, and by whom, from the suggestion as the server holds it now;
 * nothing is sent again. Where the server cannot be asked, it says only that somebody decided.
 */
export async function alreadyDecided(
  id: string,
  title: string,
  tried: Decision,
  members: { id: string; displayName: string; you: boolean }[],
  timeZone: string,
): Promise<Notice> {
  noticeNumber += 1
  const kept = tried === 'accept' ? 'was not accepted' : 'was not rejected'
  let now: Suggestion | undefined
  try {
    now = (await listSuggestions('ALL')).find((one) => one.id === id)
  } catch {
    now = undefined
  }
  if (now === undefined || now.state === 'WAITING') {
    return { key: noticeNumber, tone: 'alert', lead: `Somebody else decided this first, so it ${kept}`, title }
  }
  const by = memberName(now.decidedBy, members)
  const when = now.decidedAt === null ? '' : ` on ${atTime(now.decidedAt, timeZone)}`
  const did = now.state === 'ACCEPTED' ? 'accepted' : 'rejected'
  return {
    key: noticeNumber,
    tone: 'alert',
    lead: `${by === 'you' ? 'You' : by[0]?.toUpperCase() + by.slice(1)} had already ${did} this${when}, so it ${kept}`,
    title,
  }
}

/** A word to the person that is not about a decision's outcome. */
export function plainNotice(tone: Notice['tone'], lead: string, title?: string): Notice {
  noticeNumber += 1
  return { key: noticeNumber, tone, lead, ...(title === undefined ? {} : { title }) }
}
