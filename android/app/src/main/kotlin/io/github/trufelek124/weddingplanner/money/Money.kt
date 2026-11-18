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
