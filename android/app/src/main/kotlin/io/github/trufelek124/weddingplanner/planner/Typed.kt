package io.github.trufelek124.weddingplanner.planner

import java.time.DateTimeException
import java.time.LocalDate

/*
 * Days as a person types them on a phone in Poland or Ukraine: day, month, year, with points
 * between them. A picker stands beside every such field; typing is for whoever is faster that way.
 */

private val TYPED = Regex("^(\\d{1,2})[./-](\\d{1,2})[./-](\\d{4})$")
private val ISO = Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$")

/** "15.01.2027" */
fun dayToType(date: LocalDate): String = "%02d.%02d.%04d".format(date.dayOfMonth, date.monthValue, date.year)

/**
 * Reads "15.01.2027", "15/1/2027", "15-01-2027" or "2027-01-15". Null for anything else, a day
 * that does not exist (30.02.) included.
 */
fun dayTyped(text: String): LocalDate? {
    val typed = text.trim()
    val (year, month, day) = TYPED.matchEntire(typed)?.destructured?.let { (d, m, y) -> Triple(y, m, d) }
        ?: ISO.matchEntire(typed)?.destructured?.let { (y, m, d) -> Triple(y, m, d) }
        ?: return null
    return try {
        LocalDate.of(year.toInt(), month.toInt(), day.toInt())
    } catch (noSuchDay: DateTimeException) {
        null
    }
}
