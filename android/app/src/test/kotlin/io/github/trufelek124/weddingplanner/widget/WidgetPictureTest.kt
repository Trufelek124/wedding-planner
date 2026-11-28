package io.github.trufelek124.weddingplanner.widget

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.appwidget.AppWidgetId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.trufelek124.weddingplanner.data.Kind
import io.github.trufelek124.weddingplanner.data.Status
import io.github.trufelek124.weddingplanner.ui.PHONE
import io.github.trufelek124.weddingplanner.ui.PHONE_AT_NIGHT
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pictures of the widget: Glance's own translation of its content into the views a launcher would get, drawn by Robolectric
 * as an ordinary view. A launcher's own host, its rounded corners and the list's adapter (which the launcher's process
 * fills) are not here, so the picture shows the layout and the colours and not exactly what a launcher shows.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class WidgetPictureTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private companion object {
        const val WIDTH_PX = 600
        const val HEIGHT_PX = 520
    }

    /** A widget of the planner's on the (shadow) home screen, so that the platform knows it. */
    private val onTheScreen: Int = 7.also { id ->
        val info = android.appwidget.AppWidgetProviderInfo().apply {
            provider = android.content.ComponentName(context, PlannerWidgetReceiver::class.java)
            minWidth = 250
            minHeight = 180
            initialLayout = io.github.trufelek124.weddingplanner.R.layout.widget_preview
        }
        shadowOf(android.appwidget.AppWidgetManager.getInstance(context)).addBoundWidget(id, info)
    }

    /** Glance's translation of [content] into views, as the launcher would be handed them (not public API of Glance: read by reflection). */
    private fun views(content: @androidx.compose.runtime.Composable () -> Unit, size: DpSize = DpSize(300.dp, 260.dp)): RemoteViews {
        val widget: GlanceAppWidget = object : GlanceAppWidget() {
            override val sizeMode = androidx.glance.appwidget.SizeMode.Single
            override suspend fun provideGlance(context: Context, id: GlanceId) {
                provideContent { content() }
            }
        }
        val compose = Class.forName("androidx.glance.appwidget.AppWidgetComposerKt").declaredMethods.first { it.name == "runComposition" && it.parameterCount == 7 }
        @Suppress("UNCHECKED_CAST")
        val flow = compose.invoke(null, widget, context, AppWidgetId(onTheScreen), Bundle(), listOf(size), null, null) as Flow<RemoteViews>
        return runBlocking { flow.first() }
    }

    private fun picture(name: String, model: WidgetModel) = pictureOf(name) { PlannerWidgetContent(model) }

    private fun pictureOf(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        val remote = views(content)
        // The picture is taken of a view that is in a window: the widget's views go into an activity of the test.
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val host = FrameLayout(activity)
                activity.setContentView(host, ViewGroup.LayoutParams(WIDTH_PX, HEIGHT_PX))
                host.addView(remote.apply(activity, host), FrameLayout.LayoutParams(WIDTH_PX, HEIGHT_PX))
                host.measure(View.MeasureSpec.makeMeasureSpec(WIDTH_PX, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(HEIGHT_PX, View.MeasureSpec.EXACTLY))
                host.layout(0, 0, WIDTH_PX, HEIGHT_PX)
                host.captureRoboImage("build/outputs/roborazzi/$name.png")
            }
        }
        assertTrue(java.io.File("build/outputs/roborazzi/$name.png").length() > 0)
    }

    private fun plan() = currentModelOf(
        currentThings(
            aPlanner(
                anEntry("Book the band", "2026-11-22"),
                anEntry("Pay the venue's deposit", "2026-11-28", amount = 250000),
                anEntry("Find the venue", "2026-12-03", kind = Kind.PLAN_ITEM),
                anEntry("Order the cake", null, status = Status.IN_PROGRESS),
            ),
            WIDGET_TODAY,
        ),
        context.resources,
    )

    @Test
    fun `the widget with a plan, in light`() = picture("widget-light", plan())

    @Test
    @Config(qualifiers = PHONE_AT_NIGHT)
    fun `the widget with a plan, in dark`() = picture("widget-dark", plan())

    @Test
    fun `the widget with nothing current`() = picture("widget-nothing", currentModelOf(emptyList(), context.resources))

    @Test
    fun `the widget when nobody is signed in`() = picture("widget-signed-out", WidgetModel.SignedOut(context.getString(io.github.trufelek124.weddingplanner.R.string.widget_signed_out)))

    /**
     * The list's rows are filled in by the launcher's process (the platform binds a service for them), which Robolectric does
     * not run: the pictures above show the head and the buttons, and the list's area stays empty. The rows are drawn here
     * by the same composable, one under the other and outside the list.
     */
    private fun rowsOutsideTheList(name: String) = pictureOf(name) {
        androidx.glance.layout.Column(
            modifier = androidx.glance.GlanceModifier.fillMaxSize().background(androidx.glance.GlanceTheme.colors.background).padding(12.dp),
        ) {
            plan().rows.forEach { WidgetRowContent(it) }
        }
    }

    @Test
    fun `the rows of the widget, outside the list, in light`() = rowsOutsideTheList("widget-rows-light")

    @Test
    @Config(qualifiers = PHONE_AT_NIGHT)
    fun `the rows of the widget, outside the list, in dark`() = rowsOutsideTheList("widget-rows-dark")
}
