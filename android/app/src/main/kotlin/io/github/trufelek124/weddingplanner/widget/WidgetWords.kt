package io.github.trufelek124.weddingplanner.widget

import android.content.res.Resources
import io.github.trufelek124.weddingplanner.R
import io.github.trufelek124.weddingplanner.reminders.dayInWords

/**
 * What the widget shows, said in words: [Current] with the planner's name and its rows, nothing of a plan while nobody
 * is signed in, and a sentence of its own while the phone holds no copy yet. Nothing of Glance is in here, so that what
 * the widget says is tested without drawing it.
 */
sealed interface WidgetModel {

    /** Nobody is signed in: one sentence, and nothing of anybody's plan. */
    data class SignedOut(val sentence: String) : WidgetModel

    /** Signed in, but the phone holds nothing of the planner yet. */
    data class NoCopy(val sentence: String) : WidgetModel

    /**
     * @param name the planner's name, as the head says it
     * @param count how many things are listed, in words: "3 things", or "Nothing current"
     * @param nothing the sentence for an empty list; null while rows are listed
     */
    class Current(
        val name: String,
        val count: String,
        val nothing: String?,
        val rows: List<WidgetRow>,
        val addTask: String,
        val addStep: String,
    ) : WidgetModel {

        override fun toString(): String = "Current[rows=${rows.size}]"
    }
}

/**
 * A row of the widget. Not a data class: it holds a task's name.
 *
 * @param detail under the name: the day in words, a payment's amount before it, "Step" before a step's
 * @param tick what the box says to a screen reader; null for a step, which has no box
 */
class WidgetRow(val entryId: String, val name: String, val detail: String, val isStep: Boolean, val tick: String?) {

    override fun toString(): String = "WidgetRow[entryId=$entryId]"
}

/** The words of one row: "Pay the venue's deposit" over "2,500.00 zł — today"; a step: "Step — on 3 Dec". */
fun rowOf(thing: CurrentThing, resources: Resources): WidgetRow {
    val day = thing.day?.let { dayInWords(it, thing.today, resources) } ?: resources.getString(R.string.widget_day_in_progress)
    val detail = when {
        thing.isStep -> resources.getString(R.string.widget_detail_step, day)
        thing.amount != null -> resources.getString(R.string.widget_detail_payment, thing.amount, day)
        else -> day
    }
    val tick = if (thing.isStep) null else resources.getString(if (thing.amount != null) R.string.tick_paid else R.string.tick_done, thing.name)
    return WidgetRow(thing.entryId, thing.name, detail, thing.isStep, tick)
}

/** What the widget shows for [things]. */
fun currentModelOf(things: List<CurrentThing>, resources: Resources): WidgetModel.Current = WidgetModel.Current(
    name = resources.getString(R.string.app_name),
    count = if (things.isEmpty()) resources.getString(R.string.widget_count_none) else resources.getQuantityString(R.plurals.widget_count, things.size, things.size),
    nothing = if (things.isEmpty()) resources.getString(R.string.widget_nothing_current) else null,
    rows = things.map { rowOf(it, resources) },
    addTask = resources.getString(R.string.task_add),
    addStep = resources.getString(R.string.step_add),
)
