package io.github.trufelek124.weddingplanner.money

import android.icu.util.Currency
import android.icu.util.ULocale
import android.os.Build
import java.util.Locale

/*
 * Money on the screen. The server keeps every amount as a whole number of the currency's smallest
 * unit (grosze for the złoty), and so does everything here: an amount is split into its whole and
 * its small part with whole-number arithmetic and is never held as a fraction.
 */

private class Written(val digits: Int, val symbol: String)

/** The currencies of this wedding's two countries and their neighbours, written the same on every phone. */
private val SYMBOLS = mapOf("PLN" to "zł", "UAH" to "₴", "EUR" to "€", "USD" to "$", "GBP" to "£", "JPY" to "¥")

private fun written(code: String): Written =
    try {
        val currency = Currency.getInstance(code)
        Written(digits = currency.defaultFractionDigits.coerceIn(0, 4), symbol = SYMBOLS[code] ?: symbolOf(currency))
    } catch (unknown: IllegalArgumentException) {
        // A code the phone does not know is shown as it is, with the usual two decimals.
        Written(2, code)
    }

/** The short sign where the phone's Android knows such signs (11 and later); otherwise what it has, often the code. */
private fun symbolOf(currency: Currency): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        currency.getName(ULocale.UK, Currency.NARROW_SYMBOL_NAME, null)
    } else {
        currency.getName(ULocale.UK, Currency.SYMBOL_NAME, null)
    }

/** "2,500.50 zł": the amount first, as both countries of this wedding write it. The space does not break. */
fun money(minor: Long, currency: String): String {
    val how = written(currency)
    var unit = 1L
    repeat(how.digits) { unit *= 10 }
    val size = if (minor < 0) -minor else minor
    val whole = String.format(Locale.UK, "%,d", size / unit)
    val small = if (how.digits == 0) "" else "." + (size % unit).toString().padStart(how.digits, '0')
    return (if (minor < 0) "\u2212" else "") + whole + small + "\u00a0" + how.symbol
}

/** "zł": what stands after an amount of this currency. */
fun currencySign(currency: String): String = written(currency).symbol

/** An amount as a person would type it into a field: "2500" or "2500.50". */
fun moneyToType(minor: Long, currency: String): String {
    val digits = written(currency).digits
    var unit = 1L
    repeat(digits) { unit *= 10 }
    val whole = minor / unit
    val small = minor % unit
    return if (digits == 0 || small == 0L) whole.toString() else "$whole." + small.toString().padStart(digits, '0')
}

private val SPACES = Regex("[\\s\u00a0\u202f]")
private val AMOUNT = Regex("^\\d[\\d.,]*$")
private val GROUPED = Regex("^\\d{1,3}([.,]\\d{3})*$")
private val PLAIN = Regex("^\\d+$")

/**
 * Reads an amount a person typed: "2500", "2 500", "2500,50", "2,500.50", "2.500,50". A comma or a
 * point followed by three digits separates thousands; the last one followed by fewer is the
 * decimal mark. Returns the amount in the smallest unit, or null when it cannot be read as one:
 * letters, a minus, more decimals than the currency has, or more than a number can hold.
 */
fun moneyTyped(text: String, currency: String): Long? {
    val digits = written(currency).digits
    val typed = text.replace(SPACES, "")
    if (!AMOUNT.matches(typed)) {
        return null
    }
    val mark = maxOf(typed.lastIndexOf('.'), typed.lastIndexOf(','))
    var whole = typed
    var small = ""
    if (mark >= 0) {
        val after = typed.substring(mark + 1)
        if (after.length == 3 && digits != 3) {
            // "2,500": the mark separates thousands, and so does every one before it.
        } else if (after.length in 1..digits) {
            whole = typed.substring(0, mark)
            small = after
        } else {
            return null
        }
    }
    // What separates thousands stands before groups of exactly three digits.
    if (!GROUPED.matches(whole) && !PLAIN.matches(whole)) {
        return null
    }
    val wholeDigits = whole.replace(".", "").replace(",", "")
    if (wholeDigits.length > 15) {
        return null
    }
    var unit = 1L
    repeat(digits) { unit *= 10 }
    return wholeDigits.toLong() * unit + (small.padEnd(digits, '0').ifEmpty { "0" }).toLong()
}
