package io.github.trufelek124.weddingplanner.planner

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.trufelek124.weddingplanner.R
import io.github.trufelek124.weddingplanner.data.Load
import io.github.trufelek124.weddingplanner.data.Planner
import io.github.trufelek124.weddingplanner.ui.QuietButton
import java.time.Clock

/** The three screens a signed-in person moves between. */
private enum class Place(val route: String, val title: Int, val label: Int, val icon: Int) {
    HOME("home", R.string.app_name, R.string.nav_home, R.drawable.ic_home),
    PLAN("plan", R.string.nav_plan, R.string.nav_plan, R.drawable.ic_plan),
    TASKS("tasks", R.string.nav_tasks, R.string.nav_tasks, R.drawable.ic_tasks),
}

/**
 * Everything a signed-in person sees. While the phone holds nothing of the planner it says why;
 * once it does, Home, the plan and the tasks show it, whether or not the server can be reached.
 *
 * @param clock the phone's clock: for today's date and for saying when something was loaded
 */
@Composable
fun PlannerScreens(ui: PlannerUi, userName: String, clock: Clock, onLoad: () -> Unit, onSignOut: () -> Unit) {
    val planner = ui.planner
    if (planner == null) {
        NothingYet(ui, userName, onLoad, onSignOut)
    } else {
        Places(planner, ui, userName, clock, onLoad, onSignOut)
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
            SignedInAs(userName, onSignOut)
        }
    }
}

@Composable
private fun SignedInAs(userName: String, onSignOut: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.signed_in_as, userName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        QuietButton(text = stringResource(R.string.sign_out), onClick = onSignOut)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Places(planner: Planner, ui: PlannerUi, userName: String, clock: Clock, onLoad: () -> Unit, onSignOut: () -> Unit) {
    val navigation = rememberNavController()
    val current by navigation.currentBackStackEntryAsState()
    val here = Place.entries.firstOrNull { it.route == current?.destination?.route } ?: Place.HOME
    val loadedOn = moment(planner.loadedAt, clock.zone)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(here.title), modifier = Modifier.semantics { heading() }) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    // Pulling the list down does the same; this is for whoever cannot pull.
                    IconButton(onClick = onLoad, enabled = !ui.loading) {
                        Icon(painterResource(R.drawable.ic_load_again), contentDescription = stringResource(R.string.load_again))
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                for (place in Place.entries) {
                    NavigationBarItem(
                        selected = place == here,
                        onClick = {
                            navigation.navigate(place.route) {
                                // One of each screen, and Back from any of them leads to Home.
                                popUpTo(navigation.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(painterResource(place.icon), contentDescription = null) },
                        label = { Text(stringResource(place.label)) },
                    )
                }
            }
        },
    ) { inner ->
        Column(modifier = Modifier.padding(inner).fillMaxSize()) {
            val trouble = ui.trouble
            if (trouble != null && !ui.loading) {
                Notice(stringResource(if (trouble == Load.UNREACHABLE) R.string.notice_no_connection else R.string.notice_not_loaded, loadedOn))
            }
            PullToRefreshBox(isRefreshing = ui.loading, onRefresh = onLoad, modifier = Modifier.weight(1f)) {
                val loaded: @Composable () -> Unit = { LoadedOn(loadedOn) }
                val today = todayAt(planner, clock.instant())
                NavHost(navController = navigation, startDestination = Place.HOME.route) {
                    composable(Place.HOME.route) {
                        HomeContent(planner, today = today) {
                            Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                loaded()
                                SignedInAs(userName, onSignOut)
                            }
                        }
                    }
                    composable(Place.PLAN.route) { PlanContent(planner, today = today, foot = loaded) }
                    composable(Place.TASKS.route) { TasksContent(planner, foot = loaded) }
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
private fun Notice(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.medium)
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
            .padding(12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun LoadedOn(moment: String) {
    Text(
        text = stringResource(R.string.loaded_on, moment),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp),
    )
}
