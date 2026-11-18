import { money } from '../money/money.ts'

interface MeterProps {
  /** What the amounts are measured against; null when nothing was planned. */
  limit: number | null
  /** Every payment that counts, paid or not. */
  committed: number
  /** The part of it that is paid. */
  paid: number
  currency: string
  /** What the bar is about, to begin its name with: "Venue", "The whole budget". */
  about: string
  /** What the limit is called in a sentence: "planned", "budget". */
  limitName: string
}

/** How a bar's amounts read as a sentence; also what a screen reader is told instead of the bar. */
function meterInWords({ limit, committed, paid, currency, limitName }: Omit<MeterProps, 'about'>): string {
  const toPay = committed - paid
  const spent = `${money(paid, currency)} paid, ${money(toPay, currency)} still to pay`
  if (limit === null) {
    return spent
  }
  return committed > limit
    ? `${spent}: ${money(committed - limit, currency)} more than the ${money(limit, currency)} ${limitName}`
    : `${spent}, of ${money(limit, currency)} ${limitName}`
}

/**
 * A thin bar: what is paid, what is still to pay, and what goes beyond the limit, on a track that
 * stands for the limit. The figures beside it say the same in words; the bar is never the only way
 * to learn them.
 */
export function Meter(props: MeterProps) {
  const { limit, committed, paid, currency, about } = props
  const whole = Math.max(limit ?? 0, committed)
  const within = limit === null ? committed : Math.min(committed, limit)
  const paidWithin = Math.min(paid, within)
  const parts = [
    { kind: 'paid', size: paidWithin, says: `Paid: ${money(paid, currency)}` },
    { kind: 'due', size: within - paidWithin, says: `Still to pay: ${money(committed - paid, currency)}` },
    { kind: 'over', size: committed - within, says: `Over: ${money(committed - within, currency)}` },
  ].filter((part) => part.size > 0)

  return (
    <div className="meter" role="img" aria-label={`${about}: ${meterInWords(props)}`}>
      {parts.map((part) => (
        // A width in hundredths of a percent is as fine as a screen can show; the amounts stay whole numbers.
        <span key={part.kind} data-part={part.kind} title={part.says} style={{ width: `${Math.round((part.size / whole) * 10000) / 100}%` }} />
      ))}
    </div>
  )
}
