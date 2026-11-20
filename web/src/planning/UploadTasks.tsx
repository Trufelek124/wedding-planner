import { useEffect, useId, useRef, useState } from 'react'
import { ApiError } from '../api/client.ts'
import { useCurrentWedding } from '../wedding/WeddingContext.ts'
import { applyTaskFile, checkTaskFile, MOST_BYTES, type TaskFileReport } from './taskFileApi.ts'
import {
  buttonInWords,
  changeInWords,
  doneInWords,
  MOST_LISTED,
  numbersInWords,
  whatIsWrong,
  wrongInWords,
} from './taskFileWords.ts'

interface Chosen {
  name: string
  /** The file's text, as read: it is what both calls send. */
  text: string
}

interface Trouble {
  text: string
  /** What "Try again" does; none where trying again would change nothing. */
  retry: 'check' | 'apply' | null
}

interface UploadTasksProps {
  /** Called once a file was applied, so that the list and the categories are read again. */
  onApplied: () => void
}

/** "and 37 more" */
function More({ shown, of, what = '' }: { shown: number; of: number; what?: string }) {
  return of > shown ? <p className="muted">{`and ${of - shown} more${what}`}</p> : null
}

/** Tasks from a file: choosing one checks it and says what it would do; one button does it. */
export function UploadTasks({ onApplied }: UploadTasksProps) {
  const id = useId()
  const currency = useCurrentWedding().currency
  const [chosen, setChosen] = useState<Chosen | null>(null)
  const [report, setReport] = useState<TaskFileReport | null>(null)
  const [busy, setBusy] = useState<'reading' | 'checking' | 'applying' | null>(null)
  const [trouble, setTrouble] = useState<Trouble | null>(null)
  const [done, setDone] = useState<string | null>(null)

  // Only the latest choice may put anything on the page; one press is one request.
  const latest = useRef(0)
  const applying = useRef(false)
  const gone = useRef(false)
  const wantsFocus = useRef(false)
  const saidBox = useRef<HTMLParagraphElement>(null)
  const troubleBox = useRef<HTMLDivElement>(null)

  useEffect(() => {
    gone.current = false
    return () => {
      gone.current = true
    }
  }, [])

  // After a press the button is gone: the keyboard goes to what was said, not to the top of the page.
  useEffect(() => {
    if (wantsFocus.current && busy === null) {
      wantsFocus.current = false
      ;(troubleBox.current ?? saidBox.current)?.focus({ preventScroll: true })
    }
  })

  async function check(file: Chosen, mine: number) {
    setBusy('checking')
    try {
      const result = await checkTaskFile(file.text)
      if (mine === latest.current && !gone.current) {
        setReport(result)
        setBusy(null)
      }
    } catch (error) {
      if (mine === latest.current && !gone.current) {
        setTrouble(troubleOf(error, 'check'))
        setBusy(null)
      }
    }
  }

  async function choose(file: File) {
    const mine = ++latest.current
    setChosen(null)
    setReport(null)
    setTrouble(null)
    setDone(null)
    if (file.size > MOST_BYTES) {
      setBusy(null)
      setTrouble({ text: `“${file.name}” is larger than 1 MB, the most the planner takes. Nothing was sent. Split the tasks into more than one file.`, retry: null })
      return
    }
    setBusy('reading')
    let text: string
    try {
      text = await file.text()
    } catch {
      if (mine === latest.current && !gone.current) {
        setBusy(null)
        setTrouble({ text: `Your browser could not read “${file.name}”. Choose it again, or choose another file.`, retry: null })
      }
      return
    }
    if (mine !== latest.current || gone.current) {
      return
    }
    const picked = { name: file.name, text }
    setChosen(picked)
    await check(picked, mine)
  }

  async function tryAgain() {
    if (chosen === null || busy !== null) {
      return
    }
    wantsFocus.current = true
    const retry = trouble?.retry
    setTrouble(null)
    if (retry === 'apply') {
      await apply()
    } else {
      await check(chosen, latest.current)
    }
  }

  async function apply() {
    if (chosen === null || applying.current) {
      return
    }
    applying.current = true
    wantsFocus.current = true
    const mine = latest.current
    setTrouble(null)
    setBusy('applying')
    try {
      const result = await applyTaskFile(chosen.text)
      if (gone.current) {
        return
      }
      if (result.applied) {
        // The file is let go: the part is ready for the next one.
        setDone(doneInWords(result))
        setChosen(null)
        setReport(null)
        setBusy(null)
        onApplied()
      } else {
        setReport(result)
        setBusy(null)
      }
    } catch (error) {
      if (gone.current || mine !== latest.current) {
        return
      }
      if (error instanceof ApiError && error.is('urn:wedding-planner:problem:task-file-refused')) {
        // The planner is not as it was at the check: nothing was applied, and what is wrong now is looked at again.
        setTrouble({ text: 'The planner has changed since this file was checked, so nothing was applied. The file was checked again; here is what it does now.', retry: null })
        setReport(null)
        await check(chosen, mine)
      } else {
        setTrouble(troubleOf(error, 'apply'))
        setBusy(null)
      }
    } finally {
      applying.current = false
    }
  }

  const wrong = report !== null && !report.canBeApplied ? whatIsWrong(report) : []
  const button = report !== null && report.canBeApplied ? buttonInWords(report) : null
  const added = report?.rows.filter((row) => row.outcome === 'NEW') ?? []
  const changed = report?.rows.filter((row) => row.outcome === 'CHANGED') ?? []
  // After a failed apply the way on is "Try again", which does the same.
  const pressable = button !== null && !(trouble?.retry === 'apply')

  let said: string | null = null
  if (busy === 'reading' || busy === 'checking') {
    said = `Checking ${chosen === null ? 'the file' : `“${chosen.name}”`}…`
  } else if (busy === 'applying') {
    said = 'Applying the file…'
  } else if (done !== null) {
    said = done
  } else if (report !== null && report.canBeApplied) {
    said = report.added + report.changed === 0 ? 'The file changes nothing. There is nothing to apply.' : numbersInWords(report)
  } else if (report !== null) {
    said = 'Nothing can be applied until the file is corrected.'
  }

  return (
    <section className="upload-tasks" aria-labelledby={`${id}-heading`}>
      <h2 id={`${id}-heading`}>Upload tasks</h2>
      <p>
        Tasks from a file are added, and the ones the planner already has from an earlier file, which it knows by their
        key, are updated. Nothing is ever removed.
      </p>
      <ul className="upload-links">
        <li>
          <a href="/formats/wedding-planner-tasks-1.schema.json" target="_blank" rel="noopener noreferrer">
            The format of the file
          </a>
        </li>
        <li>
          <a href="/formats/wedding-planner-tasks-1.example.json" download="wedding-planner-tasks-1.example.json">
            An example
          </a>
        </li>
      </ul>

      <div className="stack-tight">
        <label className="button button-quiet file-button" data-disabled={busy !== null || undefined}>
          <input
            type="file"
            className="visually-hidden"
            accept=".json,application/json"
            disabled={busy !== null}
            aria-describedby={`${id}-hint`}
            onChange={(event) => {
              const file = event.target.files?.[0]
              // Emptied, so that choosing the same file again, once it is corrected, is a change again.
              event.target.value = ''
              if (file !== undefined) {
                void choose(file)
              }
            }}
          />
          Choose a file
        </label>
        <p className="hint" id={`${id}-hint`}>
          A JSON file of up to 1 MB. Choosing it only checks it; nothing changes yet.
        </p>
        {chosen !== null && <p className="muted upload-file">File: {chosen.name}</p>}
      </div>

      <p role="status" tabIndex={-1} ref={saidBox} className={said === null ? 'visually-hidden' : 'said upload-said'}>
        {said}
      </p>

      {trouble !== null && (
        <div role="alert" tabIndex={-1} ref={troubleBox} className="alert upload-trouble">
          <p>{trouble.text}</p>
          {trouble.retry !== null && (
            <button type="button" className="button button-quiet" onClick={() => void tryAgain()}>
              Try again
            </button>
          )}
        </div>
      )}

      {report !== null && report.canBeApplied && (
        <>
          {report.newCategories.length > 0 && (
            <p>
              {report.newCategories.length === 1 ? 'This category would be added: ' : 'These categories would be added: '}
              {report.newCategories.join(', ')}.
            </p>
          )}
          {added.length > 0 && (
            <div className="stack-tight">
              <h3>New tasks</h3>
              <ul>
                {added.slice(0, MOST_LISTED).map((row) => (
                  <li key={row.row}>{row.title ?? `Row ${row.row}`}</li>
                ))}
              </ul>
              <More shown={MOST_LISTED} of={added.length} />
            </div>
          )}
          {changed.length > 0 && (
            <div className="stack-tight">
              <h3>Tasks that would change</h3>
              <ul>
                {changed.slice(0, MOST_LISTED).map((row) => (
                  <li key={row.row}>
                    {row.title ?? `Row ${row.row}`}
                    <ul>
                      {row.changes.map((change) => (
                        <li key={change.field}>{changeInWords(change, currency)}</li>
                      ))}
                    </ul>
                  </li>
                ))}
              </ul>
              <More shown={MOST_LISTED} of={changed.length} />
            </div>
          )}
        </>
      )}

      {wrong.length > 0 && (
        <div className="stack-tight">
          <h3>What is wrong</h3>
          <ul>
            {wrong.slice(0, MOST_LISTED).map((one, index) => (
              <li key={index}>{wrongInWords(one)}</li>
            ))}
          </ul>
          <More shown={MOST_LISTED} of={wrong.length} what=" problems" />
        </div>
      )}

      {pressable && (
        <button type="button" className="button upload-apply" disabled={busy !== null} onClick={() => void apply()}>
          {busy === 'applying' ? 'Applying…' : button}
        </button>
      )}
    </section>
  )
}

/** What to tell a person when a call did not go through. */
function troubleOf(error: unknown, when: 'check' | 'apply'): Trouble {
  if (error instanceof ApiError) {
    if (error.is('urn:wedding-planner:problem:upload-too-large')) {
      return { text: 'The planner does not take a file this large: 1 MB is the most. Nothing was changed. Split the tasks into more than one file.', retry: null }
    }
    return { text: `Some error came back from the planner, so the file was not ${when === 'check' ? 'checked' : 'applied'}. Nothing was changed.`, retry: when }
  }
  return {
    text:
      when === 'check'
        ? 'The planner could not be reached, so the file was not checked. Nothing was changed.'
        : 'The planner could not be reached, so the file may not have been applied. Trying again is safe: a file applied twice changes nothing the second time.',
    retry: when,
  }
}
