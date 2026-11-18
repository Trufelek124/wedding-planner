package io.github.trufelek124.weddingplanner.planner.timeline

import io.github.trufelek124.weddingplanner.data.Entry
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/*
 * Where things go on the timeline. Everything is arithmetic on days, as on the web's chart
 * (web/src/planning/chart/layout.ts): nothing is measured on the screen, so the same dates always
 * give the same picture.
 */

/** The days a plan item covers, both included. */
data class Span(val start: LocalDate, val end: LocalDate)

/** One with only an end date covers that day; one without an end date is not on the timeline. */
fun spanOf(entry: Entry): Span? {
    val end = entry.dueDate ?: return null
    val start = entry.startDate
    return Span(if (start == null || start > end) end else start, end)
}

/** A month on the scale: where it begins, counted in days from the frame's first day, and what it is called. */
data class Month(val day: Int, val days: Int, val label: String)

/**
 * The stretch of days the timeline shows: whole months around everything that has a date, with
 * two weeks of room before and after.
 */
class Frame(val first: LocalDate, val days: Int) {

    /** How many days after the frame's first day a date is. */
    fun dayOf(date: LocalDate): Int = ChronoUnit.DAYS.between(first, date).toInt()

    /** The date of a day counted from the frame's first; days outside the frame give its ends. */
    fun dateOf(day: Int): LocalDate = first.plusDays(day.coerceIn(0, days - 1).toLong())

    /** The months of the frame, by their short names. Which year is said beside the scale, not on it. */
    val months: List<Month> = buildList {
        var month = first
        while (dayOf(month) < days) {
            add(Month(dayOf(month), month.lengthOfMonth(), month.format(NAME)))
            month = month.plusMonths(1)
        }
    }

    companion object {
        private val NAME = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)
        private const val ROOM = 14L

        fun around(dates: List<LocalDate>): Frame {
            val earliest = dates.min().minusDays(ROOM).withDayOfMonth(1)
            val end = dates.max().plusDays(ROOM).withDayOfMonth(1).plusMonths(1)
            return Frame(earliest, ChronoUnit.DAYS.between(earliest, end).toInt())
        }
    }
}

/** A plan item on the timeline: the entry and where its bar lies, in days from the frame's first day. */
class Bar(val entry: Entry, val span: Span, val startDay: Int, val days: Int)

/**
 * The timeline of the given plan items. Those without an end date are not on it; today and the
 * wedding day always are, so that both marks can be found.
 */
class Timeline(items: List<Entry>, val today: LocalDate, val weddingDate: LocalDate?) {

    private val dated = items.mapNotNull { entry -> spanOf(entry)?.let { entry to it } }

    /** How many of the items have no dates and are therefore only in the list. */
    val withoutDates: Int = items.size - dated.size

    val frame: Frame = Frame.around(dated.flatMap { listOf(it.second.start, it.second.end) } + listOfNotNull(today, weddingDate))

    /** In the order given, which is the list's order. */
    val bars: List<Bar> = dated.map { (entry, span) ->
        Bar(entry, span, frame.dayOf(span.start), ChronoUnit.DAYS.between(span.start, span.end).toInt() + 1)
    }

    val todayDay: Int = frame.dayOf(today)

    val weddingDay: Int? = weddingDate?.let(frame::dayOf)
}
