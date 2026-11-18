package io.github.trufelek124.weddingplanner.money

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The same amounts read the same on the phone as in the web app (web/src/money/money.test.ts). */
@RunWith(RobolectricTestRunner::class)
class MoneyTest {

    @Test
    fun `writes an amount with its small part, the thousands apart, and the currency after it`() {
        assertEquals("2,500.50\u00a0zł", money(250_050, "PLN"))
        assertEquals("0.05\u00a0zł", money(5, "PLN"))
        assertEquals("0.00\u00a0zł", money(0, "PLN"))
        assertEquals("1,200.00\u00a0€", money(120_000, "EUR"))
        assertEquals("1,200.00\u00a0₴", money(120_000, "UAH"))
    }

    @Test
    fun `keeps the largest amounts exact`() {
        assertEquals("9,999,999,999.99\u00a0zł", money(999_999_999_999, "PLN"))
        assertEquals("10,000,000,000.02\u00a0zł", money(1_000_000_000_002, "PLN"))
    }

    @Test
    fun `a currency without a small unit has no decimals`() {
        assertEquals("2,500\u00a0¥", money(2_500, "JPY"))
    }

    @Test
    fun `an amount below nothing carries a minus`() {
        assertEquals("\u2212120.00\u00a0zł", money(-12_000, "PLN"))
    }

    @Test
    fun `any other currency gets the sign the phone knows for it`() {
        assertEquals("2,500.00\u00a0Kč", money(250_000, "CZK"))
        assertEquals("2,500.00\u00a0CHF", money(250_000, "CHF"))
    }

    @Test
    fun `a currency the phone does not know is shown by its code`() {
        assertEquals("12.50\u00a0XQQ", money(1_250, "XQQ"))
        assertEquals("12.50\u00a0not a code", money(1_250, "not a code"))
    }
}
