import { useMemo, useState, type FormEvent } from 'react'
import { ApiError } from '../api/client.ts'
import { Field } from '../ui/Field.tsx'
import { CardScreen } from '../ui/Screen.tsx'
import { setUpWedding, type Wedding } from './weddingApi.ts'

/** Offered first; every other currency follows in alphabetical order. */
const COMMON_CURRENCIES = ['PLN', 'UAH', 'EUR', 'USD']

function supported(kind: 'currency' | 'timeZone', fallback: string[]): string[] {
  try {
    return Intl.supportedValuesOf(kind)
  } catch {
    return fallback
  }
}

function browserTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone
}

interface SetupPageProps {
  /** What the signed-in person is called so far; offered as their name. */
  suggestedName: string
  onDone: (wedding: Wedding) => void
  /** The other owner set the wedding up in the meantime: load theirs. */
  onAlreadySetUp: () => void
}

export function SetupPage({ suggestedName, onDone, onAlreadySetUp }: SetupPageProps) {
  const [yourName, setYourName] = useState(suggestedName)
  const [partnerName, setPartnerName] = useState('')
  const [date, setDate] = useState('')
  const [currency, setCurrency] = useState('PLN')
  const [timeZone, setTimeZone] = useState(browserTimeZone)
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState<ApiError | 'unreachable' | null>(null)

  const otherCurrencies = useMemo(
    () => supported('currency', []).filter((code) => !COMMON_CURRENCIES.includes(code)),
    [],
  )
  const timeZones = useMemo(() => {
    const zones = supported('timeZone', [])
    return zones.includes(timeZone) ? zones : [timeZone, ...zones]
    // The list is built once; the zone chosen later is always one of its entries.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  function fieldError(field: string): string | undefined {
    return failure instanceof ApiError ? failure.fieldMessage(field) : undefined
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setFailure(null)
    try {
      onDone(
        await setUpWedding({
          yourName: yourName.trim(),
          partnerName: partnerName.trim(),
          date: date === '' ? null : date,
          currency,
          timeZone,
        }),
      )
    } catch (error) {
      if (error instanceof ApiError && error.is('urn:wedding-planner:problem:wedding-already-set-up')) {
        onAlreadySetUp()
        return
      }
      setFailure(error instanceof ApiError ? error : 'unreachable')
      setBusy(false)
    }
  }

  const general =
    failure === 'unreachable'
      ? 'The server cannot be reached. Check your connection and try again.'
      : failure !== null && (failure.problem.fieldProblems ?? []).length === 0
        ? 'Saving did not work. Try again.'
        : null

  return (
    <CardScreen title="Set up your wedding" lead="A few details to start with. You can change them later." wide>
      <form className="stack" onSubmit={(event) => void submit(event)} noValidate>
        {general !== null && (
          <p role="alert" className="alert">
            {general}
          </p>
        )}

        <div className="two-columns">
          <Field label="Your name" error={fieldError('yourName')}>
            {(control) => (
              <input
                {...control}
                name="yourName"
                autoComplete="given-name"
                maxLength={80}
                required
                value={yourName}
                onChange={(event) => setYourName(event.target.value)}
              />
            )}
          </Field>
          <Field label="Your partner’s name" error={fieldError('partnerName')}>
            {(control) => (
              <input
                {...control}
                name="partnerName"
                autoComplete="off"
                maxLength={80}
                required
                value={partnerName}
                onChange={(event) => setPartnerName(event.target.value)}
              />
            )}
          </Field>
        </div>

        <Field label="Wedding day" optional hint="Leave it empty if the day is not fixed yet." error={fieldError('date')}>
          {(control) => (
            <input {...control} type="date" name="date" value={date} onChange={(event) => setDate(event.target.value)} />
          )}
        </Field>

        <div className="two-columns">
          <Field label="Currency" hint="All amounts in the planner are in this currency." error={fieldError('currency')}>
            {(control) => (
              <select {...control} name="currency" value={currency} onChange={(event) => setCurrency(event.target.value)}>
                {COMMON_CURRENCIES.map((code) => (
                  <option key={code}>{code}</option>
                ))}
                <option disabled>──────</option>
                {otherCurrencies.map((code) => (
                  <option key={code}>{code}</option>
                ))}
              </select>
            )}
          </Field>
          <Field label="Time zone" hint="Where the wedding takes place." error={fieldError('timeZone')}>
            {(control) => (
              <select {...control} name="timeZone" value={timeZone} onChange={(event) => setTimeZone(event.target.value)}>
                {timeZones.map((zone) => (
                  <option key={zone}>{zone}</option>
                ))}
              </select>
            )}
          </Field>
        </div>

        <button type="submit" className="button button-block" disabled={busy}>
          {busy ? 'Saving…' : 'Save and continue'}
        </button>
      </form>
    </CardScreen>
  )
}
