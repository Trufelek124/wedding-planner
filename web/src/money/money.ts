/**
 * Money on the screen. The server keeps every amount as a whole number of the currency's smallest
 * unit (grosze for the złoty), and so does everything here: an amount is split into its whole and
 * its small part with whole-number arithmetic and is never held as a fraction.
 */

interface Currency {
  /** How many decimals the currency has: 2 for PLN, 0 for JPY. */
  digits: number
  /** "zł", "€", "₴"; the code itself where the browser knows no symbol. */
  symbol: string
}

const known = new Map<string, Currency>()
const WHOLE = new Intl.NumberFormat('en-GB', { maximumFractionDigits: 0 })

function currencyOf(code: string): Currency {
  let currency = known.get(code)
  if (currency === undefined) {
    try {
      const format = new Intl.NumberFormat('en-GB', { style: 'currency', currency: code, currencyDisplay: 'narrowSymbol' })
      currency = {
        digits: format.resolvedOptions().maximumFractionDigits ?? 2,
        symbol: format.formatToParts(0).find((part) => part.type === 'currency')?.value ?? code,
      }
    } catch {
      // A code the browser does not know is shown as it is, with the usual two decimals.
      currency = { digits: 2, symbol: code }
    }
    known.set(code, currency)
  }
  return currency
}

function parts(minor: number, digits: number): { whole: number; small: string } {
  const unit = 10 ** digits
  const size = Math.abs(minor)
  return { whole: Math.floor(size / unit), small: digits === 0 ? '' : String(size % unit).padStart(digits, '0') }
}

/** "2,500.50": the amount without its currency, for a column that names the currency once. */
export function amount(minor: number, currency: string): string {
  const { digits } = currencyOf(currency)
  const { whole, small } = parts(minor, digits)
  return `${minor < 0 ? '−' : ''}${WHOLE.format(whole)}${small === '' ? '' : `.${small}`}`
}

/** "2,500.50 zł": the amount first, as both countries of this wedding write it. */
export function money(minor: number, currency: string): string {
  return `${amount(minor, currency)}\u00a0${currencyOf(currency).symbol}`
}

/** "zł" */
export function currencySymbol(currency: string): string {
  return currencyOf(currency).symbol
}

/** An amount as a person would type it into a field: "2500" or "2500.50". */
export function moneyToType(minor: number, currency: string): string {
  const { digits } = currencyOf(currency)
  const { whole, small } = parts(minor, digits)
  return small === '' || Number(small) === 0 ? String(whole) : `${whole}.${small}`
}

/**
 * Reads an amount a person typed: "2500", "2 500", "2500,50", "2,500.50", "2.500,50".
 * A comma or a point followed by three digits separates thousands; the last one followed by fewer
 * is the decimal mark. Returns the amount in the smallest unit, or null when it cannot be read as
 * one: letters, a minus, more decimals than the currency has.
 */
export function moneyTyped(text: string, currency: string): number | null {
  const { digits } = currencyOf(currency)
  const typed = text.replace(/[\s\u00a0\u202f]/g, '')
  if (!/^\d[\d.,]*$/.test(typed)) {
    return null
  }
  const mark = Math.max(typed.lastIndexOf('.'), typed.lastIndexOf(','))
  let whole = typed
  let small = ''
  if (mark >= 0) {
    const after = typed.slice(mark + 1)
    if (after.length === 3 && digits !== 3) {
      // "2,500": the mark separates thousands, and so does every one before it.
    } else if (after.length >= 1 && after.length <= digits) {
      whole = typed.slice(0, mark)
      small = after
    } else {
      return null
    }
  }
  // What separates thousands stands before groups of exactly three digits.
  if (!/^\d{1,3}([.,]\d{3})*$/.test(whole) && !/^\d+$/.test(whole)) {
    return null
  }
  const minor = Number(whole.replace(/[.,]/g, '')) * 10 ** digits + Number(small.padEnd(digits, '0') || '0')
  return Number.isSafeInteger(minor) ? minor : null
}
