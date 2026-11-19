import { ApiError } from '../api/client.ts'
import { getEntry } from '../planning/planningApi.ts'
import {
  acceptSuggestion,
  atTime,
  getSuggestion,
  keepSuggestion,
  madeThing,
  memberName,
  rejectSuggestion,
  undoSuggestion,
  type Suggestion,
} from './suggestionsApi.ts'

/** Accept and reject are for what waits; keep and undo for what was added by itself. */
export type Decision = 'accept' | 'reject' | 'keep' | 'undo'

type Members = { id: string; displayName: string; you: boolean }[]

/** What a page tells the person after a decision: said once, kept until the page is left. */
export interface Notice {
  key: number
  tone: 'done' | 'alert'
  /** Plain words, then optionally the title; the title was written by a mail's sender and stays plain text. */
  lead: string
  title?: string
  /** Where the title leads. */
  link?: { to: string; label: string }
  /** With `quoted` the title stands in quotation marks in the middle of a sentence, and `tail` finishes it. */
  quoted?: boolean
  tail?: string
  /** A way on that stands apart from the sentence. */
  open?: { to: string; label: string }
}

/** What goes wrong with one card, said on the card. */
export type Trouble = { kind: 'refused'; messages: string[] } | { kind: 'unreachable' } | { kind: 'other'; text: string }

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

/** "Kept: “‹title›”." with the title leading to the entry that stays. */
function kept(suggestion: Suggestion): Notice {
  noticeNumber += 1
  const made = madeThing(suggestion)
  return {
    key: noticeNumber,
    tone: 'done',
    lead: 'Kept',
    title: suggestion.title,
    quoted: true,
    tail: '.',
    ...(made === null ? {} : { link: { to: made.to, label: suggestion.title } }),
  }
}

/** "Undone: “‹title›”. It was removed from your plan." */
function undone(suggestion: Suggestion): Notice {
  noticeNumber += 1
  return { key: noticeNumber, tone: 'done', lead: 'Undone', title: suggestion.title, quoted: true, tail: '. It was removed from your plan.' }
}

export function isAlreadyDecided(error: unknown): error is ApiError {
  return error instanceof ApiError && error.is('urn:wedding-planner:problem:already-decided')
}

function isEntryChanged(error: unknown): error is ApiError {
  return error instanceof ApiError && error.is('urn:wedding-planner:problem:entry-changed')
}

function capitalised(word: string): string {
  return `${word.slice(0, 1).toUpperCase()}${word.slice(1)}`
}

/**
 * The other decision was made first, or the kind of decision does not fit. Says which, and by whom, from the
 * suggestion as the server holds it now; nothing is sent again. Where the server cannot be asked, it says only
 * that somebody decided.
 */
export async function alreadyDecided(
  id: string,
  title: string,
  tried: Decision,
  members: Members,
  timeZone: string,
): Promise<Notice> {
  noticeNumber += 1
  const key = noticeNumber
  const added = tried === 'keep' || tried === 'undo'
  const notDone = { accept: 'was not accepted', reject: 'was not rejected', keep: 'was not kept', undo: 'was not undone' }[tried]
  let now: Suggestion | null
  try {
    now = await getSuggestion(id)
  } catch {
    now = null
  }
  if (now === null || now.state === 'WAITING') {
    return { key, tone: 'alert', lead: `Somebody else decided this first, so it ${notDone}`, title }
  }
  if (now.state === 'ADDED') {
    // Accept or reject was asked of one that was added by itself: nobody has decided it.
    return { key, tone: 'alert', lead: '', title, quoted: true, tail: ' was added by itself. Keep it or undo it.' }
  }
  if (now.state === 'ACCEPTED' && now.decidedBy === null) {
    return { key, tone: 'alert', lead: '', title, quoted: true, tail: ' counted as kept after thirty days.' }
  }
  const by = memberName(now.decidedBy, members)
  if (added) {
    const did = now.state === 'ACCEPTED' ? 'kept' : 'undone'
    return { key, tone: 'alert', lead: '', title, quoted: true, tail: ` was already ${did} by ${by}.` }
  }
  const when = now.decidedAt === null ? '' : ` on ${atTime(now.decidedAt, timeZone)}`
  const did = now.state === 'ACCEPTED' ? 'accepted' : 'rejected'
  return { key, tone: 'alert', lead: `${capitalised(by)} had already ${did} this${when}, so it ${notDone}`, title }
}

/** A word to the person that is not about a decision's outcome. */
export function plainNotice(tone: Notice['tone'], lead: string, title?: string): Notice {
  noticeNumber += 1
  return { key: noticeNumber, tone, lead, ...(title === undefined ? {} : { title }) }
}

/** The server's own sentence, with the way to the entry where it is still there. */
async function entryChanged(suggestion: Suggestion, error: ApiError): Promise<Notice> {
  noticeNumber += 1
  const made = madeThing(suggestion)
  let stays = false
  if (made !== null && suggestion.madeEntryId !== null) {
    try {
      await getEntry(suggestion.madeEntryId)
      stays = true
    } catch {
      stays = false
    }
  }
  return {
    key: noticeNumber,
    tone: 'alert',
    lead: error.problem.detail ?? 'This entry was changed after it was added, so it stays in your plan and this suggestion counts as kept.',
    ...(stays && made !== null ? { open: { to: made.to, label: made.open } } : {}),
  }
}

export type Outcome = { notice: Notice } | { trouble: Trouble }

/**
 * Sends one decision, once, and says what came of it. A notice means the suggestion stands differently now, so the
 * lists read again; a trouble means nothing was decided and the card stays as it was.
 */
export async function decide(suggestion: Suggestion, decision: Decision, members: Members, timeZone: string): Promise<Outcome> {
  try {
    switch (decision) {
      case 'accept':
        return { notice: whatHappened(await acceptSuggestion(suggestion.id)) }
      case 'reject':
        return { notice: whatHappened(await rejectSuggestion(suggestion.id)) }
      case 'keep':
        return { notice: kept(await keepSuggestion(suggestion.id)) }
      case 'undo':
        return { notice: undone(await undoSuggestion(suggestion.id)) }
    }
  } catch (error) {
    if (isAlreadyDecided(error)) {
      return { notice: await alreadyDecided(suggestion.id, suggestion.title, decision, members, timeZone) }
    }
    if (isEntryChanged(error)) {
      return { notice: await entryChanged(suggestion, error) }
    }
    if (error instanceof ApiError && error.is('urn:wedding-planner:problem:not-found')) {
      return { notice: plainNotice('alert', 'This suggestion no longer exists', suggestion.title) }
    }
    if (error instanceof ApiError) {
      const messages = (error.problem.fieldProblems ?? []).map((one) => one.message)
      return {
        trouble: messages.length > 0 ? { kind: 'refused', messages } : { kind: 'other', text: error.problem.detail ?? 'That did not work. Try again.' },
      }
    }
    return { trouble: { kind: 'unreachable' } }
  }
}
