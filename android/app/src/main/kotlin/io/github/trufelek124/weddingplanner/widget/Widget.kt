package io.github.trufelek124.weddingplanner.widget

import android.content.res.Resources
import io.github.trufelek124.weddingplanner.R
import io.github.trufelek124.weddingplanner.data.Kind
import io.github.trufelek124.weddingplanner.data.Planner
import io.github.trufelek124.weddingplanner.data.PlannerRepository
import io.github.trufelek124.weddingplanner.data.SendLater
import io.github.trufelek124.weddingplanner.data.Status
import io.github.trufelek124.weddingplanner.planner.todayAt
import io.github.trufelek124.weddingplanner.session.Session
import io.github.trufelek124.weddingplanner.session.SessionState
import java.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * What the phone itself does for the widget (S12): drawing it anew. It comes into the container from outside, as the
 * network and the Keystore do, so that tests put a stand-in in its place and count what it was asked.
 */
fun interface WidgetDrawing {

    /** Draws the widget anew from what the phone holds now. Cheap to ask for, as often as the copy changes. */
    fun redraw()
}

/** What the widget shows at this moment, from the session and the phone's copy of the planner. */
class WidgetModels(
    private val session: Session,
    private val planner: PlannerRepository,
    private val clock: Clock,
    private val resources: Resources,
) {

    /** What the widget shows at this moment: the first value of [follow], and the one a session starts from. */
    suspend fun now(): WidgetModel {
        if (session.state.value is SessionState.Restoring) {
            session.restore()
        }
        return modelOf(session.state.value, planner.planner.first())
    }

    /**
     * What the widget shows, again whenever the session or the phone's copy changes (the copy is read again after every
     * write): a composition that keeps running follows it, because Glance does not start `provideGlance` again while one runs.
     */
    fun follow(): Flow<WidgetModel> =
        combine(session.state, planner.planner) { state, copy -> state to copy }
            .filter { (state, _) -> state !is SessionState.Restoring }
            .map { (state, copy) -> modelOf(state, copy) }

    private fun modelOf(state: SessionState, copy: Planner?): WidgetModel {
        if (state !is SessionState.SignedIn) {
            return WidgetModel.SignedOut(resources.getString(R.string.widget_signed_out))
        }
        copy ?: return WidgetModel.NoCopy(resources.getString(R.string.widget_no_copy))
        return currentModelOf(currentThings(copy, todayAt(copy, clock.instant())), resources)
    }
}

/**
 * What a press on the widget changes, which is one thing: a task ticked off. It goes the way the app's own tick goes: kept
 * on the phone, shown at once, and sent when there is a connection, also when the app is not open.
 */
class WidgetActions(
    private val session: Session,
    private val planner: PlannerRepository,
    private val sendLater: SendLater,
) {

    /**
     * Marks the task done — a payment paid. Nothing is done for a step, for what is not there or not open any more, or
     * while nobody is signed in.
     *
     * @return whether the tick was kept
     */
    suspend fun tick(entryId: String): Boolean {
        if (session.state.value is SessionState.Restoring) {
            session.restore()
        }
        if (session.state.value !is SessionState.SignedIn) {
            return false
        }
        val entry = planner.planner.first()?.entry(entryId)
        if (entry == null || entry.kind != Kind.TASK || !entry.status.open) {
            return false
        }
        planner.setStatus(entryId, Status.DONE)
        // Asked for before anything is sent: should the app not be open, the tick still goes out.
        sendLater.whenConnected()
        return true
    }
}
