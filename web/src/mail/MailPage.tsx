import { useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { ApiError } from '../api/client.ts'
import { useLoaded } from '../planning/useLoaded.ts'
import { changeSettings, getSettings, type SuggestionSettings } from '../suggestions/suggestionsApi.ts'
import { Field } from '../ui/Field.tsx'
import { PageRetry, PageWaiting } from '../ui/Screen.tsx'
import { Warning } from '../ui/Warning.tsx'
import { useCurrentWedding } from '../wedding/WeddingContext.ts'
import {
  browser,
  disconnect,
  dollars,
  fetchMail,
  googleAddress,
  lastLook,
  moment,
  startConnecting,
  type MailReading,
  type MailState,
  type PartnersMailConnection,
} from './mailApi.ts'

interface Notice {
  key: number
  tone: 'notice' | 'alert'
  text: string
}

/** What Google's return says, by the one word the server puts in the address. Any other word says nothing. */
const COMING_BACK: Record<string, Pick<Notice, 'tone' | 'text'>> = {
  yes: { tone: 'notice', text: 'Your Gmail is connected.' },
  refused: { tone: 'alert', text: 'You did not allow it at Google. Nothing was connected.' },
  failed: { tone: 'alert', text: 'That did not work. Nothing was connected.' },
}

/** Where each of the two connects the e-mail import to their Gmail, or takes it away again. */
export function MailPage() {
  const [params, setParams] = useSearchParams()
  const mail = useLoaded('mail', fetchMail)
  const box = useRef<HTMLDivElement>(null)

  // What Google's return said is said once: it is read from the address when the page opens, and the address loses it.
  const [notice, setNotice] = useState<Notice | null>(() => {
    const said = COMING_BACK[params.get('connected') ?? '']
    return said === undefined ? null : { key: 1, ...said }
  })
  useEffect(() => {
    if (params.has('connected')) {
      const rest = new URLSearchParams(params)
      rest.delete('connected')
      setParams(rest, { replace: true })
    }
  }, [params, setParams])

  // The keyboard goes to what was said, not to the top of the page.
  const noticeKey = notice?.key
  useEffect(() => {
    if (noticeKey !== undefined) {
      box.current?.focus({ preventScroll: true })
    }
  }, [noticeKey])

  function say(tone: Notice['tone'], text: string) {
    setNotice((before) => ({ key: (before?.key ?? 0) + 1, tone, text }))
  }

  return (
    <div className="mail-page stack-wide">
      <div className="page-head">
        <h1>E-mail import</h1>
      </div>

      {notice !== null && (
        <div className="notices" role="group" aria-label="What happened" tabIndex={-1} ref={box}>
          <p key={notice.key} className={notice.tone} role={notice.tone === 'alert' ? 'alert' : 'status'}>
            {notice.text}
          </p>
        </div>
      )}

      {mail.state.status === 'failed' ? (
        <PageRetry message="The e-mail import could not be loaded. Try again." onRetry={mail.retry} />
      ) : mail.state.status === 'loading' ? (
        <PageWaiting what="Loading…" />
      ) : (
        <Loaded mail={mail.state.data} onChanged={mail.reload} say={say} />
      )}
    </div>
  )
}

interface LoadedProps {
  mail: MailState
  onChanged: () => void
  say: (tone: Notice['tone'], text: string) => void
}

function Loaded({ mail, onChanged, say }: LoadedProps) {
  if (!mail.setUp) {
    return (
      <p className="empty">The e-mail import is not set up on this server, so there is nothing to connect. Whoever runs the planner can switch it on.</p>
    )
  }
  return (
    <>
      <Yours mail={mail} onChanged={onChanged} say={say} />
      <Partner partner={mail.partner} />
      <AddsByItself say={say} />
      <Reading reading={mail.reading} />
    </>
  )
}

function Yours({ mail, onChanged, say }: LoadedProps) {
  const { timeZone } = useCurrentWedding()
  const mine = mail.yours
  const sending = useRef(false)
  const [busy, setBusy] = useState<'connecting' | 'disconnecting' | null>(null)
  const [asking, setAsking] = useState(false)
  const [trouble, setTrouble] = useState<string | null>(null)

  // Coming back to this page with the browser's back button can show it as it was left, with the request long over.
  useEffect(() => {
    function shownAgain(event: PageTransitionEvent) {
      if (event.persisted) {
        sending.current = false
        setBusy(null)
      }
    }
    window.addEventListener('pageshow', shownAgain)
    return () => window.removeEventListener('pageshow', shownAgain)
  }, [])

  function failed(error: unknown) {
    if (error instanceof ApiError && error.is('urn:wedding-planner:problem:mail-not-set-up')) {
      // The server was changed meanwhile: the page reads again and says what is so now.
      onChanged()
    } else if (error instanceof ApiError) {
      setTrouble('That did not work. Try again.')
    } else {
      setTrouble('The server cannot be reached, so nothing was changed. Try again.')
    }
  }

  async function connect() {
    if (sending.current) {
      return
    }
    sending.current = true
    setBusy('connecting')
    setTrouble(null)
    try {
      const address = googleAddress(await startConnecting())
      if (address === null) {
        setTrouble('That did not work. Nothing was connected.')
      } else {
        // The browser leaves: the button stays as it is until the page is gone.
        browser.goTo(address)
        return
      }
    } catch (error) {
      failed(error)
    }
    sending.current = false
    setBusy(null)
  }

  async function remove() {
    if (sending.current) {
      return
    }
    sending.current = true
    setBusy('disconnecting')
    setTrouble(null)
    try {
      await disconnect()
      setAsking(false)
      say('notice', 'Your Gmail is disconnected.')
      onChanged()
    } catch (error) {
      failed(error)
    }
    sending.current = false
    setBusy(null)
  }

  return (
    <section className="group mail-yours" aria-labelledby="mail-yours">
      <h2 id="mail-yours">Your Gmail</h2>

      {mine.state === 'CONNECTED' ? (
        <>
          <dl className="details mail-facts" aria-label="Your connection">
            <div className="fact">
              <dt>Connected</dt>
              <dd>{mine.address}</dd>
            </div>
            <div className="fact">
              <dt>Since</dt>
              <dd>{mine.connectedAt === null ? '—' : moment(mine.connectedAt, timeZone)}</dd>
            </div>
            <div className="fact">
              <dt>Last looked</dt>
              <dd>{mine.lastLookAt === null ? 'not yet' : moment(mine.lastLookAt, timeZone)}</dd>
            </div>
            <div className="fact">
              <dt>Messages noted</dt>
              <dd>{mine.messagesNoted}</dd>
            </div>
          </dl>
        </>
      ) : (
        <>
          {mine.state === 'NEEDS_RECONNECTING' ? (
            <p role="status" className="alert">
              Google no longer lets the planner into your mail{mine.address === null ? '' : ` (${mine.address})`}: the permission was withdrawn or has ended.
              Connect again to go on.
            </p>
          ) : (
            <p>
              The e-mail import looks through your Gmail for messages about the wedding, so that it can suggest tasks, payments and vendors. Nothing
              changes in the planner until one of you accepts a suggestion.
            </p>
          )}
          <WhatConnectingMeans reading={mail.reading} />
        </>
      )}

      {trouble !== null && (
        <p role="alert" className="alert">
          {trouble}
        </p>
      )}

      {mine.state === 'CONNECTED' ? (
        asking ? (
          <div className="stack" role="group" aria-label="Disconnect">
            <p>
              Disconnect {mine.address}? The planner’s permission is withdrawn at Google, its token and the list of messages it has noted are removed here,
              and the planner stops looking. Suggestions that already exist stay.
            </p>
            <div className="actions">
              <button type="button" className="button button-danger" disabled={busy !== null} onClick={() => void remove()}>
                {busy === 'disconnecting' ? 'Disconnecting…' : 'Yes, disconnect'}
              </button>
              <button type="button" className="button button-quiet" disabled={busy !== null} onClick={() => setAsking(false)}>
                Do not disconnect
              </button>
            </div>
          </div>
        ) : (
          <div>
            <button type="button" className="button button-quiet" onClick={() => setAsking(true)}>
              Disconnect
            </button>
          </div>
        )
      ) : (
        <div>
          <button type="button" className="button" disabled={busy !== null} onClick={() => void connect()}>
            {busy === 'connecting' ? 'Connecting…' : 'Connect Gmail'}
          </button>
        </div>
      )}
    </section>
  )
}

/** In plain words, before the button: what asking for the permission means. */
function WhatConnectingMeans({ reading }: { reading: MailReading }) {
  return (
    <div className="stack-tight">
      <h3 id="mail-means">What connecting means</h3>
      <ul className="mail-means" aria-labelledby="mail-means">
        <li>The planner asks Google for permission to read your mail, and nothing else. It cannot send, change or delete anything.</li>
        <li>It looks at the last three months, and after that at new mail every few minutes.</li>
        <li>Of a message that does not concern the wedding it keeps only that it was looked at.</li>
        {reading.state === 'OFF' ? (
          <li>
            Nothing is sent to an AI service on this server yet. Once reading is switched on, the sender, the subject and the beginning of every message
            will be sent to it, to decide whether the message concerns the wedding, and a message that does will be sent in full. Attachments are never
            sent.
          </li>
        ) : (
          <li>
            The sender, the subject and the beginning of every message are sent to an AI service, to decide whether the message concerns the wedding. A
            message that does is sent in full. Attachments are never sent.
          </li>
        )}
        <li>
          Google will warn you that this app is not verified. That is expected for a private app of two people: choose “Advanced” to go on.
        </li>
      </ul>
    </div>
  )
}

/** How the reading of the mail stands, in words, and what it has come to and cost. The wedding's: both owners are told the same. */
function Reading({ reading }: { reading: MailReading }) {
  const nothingEver = reading.state === 'OFF' && reading.read === 0
  return (
    <section className="group mail-reading" aria-labelledby="mail-reading">
      <h2 id="mail-reading">Reading your mail</h2>
      <ReadingState reading={reading} />
      {!nothingEver && <ReadingNumbers reading={reading} />}
    </section>
  )
}

function ReadingState({ reading }: { reading: MailReading }) {
  switch (reading.state) {
    case 'OFF':
      return (
        <p>
          The planner notes which messages there are, and reads none of them yet: reading is not set up on this server. Nothing is sent to the AI
          service.
        </p>
      )
    case 'READING':
      return <p>The planner is reading your mail, the newest first, a few messages at a time.</p>
    case 'WAITING_FOR_THE_SERVICE':
      return <p>The AI service is busy just now. The reading goes on by itself.</p>
    case 'KEY_NOT_ACCEPTED':
      return (
        <p role="status" className="alert">
          <Warning /> The AI service does not accept the server’s key, so the reading has stopped. It starts again when the server is started with a key
          that works.
        </p>
      )
    case 'MONTHLY_LIMIT':
      return <p>This month’s limit of {dollars(reading.monthlyLimitCents)} is reached. The reading goes on next month.</p>
    case 'DAILY_LIMIT':
      return <p>Today’s limit of messages is reached. The reading goes on tomorrow.</p>
  }
}

function messages(count: number): string {
  return count === 1 ? '1 message' : `${count} messages`
}

function aboutTheWedding({ read, aboutTheWedding: about }: MailReading): string {
  if (read === 1) {
    return about === 1 ? 'It concerned the wedding.' : 'It did not concern the wedding.'
  }
  return `${about === 0 ? 'None' : about} of them concerned the wedding.`
}

function ReadingNumbers({ reading }: { reading: MailReading }) {
  const none = reading.read === 0
  return (
    <>
      <ul className="mail-numbers" aria-label="What was read so far">
        <li>
          {reading.waiting === 0 ? 'No message waits to be read.' : `${messages(reading.waiting)} ${reading.waiting === 1 ? 'waits' : 'wait'} to be read.`}
        </li>
        <li>{none ? 'None has been read yet.' : `${messages(reading.read)} ${reading.read === 1 ? 'was' : 'were'} read.`}</li>
        {!none && <li>{aboutTheWedding(reading)}</li>}
        {reading.couldNotBeRead > 0 && <li>{reading.couldNotBeRead} could not be read.</li>}
        {!none &&
          (reading.suggestions === 0 ? (
            <li>No suggestions came of it.</li>
          ) : (
            <li>
              <Link to="/suggestions">{reading.suggestions === 1 ? '1 suggestion' : `${reading.suggestions} suggestions`}</Link> came of it.
            </li>
          ))}
      </ul>
      <h3 id="mail-cost">Estimated cost</h3>
      <dl className="details mail-facts" aria-labelledby="mail-cost">
        <div className="fact">
          <dt>This month</dt>
          <dd>{dollars(reading.costThisMonthCents)}</dd>
        </div>
        <div className="fact">
          <dt>In all</dt>
          <dd>{dollars(reading.costInAllCents)}</dd>
        </div>
        <div className="fact">
          <dt>Monthly limit</dt>
          <dd>{dollars(reading.monthlyLimitCents)}</dd>
        </div>
      </dl>
      <p className="muted">
        These are estimates, worked out from what was sent to the AI service and the prices in the server’s settings. The bill itself may differ.
      </p>
    </>
  )
}

/** Where the other one stands: never which mailbox it is. */
function Partner({ partner }: { partner: PartnersMailConnection }) {
  const { members, timeZone } = useCurrentWedding()
  const name = members.find((member) => !member.you)?.displayName ?? 'Your partner'
  return (
    <section className="group mail-partner" aria-labelledby="mail-partner">
      <h2 id="mail-partner">{name}</h2>
      <p>
        {!partner.joined
          ? `${name} has not signed in to the planner yet, so there is nothing for them to connect.`
          : partner.state === 'CONNECTED'
            ? `${name}’s Gmail is connected; ${lastLook(partner.lastLookAt, timeZone)}.`
            : partner.state === 'NEEDS_RECONNECTING'
              ? `${name}’s Gmail has to be connected again.`
              : `${name} has not connected their Gmail.`}
      </p>
    </section>
  )
}

const THRESHOLD_MESSAGE = 'Choose a number from 50 to 100.'

/** How sure the import has to be before it adds a task or a step by itself: the wedding's setting, the same for both owners. */
function AddsByItself({ say }: { say: (tone: Notice['tone'], text: string) => void }) {
  const settings = useLoaded('suggestion-settings', getSettings)
  const { members } = useCurrentWedding()
  const partner = members.find((member) => !member.you)?.displayName ?? 'your partner'
  return (
    <section className="group mail-adds" aria-labelledby="mail-adds">
      <h2 id="mail-adds">Adding by itself</h2>
      {settings.state.status === 'failed' ? (
        <PageRetry message="The setting could not be loaded. Try again." onRetry={settings.retry} />
      ) : settings.state.status === 'loading' ? (
        <PageWaiting what="Loading…" />
      ) : (
        <>
          <p>
            This is the wedding’s setting: you and {partner} see and change the same one. A payment or a new vendor is never added by itself; it always
            waits for one of you.
          </p>
          <ThresholdForm key={`${settings.state.data.addsByItself}-${settings.state.data.threshold}`} saved={settings.state.data} onSaved={settings.set} say={say} />
          <p className="muted">
            A change applies from now on: it adds nothing that already waits and undoes nothing that was added.
          </p>
        </>
      )}
    </section>
  )
}

function ThresholdForm({
  saved,
  onSaved,
  say,
}: {
  saved: SuggestionSettings
  onSaved: (settings: SuggestionSettings) => void
  say: (tone: Notice['tone'], text: string) => void
}) {
  const [adds, setAdds] = useState(saved.addsByItself)
  const [typed, setTyped] = useState(String(saved.threshold))
  const [refused, setRefused] = useState<string | undefined>(undefined)
  const [trouble, setTrouble] = useState<string | null>(null)
  const sending = useRef(false)
  const [busy, setBusy] = useState(false)

  async function save(event: React.FormEvent) {
    event.preventDefault()
    if (sending.current) {
      return
    }
    const valid = /^\d{1,3}$/.test(typed.trim())
    // Never keeps the number: it is sent as it stands, and a number nobody can use stays what it was.
    if (adds && !valid) {
      setRefused(THRESHOLD_MESSAGE)
      return
    }
    sending.current = true
    setBusy(true)
    setRefused(undefined)
    setTrouble(null)
    try {
      const threshold = valid ? Number(typed.trim()) : saved.threshold
      const now = await changeSettings({ addsByItself: adds, threshold })
      say('notice', 'Saved. It applies from now on.')
      onSaved(now)
    } catch (error) {
      const message = error instanceof ApiError ? error.fieldMessage('threshold') : undefined
      if (message !== undefined) {
        setRefused(message)
      } else {
        setTrouble(error instanceof ApiError ? 'That did not work. Nothing was changed. Try again.' : 'The server cannot be reached, so nothing was changed. Try again.')
      }
    }
    sending.current = false
    setBusy(false)
  }

  return (
    <form className="stack" onSubmit={(event) => void save(event)} noValidate aria-label="Adding by itself">
      <fieldset className="choice">
        <legend>When should the import add by itself?</legend>
        <label>
          <input type="radio" name="adds" checked={adds} onChange={() => setAdds(true)} />
          Add tasks and steps by themselves when the import is at least this sure
        </label>
        <label>
          <input type="radio" name="adds" checked={!adds} onChange={() => setAdds(false)} />
          Never: nothing is added by itself, everything waits for you
        </label>
      </fieldset>
      <Field label="At least this sure, in percent (50 to 100)" error={refused}>
        {(control) => (
          <input
            {...control}
            name="threshold"
            inputMode="numeric"
            autoComplete="off"
            value={typed}
            disabled={!adds}
            onChange={(event) => {
              setTyped(event.target.value)
              setRefused(undefined)
            }}
          />
        )}
      </Field>
      {trouble !== null && (
        <p role="alert" className="alert">
          {trouble}
        </p>
      )}
      <div>
        <button type="submit" className="button" disabled={busy}>
          {busy ? 'Saving…' : 'Save'}
        </button>
      </div>
    </form>
  )
}
