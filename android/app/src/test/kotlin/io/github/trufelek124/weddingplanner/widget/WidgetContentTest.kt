package io.github.trufelek124.weddingplanner.widget

import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.EmittableCheckBox
import androidx.glance.action.ActionModifier
import androidx.glance.appwidget.action.RunCallbackAction
import androidx.glance.findModifier
import androidx.glance.testing.GlanceNodeMatcher
import androidx.glance.testing.unit.MappedNode
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasAnyDescendant
import androidx.glance.testing.unit.hasContentDescriptionEqualTo
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.test.core.app.ApplicationProvider
import io.github.trufelek124.weddingplanner.MainActivity
import io.github.trufelek124.weddingplanner.data.Kind
import io.github.trufelek124.weddingplanner.data.Status
import io.github.trufelek124.weddingplanner.reminders.OpenAt
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The widget's content, drawn into the platform's unit-test environment for Glance: it checks the words, the boxes and
 * which action each press carries. It does not draw pixels and does not run the launcher's own widget host.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetContentTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val resources = context.resources

    private fun current(vararg entries: io.github.trufelek124.weddingplanner.data.Entry) =
        currentModelOf(currentThings(aPlanner(*entries), WIDGET_TODAY), resources)

    /** A box that runs [TickCallback] for the entry: Glance's own matchers know clicks, and a box is not one. */
    private fun ticks(entryId: String) = GlanceNodeMatcher<MappedNode>("a box that ticks $entryId") { node ->
        val emittable = node.value.emittable
        // The box's action is wrapped in Glance's own class for boxes, which the library keeps to itself: read through its getter.
        val pressed = (emittable as? EmittableCheckBox)?.modifier?.findModifier<ActionModifier>()?.action
        val action = pressed?.takeIf { it.javaClass.simpleName == "CompoundButtonAction" }?.let { it.javaClass.getMethod("getInnerAction").invoke(it) } as? RunCallbackAction
        action != null && action.callbackClass == TickCallback::class.java && action.parameters[EntryKey] == entryId
    }

    /**
     * A press that starts the planner's activity asking to open [opening] (null: just open it). Glance's own matcher compares
     * the intent without its extras, which would let a button or a row open the wrong thing and still match: the extras are
     * read here, through the getter of the library's internal action class.
     */
    private fun opens(opening: String?) = GlanceNodeMatcher<MappedNode>("a press that opens the planner at ${opening ?: "its front"}") { node ->
        val pressed = node.value.emittable.modifier.findModifier<ActionModifier>()?.action
        val intent = pressed?.takeIf { it.javaClass.simpleName == "StartActivityIntentAction" }?.let { it.javaClass.getMethod("getIntent").invoke(it) } as? Intent
        intent != null && intent.component?.className == MainActivity::class.java.name && intent.getStringExtra(OpenAt.EXTRA) == opening
    }

    private fun anyBox() = GlanceNodeMatcher<MappedNode>("a box") { node -> node.value.emittable is EmittableCheckBox }

    @Test
    fun `the head names the planner and counts what is listed, and has both buttons`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        provideComposable {
            PlannerWidgetContent(current(anEntry("Book the band", "2026-11-22"), anEntry("Rings", "2026-11-28"), anEntry("Venue", "2026-12-03", kind = Kind.PLAN_ITEM)))
        }

        onNode(hasTextEqualTo("Wedding planner")).assertExists()
        onNode(hasTextEqualTo("3 things")).assertExists()
        onNode(hasTextEqualTo("Add a task") and opens(OpenAt.ADD_TASK)).assertExists()
        onNode(hasTextEqualTo("Add a step") and opens(OpenAt.ADD_STEP)).assertExists()
    }

    @Test
    fun `each row has its name over its day, and a press on the words opens that entry`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        provideComposable {
            PlannerWidgetContent(
                current(
                    anEntry("Book the band", "2026-11-22", id = "t1"),
                    anEntry("Pay the deposit", "2026-11-28", amount = 250000, id = "t2"),
                    anEntry("Venue", "2026-12-03", kind = Kind.PLAN_ITEM, id = "s1"),
                ),
            )
        }

        onNode(hasTextEqualTo("Book the band")).assertExists()
        onNode(hasTextEqualTo("overdue since 22 Nov")).assertExists()
        onNode(hasTextEqualTo("Pay the deposit")).assertExists()
        onNode(hasTextEqualTo("2,500.00 zł — today")).assertExists()
        onNode(hasTextEqualTo("Step — on 3 Dec")).assertExists()
        // The words are a press of their own: the task's form, the step's form.
        onAllNodes(hasAnyDescendant(hasTextEqualTo("Book the band")) and opens(OpenAt.task("t1"))).assertCountEquals(1)
        onAllNodes(hasAnyDescendant(hasTextEqualTo("Pay the deposit")) and opens(OpenAt.task("t2"))).assertCountEquals(1)
        onAllNodes(hasAnyDescendant(hasTextEqualTo("Venue")) and opens(OpenAt.step("s1"))).assertCountEquals(1)
    }

    @Test
    fun `a task has a box that ticks that task, a payment's says paid, and a step has none`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        provideComposable {
            PlannerWidgetContent(
                current(
                    anEntry("Book the band", "2026-11-22", id = "t1"),
                    anEntry("Pay the deposit", "2026-11-28", amount = 250000, id = "t2"),
                    anEntry("Venue", "2026-12-03", kind = Kind.PLAN_ITEM, id = "s1"),
                ),
            )
        }

        onNode(hasContentDescriptionEqualTo("Done: Book the band") and ticks("t1")).assertExists()
        onNode(hasContentDescriptionEqualTo("Paid: Pay the deposit") and ticks("t2")).assertExists()
        onNode(hasContentDescriptionEqualTo("Done: Venue")).assertDoesNotExist()
        onNode(hasContentDescriptionEqualTo("Paid: Venue")).assertDoesNotExist()
        onNode(ticks("s1")).assertDoesNotExist()
        onAllNodes(anyBox()).assertCountEquals(2)
    }

    @Test
    fun `nothing current says so, and the buttons stay`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        provideComposable { PlannerWidgetContent(current(anEntry("Far off", "2027-06-01"), anEntry("Done", "2026-11-28", status = Status.DONE))) }

        onNode(hasTextEqualTo("Nothing current")).assertExists()
        onNode(hasTextEqualTo("Nothing is current. What is overdue, due within 14 days or in progress shows here.")).assertExists()
        onNode(hasTextEqualTo("Add a task") and opens(OpenAt.ADD_TASK)).assertExists()
        onNode(hasTextEqualTo("Add a step") and opens(OpenAt.ADD_STEP)).assertExists()
        onAllNodes(anyBox()).assertCountEquals(0)
    }

    @Test
    fun `nobody signed in shows one sentence, no buttons and nothing of a plan, and a press opens the app`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        provideComposable { PlannerWidgetContent(WidgetModel.SignedOut(resources.getString(io.github.trufelek124.weddingplanner.R.string.widget_signed_out))) }

        onNode(hasTextEqualTo("Open the planner to sign in.")).assertExists()
        onNode(hasTextEqualTo("Add a task")).assertDoesNotExist()
        onNode(hasTextEqualTo("Add a step")).assertDoesNotExist()
        onNode(hasTextEqualTo("Wedding planner")).assertDoesNotExist()
        onAllNodes(anyBox()).assertCountEquals(0)
        onNode(opens(null)).assertExists()
    }

    @Test
    fun `a phone that holds no copy yet says so in one sentence, and a press opens the app`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        provideComposable { PlannerWidgetContent(WidgetModel.NoCopy(resources.getString(io.github.trufelek124.weddingplanner.R.string.widget_no_copy))) }

        onNode(hasTextEqualTo("Open the planner to load your plan.")).assertExists()
        onNode(hasTextEqualTo("Add a task")).assertDoesNotExist()
        onNode(opens(null)).assertExists()
    }
}
