package io.github.trufelek124.weddingplanner.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.AppWidgetId
import androidx.test.core.app.ApplicationProvider
import io.github.trufelek124.weddingplanner.R
import io.github.trufelek124.weddingplanner.StandInPhone
import io.github.trufelek124.weddingplanner.WeddingPlannerApp
import io.github.trufelek124.weddingplanner.api.StandInServer
import io.github.trufelek124.weddingplanner.data.LivingPlanner
import io.github.trufelek124.weddingplanner.data.StandIn
import io.github.trufelek124.weddingplanner.data.planner
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The widget follows the phone's copy and the session while it runs (Glance does not start `provideGlance` again while a
 * composition is running, so what it draws must change by itself): first the flow of what it shows, then the real widget's
 * own composition, one running composition from the first draw to the last.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetFollowsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var phone: StandInPhone
    private lateinit var theirs: LivingPlanner
    private val server get() = phone.server
    private val container get() = phone.container
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Before
    fun aSignedInPhone() {
        phone = StandInPhone(folder.root)
        (context as WeddingPlannerApp).container = phone.container
        server.json("POST /api/auth/dev", StandInServer.session())
        server.json("POST /api/auth/refresh", StandInServer.session(accessToken = "access-2", refreshToken = "refresh-2"))
        server.nothing("POST /api/auth/sign-out")
        server.planner()
        theirs = LivingPlanner(
            server,
            entries = listOf(
                StandIn.entry("t1", "Book the band", dueDate = "2026-11-12"),
                StandIn.entry("t2", "Pay the venue's deposit", dueDate = "2026-11-18", amount = 250000),
                StandIn.entry("p1", "Find the venue", kind = "PLAN_ITEM", dueDate = "2026-11-25"),
            ),
        )
        runBlocking {
            container.session.signInForDevelopment("anna@example.com")
            container.planner.sync()
        }
    }

    @After
    fun off() {
        scope.cancel()
        phone.off()
    }

    private fun waitUntil(what: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!what()) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.currentTimeMillis() < until) { "waited for ten seconds" }
            Thread.sleep(10)
        }
    }

    private fun namesIn(model: WidgetModel) = (model as? WidgetModel.Current)?.rows?.map { it.name }.orEmpty()

    // ---------------------------------------------------------------- the flow

    @Test
    fun `the flow follows a tick, a load and a sign-out, and never holds a row once nobody is signed in`() {
        val seen = CopyOnWriteArrayList<WidgetModel>()
        scope.launch { container.widgetModels.follow().collect { seen += it } }
        waitUntil { seen.isNotEmpty() }
        assertEquals(listOf("Book the band", "Pay the venue's deposit", "Find the venue"), namesIn(seen.last()))

        runBlocking { container.widgetActions.tick("t1") }
        waitUntil { namesIn(seen.last()) == listOf("Pay the venue's deposit", "Find the venue") }

        // The partner added a task meanwhile: a load brings it.
        theirs.put(StandIn.entry("t9", "Order the cake", dueDate = "2026-11-19"))
        runBlocking { container.planner.sync() }
        waitUntil { namesIn(seen.last()).contains("Order the cake") }

        runBlocking { container.session.signOut() }
        waitUntil { seen.last() == WidgetModel.SignedOut("Open the planner to sign in.") }
        // From the moment the session ended no value carries a row of the plan.
        val ended = seen.indexOfFirst { it is WidgetModel.SignedOut }
        assertTrue(seen.drop(ended).none { it is WidgetModel.Current })
        assertFalse(seen.drop(ended).any { namesIn(it).isNotEmpty() })
    }

    // ---------------------------------------------------------------- the real widget's own composition

    private fun texts(views: RemoteViews): List<String> {
        val host = FrameLayout(context)
        host.addView(views.apply(context, host))
        val found = mutableListOf<String>()
        fun walk(v: View) {
            if (v is TextView) found += v.text.toString()
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(host)
        return found
    }

    /** The real widget's own composition, running: everything it draws from now on. One call is one running composition. */
    private fun running(): CopyOnWriteArrayList<RemoteViews> {
        val id = 7
        shadowOf(AppWidgetManager.getInstance(context)).addBoundWidget(
            id,
            AppWidgetProviderInfo().apply {
                provider = ComponentName(context, PlannerWidgetReceiver::class.java)
                minWidth = 250
                minHeight = 180
                initialLayout = R.layout.widget_preview
            },
        )
        // Glance's own translation of the widget's content (reflection: not public API).
        val run = Class.forName("androidx.glance.appwidget.AppWidgetComposerKt").declaredMethods.first { it.name == "runComposition" && it.parameterCount == 7 }

        @Suppress("UNCHECKED_CAST")
        val drawn = run.invoke(null, PlannerWidget(), context, AppWidgetId(id), Bundle(), listOf(DpSize(300.dp, 260.dp)), null, null) as Flow<RemoteViews>
        val everything = CopyOnWriteArrayList<RemoteViews>()
        scope.launch { drawn.collect { everything += it } }
        return everything
    }

    /** Lets the app and the composition work for [millis], as they would on a phone that is left alone. */
    private fun leftAlone(millis: Long) {
        val until = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    @Test
    fun `while nothing changes the widget is not drawn again and again`() {
        val everything = running()
        waitUntil { everything.toList().any { "3 things" in texts(it) } }
        leftAlone(500)
        val drawn = everything.size

        // A composition that asked for its content anew at every drawing would go round and round here.
        leftAlone(1_500)

        assertEquals(drawn, everything.size)
    }

    @Test
    fun `in one running composition the head goes from three things to two after a tick, and to the sign-in sentence after signing out`() {
        val everything = running()

        fun said(text: String) = everything.toList().any { text in texts(it) }
        waitUntil { said("3 things") }

        runBlocking { container.widgetActions.tick("t1") }
        waitUntil { said("2 things") }

        runBlocking { container.session.signOut() }
        waitUntil { said("Open the planner to sign in.") }

        // The last thing drawn is the sentence: nothing of the plan stands next to it.
        val last = texts(everything.last())
        assertTrue(last.toString(), "Open the planner to sign in." in last)
        assertFalse(last.toString(), last.any { it == "Wedding planner" || it.equals("Add a task", ignoreCase = true) })
    }
}
