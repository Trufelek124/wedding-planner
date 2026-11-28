package io.github.trufelek124.weddingplanner.widget

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.Button
import io.github.trufelek124.weddingplanner.MainActivity
import io.github.trufelek124.weddingplanner.WeddingPlannerApp
import io.github.trufelek124.weddingplanner.reminders.OpenAt
import io.github.trufelek124.weddingplanner.ui.PlannerDarkColors
import io.github.trufelek124.weddingplanner.ui.PlannerLightColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** The planner's own colours for the widget, light and dark as the phone is: the same as the screens'. */
private val WidgetColors = ColorProviders(light = PlannerLightColors, dark = PlannerDarkColors)

/**
 * The intent a press on the widget sends to the app, with what it asks to open ([OpenAt]); none, for a press that only
 * opens the app. The app is brought to the front where it is, and asked there (`onNewIntent`), as a reminder's is.
 */
fun openFromWidget(context: Context, opening: String?): Intent =
    Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .apply { opening?.let { putExtra(OpenAt.EXTRA, it) } }

/** Which task a press on a box is about. */
val EntryKey = ActionParameters.Key<String>("io.github.trufelek124.weddingplanner.widget.ENTRY")

/**
 * The widget on the home screen: what is current, drawn from the phone's copy each time it is asked to draw itself. One
 * layout that scales with its size; the list moves where it is longer than the widget.
 */
class PlannerWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val models = (context.applicationContext as WeddingPlannerApp).container.widgetModels
        // Loaded before the content: Glance does not start this again while a composition runs (about 45 seconds after the
        // last draw), so the content follows the copy and the session itself.
        val first = models.now()
        provideContent {
            // Remembered: a flow made anew at every drawing would be collected anew each time, hand over a new value,
            // and so draw the widget round and round for as long as the composition runs.
            val following = remember { models.follow() }
            val model by following.collectAsState(first)
            PlannerWidgetContent(model)
        }
    }
}

/** The widget's receiver: the phone sends it the widget's life (put on the screen, resized, taken off). */
class PlannerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PlannerWidget()
}

/** A press on a task's box: the task is marked done, and nothing else (see [WidgetActions.tick]). */
class TickCallback : ActionCallback {

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val entryId = parameters[EntryKey] ?: return
        (context.applicationContext as WeddingPlannerApp).container.widgetActions.tick(entryId)
    }
}

/** What the widget draws for [model]. */
@Composable
fun PlannerWidgetContent(model: WidgetModel) {
    GlanceTheme(colors = WidgetColors) {
        when (model) {
            is WidgetModel.SignedOut -> Sentence(model.sentence)
            is WidgetModel.NoCopy -> Sentence(model.sentence)
            is WidgetModel.Current -> Current(model)
        }
    }
}

/** Nothing of a plan: one sentence, and a press anywhere opens the app (which shows the sign-in, or loads). */
@Composable
private fun Sentence(text: String) {
    val open = actionStartActivity(openFromWidget(LocalContext.current, null))
    Column(
        modifier = GlanceModifier.fillMaxSize().background(GlanceTheme.colors.background).padding(16.dp).clickable(open),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 16.sp))
    }
}

@Composable
private fun Current(model: WidgetModel.Current) {
    val context = LocalContext.current
    Column(modifier = GlanceModifier.fillMaxSize().background(GlanceTheme.colors.background).padding(12.dp)) {
        Column(modifier = GlanceModifier.fillMaxWidth().clickable(actionStartActivity(openFromWidget(context, OpenAt.TASKS)))) {
            Text(model.name, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold), maxLines = 1)
            Text(model.count, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp), maxLines = 1)
        }
        Spacer(GlanceModifier.height(8.dp))
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Button(model.addTask, actionStartActivity(openFromWidget(context, OpenAt.ADD_TASK)), modifier = GlanceModifier.defaultWeight())
            Spacer(GlanceModifier.width(8.dp))
            Button(model.addStep, actionStartActivity(openFromWidget(context, OpenAt.ADD_STEP)), modifier = GlanceModifier.defaultWeight())
        }
        Spacer(GlanceModifier.height(8.dp))
        if (model.nothing != null) {
            Text(model.nothing, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp))
        } else {
            LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                items(model.rows, itemId = { it.entryId.hashCode().toLong() }) { row -> WidgetRowContent(row) }
            }
        }
    }
}

/** A row: the task's box (a step has none), its name and under it its day. A press on the words opens the entry's form. */
@Composable
internal fun WidgetRowContent(row: WidgetRow) {
    val context = LocalContext.current
    val opening = if (row.isStep) OpenAt.step(row.entryId) else OpenAt.task(row.entryId)
    Row(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (row.tick != null) {
            CheckBox(
                checked = false,
                onCheckedChange = actionRunCallback<TickCallback>(actionParametersOf(EntryKey to row.entryId)),
                modifier = GlanceModifier.semantics { contentDescription = row.tick },
            )
        } else {
            // Where a task's box is: a step's name stands in line with the names of the tasks.
            Spacer(GlanceModifier.width(32.dp))
        }
        Column(
            modifier = GlanceModifier.defaultWeight().padding(vertical = 4.dp).clickable(actionStartActivity(openFromWidget(context, opening))),
        ) {
            Text(row.name, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp), maxLines = 2)
            Text(row.detail, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 1)
        }
    }
}

/**
 * Drawing the widget as the phone does it. Every request is folded into the one that is waiting, so a load that writes
 * twenty times draws once or twice. A phone with no widget on its screen has nothing to draw, which costs nothing.
 */
class WidgetOnThePhone(private val context: Context) : WidgetDrawing {

    private val asked = Channel<Unit>(Channel.CONFLATED)

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            for (request in asked) {
                try {
                    PlannerWidget().updateAll(context)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failed: Exception) {
                    // Only its kind is said: a widget that cannot be drawn is not the app's trouble.
                    Log.w(TAG, "The widget could not be drawn: ${failed.javaClass.simpleName}")
                }
            }
        }
    }

    override fun redraw() {
        asked.trySend(Unit)
    }

    private companion object {
        const val TAG = "Widget"
    }
}
