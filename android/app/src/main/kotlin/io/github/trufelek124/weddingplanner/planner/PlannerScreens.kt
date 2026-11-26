package io.github.trufelek124.weddingplanner.planner

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.trufelek124.weddingplanner.R
import io.github.trufelek124.weddingplanner.data.Decision
import io.github.trufelek124.weddingplanner.data.Entry
import io.github.trufelek124.weddingplanner.data.Gone
import io.github.trufelek124.weddingplanner.data.GuestStatus
import io.github.trufelek124.weddingplanner.data.GuestTrouble
import io.github.trufelek124.weddingplanner.data.SuggestionTrouble
import io.github.trufelek124.weddingplanner.data.Kind
import io.github.trufelek124.weddingplanner.data.Load
import io.github.trufelek124.weddingplanner.data.Planner
import io.github.trufelek124.weddingplanner.data.TaskFields
import io.github.trufelek124.weddingplanner.data.WaitingShare
import io.github.trufelek124.weddingplanner.ui.QuietButton
import java.time.Clock

/** The places a signed-in person moves between, each with its own place in the bar below. */
private enum class Place(val route: String, val title: Int, val label: Int, val icon: Int) {
    HOME("home", R.string.app_name, R.string.nav_home, R.drawable.ic_home),
    PLAN("plan", R.string.nav_plan, R.string.nav_plan, R.drawable.ic_plan),
    TASKS("tasks", R.string.nav_tasks, R.string.nav_tasks, R.drawable.ic_tasks),
    MORE("more", R.string.nav_more, R.string.nav_more, R.drawable.ic_more),
}

/**
 * A screen behind More: reached from its list (the budget also from Home's glance at the money), with
 * a back arrow in the top bar, and More stays the place that is selected. Pressing More on it leads to the list.
 *
 * @param title what the top bar says; of a screen named by what it shows (a vendor, a guest), what it says
 *   where that is gone
 * @param nameOf the name of what the screen shows, from the id in its address; null where the screen has a title of its own
 */
private class BehindMore(val route: String, @StringRes val title: Int, val nameOf: ((Planner, String) -> String?)? = null)

private const val BUDGET = "budget"
private const val VENDORS = "vendors"

/** One vendor: "vendor?id=…". */
private const val VENDOR = "vendor"
private const val VENDOR_ROUTE = "$VENDOR?id={id}"

private const val GUESTS = "guests"

/** One guest: "guest?id=…". */
private const val GUEST = "guest"
private const val GUEST_ROUTE = "$GUEST?id={id}"

private const val INSPIRATIONS = "inspirations"

/** One idea: "inspiration?id=…". */
private const val INSPIRATION = "inspiration"
private const val INSPIRATION_ROUTE = "$INSPIRATION?id={id}"

/** The inbox; "suggestions?part=added" comes to the part of what was added by itself. */
private const val SUGGESTIONS = "suggestions"
private const val SUGGESTIONS_ROUTE = "$SUGGESTIONS?part={part}"
private const val ADDED_PART_ARGUMENT = "added"

/** Where a task or a step came from: "source?id=…", the id of the suggestion. */
private const val SOURCE = "source"
private const val SOURCE_ROUTE = "$SOURCE?id={id}"

private val BEHIND_MORE = listOf(
    BehindMore(BUDGET, R.string.budget_title),
    BehindMore(VENDORS, R.string.vendors_title),
    BehindMore(VENDOR_ROUTE, R.string.vendor_title) { planner, id -> planner.vendor(id)?.name },
    BehindMore(GUESTS, R.string.guests_title),
    BehindMore(GUEST_ROUTE, R.string.guest_title) { planner, id -> planner.guest(id)?.name },
    BehindMore(INSPIRATIONS, R.string.inspirations_title),
    BehindMore(INSPIRATION_ROUTE, R.string.inspiration_title) { planner, id -> planner.inspiration(id)?.let(::inspirationName) },
    BehindMore(SUGGESTIONS_ROUTE, R.string.suggestions_title),
    BehindMore(SOURCE_ROUTE, R.string.source_title) { planner, id -> planner.suggestion(id)?.let(::suggestionName) },
)

/** Where a task is added or changed: "task?id=…" for an existing one, "task?parent=…" for a new one under a step. */
private const val TASK = "task"
private const val TASK_ROUTE = "$TASK?id={id}&parent={parent}"

/** Where a step of the plan is added or changed: "step?id=…" for an existing one, "step" for a new one. */
private const val STEP = "step"
private const val STEP_ROUTE = "$STEP?id={id}"

/** What a person can change, handed down from whoever keeps the planner. */
class Changes(
    val setStatus: (Entry, Boolean) -> Unit,
    val add: (TaskFields, Kind) -> Unit,
    val edit: (taskId: String, TaskFields) -> Unit,
    val letGo: (taskId: String) -> Unit,
    val letGoOfShare: (shareId: String) -> Unit = {},
    val setGuestStatus: (guestId: String, GuestStatus) -> Unit = { _, _ -> },
    val letGoOfGuestChange: (guestId: String) -> Unit = {},
    val decideSuggestion: (suggestionId: String, Decision) -> Unit = { _, _ -> },
    val takeBackSuggestionDecision: (suggestionId: String) -> Unit = {},
    val letGoOfSuggestionDecision: (suggestionId: String) -> Unit = {},
)

/**
 * Everything a signed-in person sees. While the phone holds nothing of the planner it says why;
 * once it does, Home, the plan and the tasks show it, whether or not the server can be reached.
 *
 * @param clock the phone's clock: for today's date and for saying when something was loaded
 */
@Composable
fun PlannerScreens(
    ui: PlannerUi,
    userName: String,
    clock: Clock,
    changes: Changes,
    onLoad: () -> Unit,
    onSignOut: () -> Unit,
    pictures: PicturesModel? = null,
) {
    val planner = ui.planner
    if (planner == null) {
        NothingYet(ui, userName, onLoad, onSignOut)
    } else {
        Places(planner, ui, userName, clock, changes, onLoad, onSignOut, pictures)
    }
}

/** The phone holds nothing to show: it is loading for the first time, or it cannot. */
@Composable
private fun NothingYet(ui: PlannerUi, userName: String, onLoad: () -> Unit, onSignOut: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.semantics { heading() },
            )
            val trouble = ui.trouble
            if (!ui.read || ui.loading || trouble == null) {
                Text(stringResource(R.string.wedding_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(
                    text = stringResource(
                        when (trouble) {
                            Load.NOT_SET_UP -> R.string.wedding_not_set_up
                            Load.NOT_A_MEMBER -> R.string.wedding_full
                            Load.UNREACHABLE -> R.string.wedding_unreachable
                            else -> R.string.wedding_failed
                        },
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (trouble != Load.NOT_A_MEMBER) {
                    QuietButton(text = stringResource(R.string.try_again), onClick = onLoad)
                }
            }
            SignedInAs(userName, unsent = 0, onSignOut = onSignOut)
        }
    }
}

/**
 * Who is signed in, and the way out. Signing out removes everything from the phone, also what was
 * changed here and not sent yet: when there is such a thing, the person is asked first.
 */
@Composable
private fun SignedInAs(userName: String, unsent: Int, onSignOut: () -> Unit) {
    var asking by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.signed_in_as, userName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        QuietButton(text = stringResource(R.string.sign_out), onClick = { if (unsent > 0) asking = true else onSignOut() })
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.sign_out_ask)) },
            text = { Text(pluralStringResource(R.plurals.sign_out_loses, unsent, unsent)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        asking = false
                        onSignOut()
                    },
                ) { Text(stringResource(R.string.sign_out_anyway)) }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(R.string.sign_out_stay)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Places(
    planner: Planner,
    ui: PlannerUi,
    userName: String,
    clock: Clock,
    changes: Changes,
    onLoad: () -> Unit,
    onSignOut: () -> Unit,
    pictures: PicturesModel?,
) {
    val navigation = rememberNavController()
    val current by navigation.currentBackStackEntryAsState()
    val route = current?.destination?.route
    val behind = BEHIND_MORE.firstOrNull { it.route == route }
    val here = if (behind != null) Place.MORE else Place.entries.firstOrNull { it.route == route } ?: Place.HOME
    val onTheStepForm = route == STEP_ROUTE
    val onTheForm = route == TASK_ROUTE || onTheStepForm
    val changingAnEntry = onTheForm && current?.arguments?.getString("id") != null
    val loadedOn = moment(planner.loadedAt, clock.zone)
    val actions = TaskActions(
        tick = changes.setStatus,
        open = { entry -> navigation.navigate(if (entry.kind == Kind.PLAN_ITEM) "$STEP?id=${entry.id}" else "$TASK?id=${entry.id}") },
        add = { parentId -> navigation.navigate(if (parentId == null) TASK else "$TASK?parent=$parentId") },
        source = { suggestionId -> navigation.navigate("$SOURCE?id=${Uri.encode(suggestionId)}") },
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    val title = when {
                        onTheStepForm -> stringResource(if (changingAnEntry) R.string.step_change else R.string.step_add)
                        onTheForm -> stringResource(if (changingAnEntry) R.string.task_change else R.string.task_add)
                        // One vendor or guest is named by its name; one that is gone has none.
                        behind != null ->
                            current?.arguments?.getString("id")?.let { id -> behind.nameOf?.invoke(planner, id) }
                                ?: stringResource(behind.title)
                        else -> stringResource(here.title)
                    }
                    Text(title, modifier = Modifier.semantics { heading() })
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    if (onTheForm || behind != null) {
                        IconButton(onClick = { navigation.popBackStack() }) {
                            Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back))
                        }
                    }
                },
                actions = {
                    if (!onTheForm) {
                        // Pulling the list down does the same; this is for whoever cannot pull.
                        IconButton(onClick = onLoad, enabled = !ui.loading) {
                            Icon(painterResource(R.drawable.ic_load_again), contentDescription = stringResource(R.string.load_again))
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (!onTheForm) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    for (place in Place.entries) {
                        NavigationBarItem(
                            selected = place == here,
                            onClick = {
                                // A screen behind More: pressing More there leads back to the list, not to the screen again.
                                val leavingIt = behind != null && place == here
                                // Home is where everything starts: what was opened from it (the budget from its glance,
                                // the suggestions from its line) is kept by the navigation as "what stood above Home" and
                                // would be put straight back. Pressing Home means Home itself.
                                val toHome = place == Place.HOME
                                navigation.navigate(place.route) {
                                    // One of each screen, and Back from any of them leads to Home.
                                    popUpTo(navigation.graph.findStartDestination().id) { saveState = !leavingIt }
                                    launchSingleTop = true
                                    restoreState = !leavingIt && !toHome
                                }
                            },
                            icon = { Icon(painterResource(place.icon), contentDescription = null) },
                            label = { Text(stringResource(place.label)) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if ((here == Place.TASKS || here == Place.PLAN) && !onTheForm) {
                // Its words are always there: the ready-made button with a label brings them in with an animation,
                // and until that has run a screen reader finds a button without a name.
                FloatingActionButton(
                    onClick = { if (here == Place.PLAN) navigation.navigate(STEP) else actions.add(null) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_add), contentDescription = null)
                        Text(stringResource(if (here == Place.PLAN) R.string.step_add else R.string.task_add), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        },
    ) { inner ->
        Column(modifier = Modifier.padding(inner).fillMaxSize()) {
            if (!onTheForm) {
                val trouble = ui.trouble
                if (trouble != null && !ui.loading) {
                    val why = stringResource(if (trouble == Load.UNREACHABLE) R.string.notice_no_connection else R.string.notice_not_loaded, loadedOn)
                    val waiting = planner.waiting.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.changes_waiting, it, it) }
                    val shared = planner.shares.count { it.trouble == null }.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.shares_waiting, it, it) }
                    Notice(listOfNotNull(why, waiting, shared).joinToString(" "))
                }
                for (gone in planner.gone) {
                    NoLongerThere(gone, onOk = { changes.letGo(gone.entryId) })
                }
                for (share in planner.shares.filter { it.trouble != null }) {
                    NotSaved(share, onDiscard = { changes.letGoOfShare(share.id) })
                }
                if (route == SUGGESTIONS_ROUTE || route == SOURCE_ROUTE) {
                    for (trouble in planner.suggestionTroubles) {
                        SuggestionDecisionNotSent(trouble, onLetGo = { changes.letGoOfSuggestionDecision(trouble.suggestionId) })
                    }
                }
                if (route == GUESTS || route == GUEST_ROUTE) {
                    for (trouble in planner.guestTroubles) {
                        GuestChangeNotSent(trouble, onLetGo = { changes.letGoOfGuestChange(trouble.guestId) })
                    }
                }
            }
            PullToRefreshBox(isRefreshing = ui.loading && !onTheForm, onRefresh = onLoad, modifier = Modifier.weight(1f)) {
                val loaded: @Composable () -> Unit = { LoadedOn(loadedOn) }
                // On the tasks a button floats over the end of the list: room for the last line to come out from under it.
                val loadedUnderTheButton: @Composable () -> Unit = { LoadedOn(loadedOn, Modifier.padding(bottom = 72.dp)) }
                val today = todayAt(planner, clock.instant())
                NavHost(navController = navigation, startDestination = Place.HOME.route) {
                    composable(Place.HOME.route) {
                        HomeContent(planner, today = today, actions = actions, onBudget = { navigation.navigate(BUDGET) }, onSuggestions = { navigation.navigate(SUGGESTIONS) }, onAdded = { navigation.navigate("$SUGGESTIONS?part=$ADDED_PART_ARGUMENT") }) {
                            Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                loaded()
                                SignedInAs(userName, unsent = planner.unsent, onSignOut = onSignOut)
                            }
                        }
                    }
                    composable(Place.PLAN.route) { PlanContent(planner, today = today, actions = actions, foot = loadedUnderTheButton) }
                    composable(Place.TASKS.route) { TasksContent(planner, actions = actions, foot = loadedUnderTheButton) }
                    composable(Place.MORE.route) {
                        MoreContent(planner, onBudget = { navigation.navigate(BUDGET) }, onVendors = { navigation.navigate(VENDORS) }, onGuests = { navigation.navigate(GUESTS) }, onInspirations = { navigation.navigate(INSPIRATIONS) }, onSuggestions = { navigation.navigate(SUGGESTIONS) }) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                loaded()
                                SignedInAs(userName, unsent = planner.unsent, onSignOut = onSignOut)
                            }
                        }
                    }
                    composable(BUDGET) { BudgetContent(planner, foot = loaded) }
                    composable(
                        route = SUGGESTIONS_ROUTE,
                        arguments = listOf(
                            navArgument("part") {
                                type = NavType.StringType
                                nullable = true
                            },
                        ),
                    ) { entry ->
                        SuggestionsContent(
                            planner, zone = clock.zone, foot = loaded,
                            scrollToAdded = entry.arguments?.getString("part") == ADDED_PART_ARGUMENT,
                            actions = { suggestion ->
                                SuggestionActions(
                                    suggestion, notTakenBack = suggestion.id in ui.notTakenBack,
                                    onDecide = { changes.decideSuggestion(suggestion.id, it) },
                                    onTakeBack = { changes.takeBackSuggestionDecision(suggestion.id) },
                                )
                            },
                        )
                    }
                    composable(
                        route = SOURCE_ROUTE,
                        arguments = listOf(navArgument("id") { type = NavType.StringType }),
                    ) { entry ->
                        val suggestion = entry.arguments?.getString("id")?.let(planner::suggestion)
                        if (suggestion == null) {
                            // Undone or decided since, and loaded again: nothing of the mail is held any more.
                            SuggestionSourceGone(onBack = { navigation.popBackStack() })
                        } else {
                            SuggestionSourceContent(
                                planner, suggestion, clock.zone, foot = loaded,
                                actions = { shown ->
                                    SuggestionActions(
                                        shown, notTakenBack = shown.id in ui.notTakenBack,
                                        onDecide = { changes.decideSuggestion(shown.id, it) },
                                        onTakeBack = { changes.takeBackSuggestionDecision(shown.id) },
                                    )
                                },
                            )
                        }
                    }
                    composable(VENDORS) {
                        VendorsContent(planner, onOpen = { navigation.navigate("$VENDOR?id=${Uri.encode(it.id)}") }, foot = loaded)
                    }
                    composable(
                        route = VENDOR_ROUTE,
                        arguments = listOf(navArgument("id") { type = NavType.StringType }),
                    ) { entry ->
                        val vendor = entry.arguments?.getString("id")?.let(planner::vendor)
                        if (vendor == null) {
                            // Removed in the web app and loaded again since.
                            VendorGone(onBack = { navigation.popBackStack() })
                        } else {
                            VendorContent(planner, vendor, actions = actions, foot = loaded)
                        }
                    }
                    composable(GUESTS) {
                        GuestsContent(planner, onOpen = { navigation.navigate("$GUEST?id=${Uri.encode(it.id)}") }, foot = loaded)
                    }
                    composable(
                        route = GUEST_ROUTE,
                        arguments = listOf(navArgument("id") { type = NavType.StringType }),
                    ) { entry ->
                        val guest = entry.arguments?.getString("id")?.let(planner::guest)
                        if (guest == null) {
                            // Removed in the web app and loaded again since.
                            GuestGone(onBack = { navigation.popBackStack() })
                        } else {
                            GuestContent(planner, guest, onChoose = { changes.setGuestStatus(guest.id, it) }, foot = loaded)
                        }
                    }
                    composable(INSPIRATIONS) {
                        if (pictures == null) {
                            InspirationsContent(planner, onOpen = { navigation.navigate("$INSPIRATION?id=${Uri.encode(it.id)}") }, foot = loaded)
                        } else {
                            InspirationsContent(
                                planner, onOpen = { navigation.navigate("$INSPIRATION?id=${Uri.encode(it.id)}") },
                                face = { PictureFace(it, pictures) }, shareFace = { SharePictureFace(it, pictures) }, foot = loaded,
                            )
                        }
                    }
                    composable(
                        route = INSPIRATION_ROUTE,
                        arguments = listOf(navArgument("id") { type = NavType.StringType }),
                    ) { entry ->
                        val idea = entry.arguments?.getString("id")?.let(planner::inspiration)
                        if (idea == null) {
                            // Removed in the web app and loaded again since.
                            InspirationGone(onBack = { navigation.popBackStack() })
                        } else {
                            InspirationContent(
                                planner, idea, onVendor = { navigation.navigate("$VENDOR?id=${Uri.encode(it.id)}") },
                                pictureItems = pictures?.let { shown -> { one -> pictureItems(one, shown) } }, foot = loaded,
                            )
                        }
                    }
                    composable(
                        route = TASK_ROUTE,
                        arguments = listOf(
                            navArgument("id") {
                                type = NavType.StringType
                                nullable = true
                            },
                            navArgument("parent") {
                                type = NavType.StringType
                                nullable = true
                            },
                        ),
                    ) { entry ->
                        val id = entry.arguments?.getString("id")
                        val parent = entry.arguments?.getString("parent")
                        val task = id?.let(planner::entry)?.takeIf { it.kind == Kind.TASK }
                        if (id != null && task == null) {
                            // Deleted elsewhere while the form was open, or on its way here.
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(stringResource(R.string.task_not_there), style = MaterialTheme.typography.bodyLarge)
                                QuietButton(stringResource(R.string.back), onClick = { navigation.popBackStack() })
                            }
                        } else {
                            TaskForm(
                                planner = planner,
                                kind = Kind.TASK,
                                task = task,
                                parentId = parent,
                                today = today,
                                onSave = { fields ->
                                    if (task == null) changes.add(fields, Kind.TASK) else changes.edit(task.id, fields)
                                    navigation.popBackStack()
                                },
                                // Only the form that "Add a task to this" opens: one task after the other for the same step.
                                onSaveAndAddAnother = if (task == null && parent != null) { fields -> changes.add(fields, Kind.TASK) } else null,
                                onCancel = { navigation.popBackStack() },
                                onLetGo = {
                                    task?.let { changes.letGo(it.id) }
                                    navigation.popBackStack()
                                },
                                onSource = actions.source,
                            )
                        }
                    }
                    composable(
                        route = STEP_ROUTE,
                        arguments = listOf(
                            navArgument("id") {
                                type = NavType.StringType
                                nullable = true
                            },
                        ),
                    ) { entry ->
                        val id = entry.arguments?.getString("id")
                        val step = id?.let(planner::entry)?.takeIf { it.kind == Kind.PLAN_ITEM }
                        if (id != null && step == null) {
                            // Deleted elsewhere while the form was open, or on its way here.
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(stringResource(R.string.step_not_there), style = MaterialTheme.typography.bodyLarge)
                                QuietButton(stringResource(R.string.back), onClick = { navigation.popBackStack() })
                            }
                        } else {
                            TaskForm(
                                planner = planner,
                                kind = Kind.PLAN_ITEM,
                                task = step,
                                parentId = null,
                                today = today,
                                onSave = { fields ->
                                    if (step == null) changes.add(fields, Kind.PLAN_ITEM) else changes.edit(step.id, fields)
                                    navigation.popBackStack()
                                },
                                onCancel = { navigation.popBackStack() },
                                onLetGo = {
                                    step?.let { changes.letGo(it.id) }
                                    navigation.popBackStack()
                                },
                                onSource = actions.source,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Said when what is on the screen is not the latest: why, and from when it is. Being without a
 * connection is no fault of anyone's, so this does not look like an error.
 */
@Composable
private fun Notice(text: String, action: @Composable () -> Unit = {}) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.medium)
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
            .padding(12.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        action()
    }
}

/** A change whose task was deleted on the server meanwhile: it is named, and can only be let go of. */
@Composable
private fun NoLongerThere(gone: Gone, onOk: () -> Unit) {
    Notice(stringResource(if (gone.step) R.string.change_gone_step else R.string.change_gone, gone.title)) {
        QuietButton(stringResource(R.string.change_gone_ok), onClick = onOk)
    }
}

/** A status chosen for a guest that the server could not take: the guest is named, and the choice can only be let go of. */
@Composable
private fun GuestChangeNotSent(trouble: GuestTrouble, onLetGo: () -> Unit) {
    Notice(stringResource(if (trouble.gone) R.string.guest_change_gone else R.string.guest_change_refused, trouble.name)) {
        QuietButton(stringResource(if (trouble.gone) R.string.change_gone_ok else R.string.guest_change_let_go), onClick = onLetGo)
    }
}

/** A decision made for a suggestion that could not be sent: the suggestion is named, and the decision can only be let go of. */
@Composable
private fun SuggestionDecisionNotSent(trouble: SuggestionTrouble, onLetGo: () -> Unit) {
    Notice(suggestionTroubleInWords(trouble)) {
        QuietButton(suggestionTroubleButton(trouble), onClick = onLetGo)
    }
}

/** Something shared to the planner that the server did not take: it says what and why, and can be discarded. */
@Composable
private fun NotSaved(share: WaitingShare, onDiscard: () -> Unit) {
    val why = share.trouble.orEmpty()
    val text = when {
        share.link != null -> stringResource(R.string.share_refused_link, share.link, why)
        share.pictures > 0 -> pluralStringResource(R.plurals.share_refused_pictures, share.pictures, share.pictures, why)
        else -> stringResource(R.string.share_refused_note, why)
    }
    Notice(text) {
        QuietButton(stringResource(R.string.share_discard), onClick = onDiscard)
    }
}

@Composable
private fun LoadedOn(moment: String, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.loaded_on, moment),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 16.dp),
    )
}
