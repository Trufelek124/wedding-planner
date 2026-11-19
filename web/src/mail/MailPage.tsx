import { useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router'
import { ApiError } from '../api/client.ts'
import { useLoaded } from '../planning/useLoaded.ts'
import { PageRetry, PageWaiting } from '../ui/Screen.tsx'
import { useCurrentWedding } from '../wedding/WeddingContext.ts'
import { browser, disconnect, fetchMail, googleAddress, lastLook, moment, startConnecting, type MailState, type PartnersMailConnection } from './mailApi.ts'

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
          <p className="muted">For now the planner only notes which messages there are. Reading them comes later.</p>
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
              When it is complete, the e-mail import will look through your Gmail for messages about the wedding and suggest tasks, payments and vendors.
              Nothing changes in the planner until one of you accepts a suggestion.
            </p>
          )}
          <WhatConnectingMeans />
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
function WhatConnectingMeans() {
  return (
    <div className="stack-tight">
      <h3 id="mail-means">What connecting means</h3>
      <ul className="mail-means" aria-labelledby="mail-means">
        <li>The planner asks Google for permission to read your mail, and nothing else. It cannot send, change or delete anything.</li>
        <li>It looks at the last three months, and after that at new mail every few minutes.</li>
        <li>Of a message that does not concern the wedding it keeps only that it was looked at.</li>
        <li>
          Once the import is complete, the sender, the subject and the beginning of every message will be sent to the AI service that decides whether it
          concerns the wedding.
        </li>
        <li>
          Google will warn you that this app is not verified. That is expected for a private app of two people: choose “Advanced” to go on.
        </li>
        <li>For now the planner only notes which messages there are. Reading them comes later.</li>
      </ul>
    </div>
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
