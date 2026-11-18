package io.github.trufelek124.weddingplanner.planner

import io.github.trufelek124.weddingplanner.money.moneyToType
import io.github.trufelek124.weddingplanner.money.moneyTyped
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** What a person types into the task's form: days and amounts. The amounts read as in the web app (money.test.ts). */
@RunWith(RobolectricTestRunner::class)
class TypedTest {

    @Test
    fun `a day is typed day, month, year, with points, strokes or dashes between`() {
        val day = LocalDate.of(2027, 1, 15)
        assertEquals(day, dayTyped("15.01.2027"))
        assertEquals(day, dayTyped("15.1.2027"))
        assertEquals(day, dayTyped("15/01/2027"))
        assertEquals(day, dayTyped("15-01-2027"))
        assertEquals(day, dayTyped(" 15.01.2027 "))
        assertEquals(LocalDate.of(2027, 6, 5), dayTyped("5.6.2027"))
    }

    @Test
    fun `the way the server writes a day is read too`() {
        assertEquals(LocalDate.of(2027, 1, 15), dayTyped("2027-01-15"))
    }

    @Test
    fun `what is no day is not read as one`() {
        for (typed in listOf("", "tomorrow", "15.01", "15.01.27", "30.02.2027", "32.01.2027", "15.13.2027", "15..2027", "2027/01/15", "15.01.2027 10:00")) {
            assertNull(typed, dayTyped(typed))
        }
    }

    @Test
    fun `a day is written into the field as it is typed, and read back`() {
        assertEquals("05.06.2027", dayToType(LocalDate.of(2027, 6, 5)))
        for (day in listOf(LocalDate.of(2026, 11, 18), LocalDate.of(2028, 2, 29), LocalDate.of(2027, 12, 31))) {
            assertEquals(day, dayTyped(dayToType(day)))
        }
    }

    @Test
    fun `an amount is read in whole grosze however it is typed`() {
        val read = mapOf(
            "2500" to 250_000L, "2 500" to 250_000L, "2\u00a0500,50" to 250_050L, "2500.5" to 250_050L, "2500,50" to 250_050L,
            "2,500" to 250_000L, "2.500" to 250_000L, "2,500.50" to 250_050L, "2.500,50" to 250_050L, "1,234,567.89" to 123_456_789L,
            "0,05" to 5L, "0.29" to 29L, "19.99" to 1_999L, " 80 " to 8_000L,
        )
        for ((typed, grosze) in read) {
            assertEquals(typed, grosze, moneyTyped(typed, "PLN"))
        }
    }

    @Test
    fun `what is no amount is not read as one`() {
        for (typed in listOf("", "abc", "12 zł", "-5", "2500.123", "2500.", "1,23,456", ".50", "2..5", "1e3", "99999999999999999999")) {
            assertNull(typed, moneyTyped(typed, "PLN"))
        }
    }

    @Test
    fun `a field gets the plain number, and reads back whatever it wrote`() {
        assertEquals("2500", moneyToType(250_000, "PLN"))
        assertEquals("2500.50", moneyToType(250_050, "PLN"))
        assertEquals("0.05", moneyToType(5, "PLN"))
        for (grosze in listOf(1L, 29L, 57L, 100L, 1_999L, 250_050L, 999_999_999_999L)) {
            assertEquals(grosze, moneyTyped(moneyToType(grosze, "PLN"), "PLN"))
        }
    }

    @Test
    fun `a currency without a small unit takes whole numbers only`() {
        assertEquals(2_500L, moneyTyped("2,500", "JPY"))
        assertEquals(2_500L, moneyTyped("2500", "JPY"))
        assertNull(moneyTyped("2500.5", "JPY"))
        assertEquals("2500", moneyToType(2_500, "JPY"))
    }
}
