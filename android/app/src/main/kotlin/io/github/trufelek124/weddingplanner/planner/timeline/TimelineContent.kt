package io.github.trufelek124.weddingplanner.planner.timeline

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.horizontalScrollAxisRange
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.trufelek124.weddingplanner.R
import io.github.trufelek124.weddingplanner.data.Entry
import io.github.trufelek124.weddingplanner.data.Status
import io.github.trufelek124.weddingplanner.planner.NothingHere
import io.github.trufelek124.weddingplanner.planner.shortDate
import io.github.trufelek124.weddingplanner.planner.statusLabel
import io.github.trufelek124.weddingplanner.planner.waitingInWords
import io.github.trufelek124.weddingplanner.planner.whenInWords
import io.github.trufelek124.weddingplanner.ui.QuietButton
import java.time.LocalDate
import kotlin.math.roundToInt

/** How wide a day is. A phone held upright shows about four months at a time. */
private val DAY_WIDTH = 3.dp

/** A bar is never narrower than this, so that a single day can still be seen. */
private val MIN_BAR_WIDTH = 8.dp
private val SIDE = 16.dp

/**
 * The plan's dated steps as bars on a line of months, to be scrolled and read: sideways through
 * time, up and down through the steps. Today and the wedding day are marked. Each step says its
 * name and its dates in words above its bar, and nothing here can be moved: the plan is changed in
 * the web app.
 *
 * @param controls what stands above the timeline and scrolls away with it
 * @param foot what stands under everything
 */
@Composable
fun TimelineContent(
    items: List<Entry>,
    today: LocalDate,
    weddingDate: LocalDate?,
    modifier: Modifier = Modifier,
    controls: @Composable () -> Unit = {},
    foot: @Composable () -> Unit = {},
) {
    val timeline = remember(items, today, weddingDate) { Timeline(items, today, weddingDate) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        TimelineOf(timeline, constraints.maxWidth, controls, foot)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimelineOf(timeline: Timeline, width: Int, controls: @Composable () -> Unit, foot: @Composable () -> Unit) {
    val frame = timeline.frame
    val density = LocalDensity.current
    val dayPx = with(density) { DAY_WIDTH.toPx() }
    val sidePx = with(density) { SIDE.toPx() }
    // The stretch of the screen the days are drawn on, and how far it can be moved.
    val viewport = width - 2 * sidePx
    val furthest = (frame.days * dayPx - viewport).coerceAtLeast(0f)
    fun around(day: Int): Float = (day * dayPx - viewport / 4).coerceIn(0f, furthest)

    // Opens with today a quarter in from the left: a little of what was, more of what comes.
    var moved by rememberSaveable(frame.first, frame.days) { mutableFloatStateOf(around(timeline.todayDay)) }
    fun moveBy(distance: Float): Float {
        val before = moved
        moved = (moved + distance).coerceIn(0f, furthest)
        return moved - before
    }
    val sideways = rememberScrollableState { dragged -> -moveBy(-dragged) }
    val showing by remember(frame, dayPx, viewport) {
        derivedStateOf {
            val from = frame.dateOf((moved / dayPx).toInt())
            val to = frame.dateOf(((moved + viewport) / dayPx).toInt())
            from to to
        }
    }

    val colours = MaterialTheme.colorScheme
    val marks = Marks(
        dayPx = dayPx,
        months = frame.months.map { it.day },
        today = timeline.todayDay,
        wedding = timeline.weddingDay,
        line = colours.outlineVariant,
        todayColour = colours.primary,
        weddingColour = colours.tertiary,
    )

    // Sideways is the timeline's own to move; up and down is the list's. A screen reader is told
    // of the two on two different nodes, or one would take the other's place.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .scrollable(sideways, Orientation.Horizontal)
            .semantics {
                horizontalScrollAxisRange = ScrollAxisRange(value = { moved }, maxValue = { furthest })
                scrollBy { x, _ -> moveBy(x) != 0f }
            },
    ) {
        LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), modifier = Modifier.fillMaxSize()) {
            item(key = "controls") { Box(Modifier.padding(horizontal = SIDE)) { controls() } }

            if (timeline.bars.isEmpty()) {
                item(key = "nothing") {
                    NothingHere(stringResource(R.string.timeline_empty), Modifier.padding(horizontal = SIDE))
                }
            } else {
                item(key = "moving") {
                    // Dragging sideways does the same; these are for whoever cannot drag.
                    FlowRow(modifier = Modifier.padding(horizontal = SIDE).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QuietButton(stringResource(R.string.timeline_earlier), onClick = { moveBy(-viewport * 0.75f) }, enabled = moved > 0f)
                        QuietButton(stringResource(R.string.timeline_to_today), onClick = { moved = around(timeline.todayDay) })
                        QuietButton(stringResource(R.string.timeline_later), onClick = { moveBy(viewport * 0.75f) }, enabled = moved < furthest)
                    }
                }
                // Stays in view while the steps scroll: which days these are, the months, the two marked days.
                stickyHeader(key = "scale") {
                    Column(modifier = Modifier.background(colours.background).padding(horizontal = SIDE).padding(top = 8.dp)) {
                        Text(
                            text = stringResource(R.string.timeline_showing, shortDate(showing.first), shortDate(showing.second)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colours.onSurfaceVariant,
                        )
                        Scale(frame = frame, timeline = timeline, moved = { moved }, dayPx = dayPx)
                    }
                }
                items(timeline.bars, key = { it.entry.id }) { bar ->
                    Step(bar, marks, moved = { moved }, modifier = Modifier.padding(horizontal = SIDE))
                }
            }

            item(key = "foot") {
                Column(modifier = Modifier.padding(horizontal = SIDE)) {
                    if (timeline.withoutDates > 0) {
                        Text(
                            text = pluralStringResource(R.plurals.timeline_without_dates, timeline.withoutDates, timeline.withoutDates),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colours.onSurfaceVariant,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                    foot()
                }
            }
        }
    }
}

/** What every row draws behind its bar, so that the lines run through from top to bottom. */
private class Marks(
    val dayPx: Float,
    val months: List<Int>,
    val today: Int,
    val wedding: Int?,
    val line: Color,
    val todayColour: Color,
    val weddingColour: Color,
)

private fun DrawScope.draw(marks: Marks, moved: Float) {
    for (month in marks.months) {
        val x = month * marks.dayPx - moved
        if (x in 0f..size.width) {
            drawLine(marks.line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
        }
    }
    val today = (marks.today + 0.5f) * marks.dayPx - moved
    if (today in 0f..size.width) {
        drawLine(marks.todayColour, Offset(today, 0f), Offset(today, size.height), strokeWidth = 2.dp.toPx())
    }
    if (marks.wedding != null) {
        val wedding = (marks.wedding + 0.5f) * marks.dayPx - moved
        if (wedding in 0f..size.width) {
            // Told apart from today's line by more than its colour: it is dashed.
            drawLine(
                marks.weddingColour, Offset(wedding, 0f), Offset(wedding, size.height), strokeWidth = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
            )
        }
    }
}

/** The months, and under them the names of the two marked days. It stays in view while the steps scroll. */
@Composable
private fun Scale(frame: Frame, timeline: Timeline, moved: () -> Float, dayPx: Float, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val inWords = if (timeline.weddingDate == null) {
        stringResource(R.string.timeline_marks_today, shortDate(timeline.today))
    } else {
        stringResource(R.string.timeline_marks, shortDate(timeline.today), shortDate(timeline.weddingDate))
    }
    // A screen reader is told the two days; where a label hangs on the screen means nothing to it.
    Column(modifier = modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = inWords }) {
        Box(modifier = Modifier.fillMaxWidth().clipToBounds()) {
            for (month in frame.months) {
                Text(
                    text = month.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier
                        .offset { IntOffset((month.day * dayPx - moved()).roundToInt(), 0) }
                        .width(with(density) { (month.days * dayPx).toDp() })
                        .padding(start = 4.dp),
                )
            }
        }
        Box(modifier = Modifier.fillMaxWidth().clipToBounds()) {
            // The names hang to the right of their lines.
            Text(
                text = stringResource(R.string.timeline_today),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.offset { IntOffset(((timeline.todayDay + 0.5f) * dayPx - moved()).roundToInt(), 0) }.padding(start = 4.dp),
            )
            timeline.weddingDay?.let { day ->
                Text(
                    text = stringResource(R.string.timeline_wedding),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.offset { IntOffset(((day + 0.5f) * dayPx - moved()).roundToInt(), 0) }.padding(start = 4.dp),
                )
            }
        }
    }
}

/** One step: its name, its dates and state in words, and its bar. A screen reader gets the words as one line. */
@Composable
private fun Step(bar: Bar, marks: Marks, moved: () -> Float, modifier: Modifier = Modifier) {
    val colours = MaterialTheme.colorScheme
    val entry = bar.entry
    val closed = !entry.status.open
    val fill = when (entry.status) {
        Status.IN_PROGRESS -> colours.primary
        Status.DONE, Status.DROPPED -> colours.onSurfaceVariant.copy(alpha = 0.45f)
        else -> colours.primary.copy(alpha = 0.55f)
    }
    Column(modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Text(
            text = entry.title,
            style = MaterialTheme.typography.titleSmall,
            color = if (closed) colours.onSurfaceVariant else colours.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            // A step added or changed on this phone and not sent yet says so here too.
            text = listOfNotNull(waitingInWords(entry.waiting), whenInWords(entry), statusLabel(entry.status)).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = colours.onSurfaceVariant,
        )
        Box(
            modifier = Modifier.fillMaxWidth().height(22.dp).drawBehind {
                val at = moved()
                draw(marks, at)
                val left = bar.startDay * marks.dayPx - at
                val width = maxOf(bar.days * marks.dayPx, MIN_BAR_WIDTH.toPx())
                val top = 5.dp.toPx()
                val height = size.height - 2 * top
                when {
                    // The bar lies outside what is shown: an arrow at the edge says on which side.
                    left + width < 0f -> arrow(fill, pointsLeft = true)
                    left > size.width -> arrow(fill, pointsLeft = false)
                    else -> drawRoundRect(fill, Offset(left, top), Size(width, height), CornerRadius(4.dp.toPx()))
                }
            },
        )
    }
}

private fun DrawScope.arrow(colour: Color, pointsLeft: Boolean) {
    val reach = 7.dp.toPx()
    val middle = size.height / 2
    val tip = if (pointsLeft) 0f else size.width
    val back = if (pointsLeft) reach else size.width - reach
    drawPath(
        Path().apply {
            moveTo(tip, middle)
            lineTo(back, middle - reach * 0.7f)
            lineTo(back, middle + reach * 0.7f)
            close()
        },
        colour,
    )
}
