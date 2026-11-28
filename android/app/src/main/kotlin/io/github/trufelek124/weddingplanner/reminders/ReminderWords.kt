package io.github.trufelek124.weddingplanner.reminders

import android.content.res.Resources
import io.github.trufelek124.weddingplanner.R

/** The most things a reminder names on a line of its own; the rest are counted. */
const val MOST_LINES = 5

/**
 * What a reminder says. Not a data class: it holds the names of tasks, which are not to be printed.
 *
 * @param title "Pay the venue's deposit is due today", or "4 things are due"
 * @param text the line under the title in a notification that is not opened up; null where the title says it all
 * @param lines what the opened notification lists, one thing a line and "and 3 more" at the end; empty for one thing
 * @param publicTitle what a locked phone that hides private content may still show: how many, and no names
 * @param count how many things it names, "and n more" included
 */
class Reminder(val title: String, val text: String?, val lines: List<String>, val publicTitle: String, val count: Int) {

    override fun toString(): String = "Reminder[count=$count]"
}

/** The day as a reminder says it: "overdue since 22 Nov", "today", "tomorrow", "on 3 Dec". */
fun dayInWords(thing: DueThing, resources: Resources): String = when (val day = thing.day) {
    is Day.Overdue -> resources.getString(R.string.reminder_day_overdue, dayWords(day.since, thing.today))
    Day.Today -> resources.getString(R.string.reminder_day_today)
    Day.Tomorrow -> resources.getString(R.string.reminder_day_tomorrow)
    is Day.On -> resources.getString(R.string.reminder_day_on, dayWords(day.day, thing.today))
}

/** One line of the list: "Pay the venue's deposit, 2,500.00 zł — today". */
fun lineOf(thing: DueThing, resources: Resources): String {
    val day = dayInWords(thing, resources)
    return if (thing.amount == null) {
        resources.getString(R.string.reminder_line, thing.name, day)
    } else {
        resources.getString(R.string.reminder_line_with_amount, thing.name, thing.amount, day)
    }
}

/** What to say of [things], in the order they come in; null when there is nothing to say. */
fun reminderOf(things: List<DueThing>, resources: Resources): Reminder? {
    val count = things.size
    val publicTitle = resources.getQuantityString(R.plurals.reminder_several, count, count)
    return when (count) {
        0 -> null
        1 -> {
            val thing = things.single()
            val title = when (val day = thing.day) {
                is Day.Overdue -> resources.getString(R.string.reminder_one_overdue, thing.name)
                Day.Today -> resources.getString(R.string.reminder_one_today, thing.name)
                Day.Tomorrow -> resources.getString(R.string.reminder_one_tomorrow, thing.name)
                is Day.On -> resources.getString(R.string.reminder_one_on, thing.name, dayWords(day.day, thing.today))
            }
            val text = thing.amount?.let { resources.getString(R.string.reminder_payment_of, it) }
            Reminder(title, text, emptyList(), publicTitle, count)
        }
        else -> {
            val lines = things.take(MOST_LINES).map { lineOf(it, resources) }.toMutableList()
            if (count > MOST_LINES) {
                lines += resources.getString(R.string.reminder_more, count - MOST_LINES)
            }
            Reminder(publicTitle, lines.first(), lines, publicTitle, count)
        }
    }
}
