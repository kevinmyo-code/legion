package com.kevin.legion.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kevin.legion.ui.theme.LegionTheme

/**
 * Single-activity shell (ticket 07 resolution). Everything the app shows -
 * fleet, ledger, pantry, settings, and the sub-routes absorbed from the three
 * orphan activities - lives inside one [NavHost] here. There is no second
 * `<activity>` left in the manifest for any of that content; see the
 * deletions of `SavedPlacesActivity`, `LedgerImportActivity` and
 * `PantryImportActivity` (their composable content moved into [ui] screen
 * files, only the hosting changed) and `BootReceiver` (deleted outright -
 * ignition is a user toggle now, see [com.kevin.legion.service.AssistantIgnition]).
 *
 * **`LegionTheme`, not `MaterialTheme`** (resolution "specified, not asked").
 * The Instrument theme (ticket 02) was built and previously unused by the
 * only screen that existed.
 *
 * **No key wall** (resolution §3). The shell renders with all four tabs live
 * on a completely fresh install; a Gemini key is requested only at the
 * point of use (the assistant toggle, or a future LLM-fallback spend gate),
 * never as a gate on opening the app.
 */
class MainActivity : ComponentActivity() {
    // Held as Compose state, not read once in onCreate: MainActivity is
    // launchMode="singleTask" (kept for the Spotify OAuth redirect - see the
    // manifest comment), so a voice tool's second startActivity call while
    // this Activity is already on top delivers onNewIntent, not a fresh
    // onCreate. deepLinkNonce (not deepLinkRoute) is what LegionShell's
    // LaunchedEffect keys on, so a repeat "open my saved places" while the
    // app is already foregrounded re-navigates instead of being skipped as
    // an unchanged key - deepLinkRoute alone could arrive at the same value
    // twice in a row.
    private var deepLinkRoute by mutableStateOf<String?>(null)
    private var deepLinkNonce by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deepLinkRoute = intent?.getStringExtra(EXTRA_ROUTE)
        deepLinkNonce++
        setContent {
            LegionTheme {
                LegionShell(deepLinkRoute = deepLinkRoute, deepLinkNonce = deepLinkNonce)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinkRoute = intent.getStringExtra(EXTRA_ROUTE)
        deepLinkNonce++
    }

    companion object {
        /**
         * Intent extra a caller with no Compose nav graph of its own can set to
         * land the shell directly on a sub-route, e.g. [LegionRoute.FLEET_PLACES].
         * The only caller today is [com.kevin.legion.service.LiveSessionController]'s
         * `show_saved_places` / `import_statement` / `import_receipt` voice
         * tools - they used to `startActivity` the three orphan Activities this
         * ticket deleted; now they start this Activity with a route instead.
         */
        const val EXTRA_ROUTE = "route"
    }
}

@Composable
private fun LegionShell(deepLinkRoute: String? = null, deepLinkNonce: Int = 0) {
    val navController = rememberNavController()

    // Keyed on the nonce, not the route string, so a repeat deep link to the
    // same sub-route (onNewIntent while already on top) still re-navigates -
    // see MainActivity.deepLinkNonce's doc comment. Does nothing for the
    // ordinary launcher-icon path, where deepLinkRoute is null.
    LaunchedEffect(deepLinkNonce) {
        deepLinkRoute?.let { navController.navigate(it) }
    }

    Scaffold(
        bottomBar = { LegionBottomBar(navController) },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = LegionRoute.FLEET,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(LegionRoute.FLEET) {
                FleetScreen(onOpenPlaces = { navController.navigate(LegionRoute.FLEET_PLACES) })
            }
            composable(LegionRoute.FLEET_PLACES) {
                SavedPlacesScreen(onBack = { navController.popBackStack() })
            }

            composable(LegionRoute.LEDGER) {
                LedgerScreen(onOpenImport = { navController.navigate(LegionRoute.LEDGER_IMPORT) })
            }
            composable(LegionRoute.LEDGER_IMPORT) {
                LedgerImportScreen(onBack = { navController.popBackStack() })
            }

            composable(LegionRoute.PANTRY) {
                PantryScreen(onOpenImport = { navController.navigate(LegionRoute.PANTRY_IMPORT) })
            }
            composable(LegionRoute.PANTRY_IMPORT) {
                PantryImportScreen(onBack = { navController.popBackStack() })
            }

            composable(LegionRoute.SETTINGS) {
                SettingsScreen(onOpenKeyScreen = { navController.navigate(LegionRoute.SETTINGS_KEY) })
            }
            composable(LegionRoute.SETTINGS_KEY) {
                KeyScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

/**
 * Four top-level tabs. Tapping one collapses the back stack down to the
 * start destination and pushes the tapped tab on top - "standard
 * single-activity back stack" (resolution §5): back pops sub-routes within
 * the current tab, then lands on the start destination (Fleet), then exits.
 * Deliberately NOT the multi-back-stack-per-tab pattern (`saveState`/
 * `restoreState`) - that preserves scroll/nav position per tab across
 * switches, which is a real UX call ticket 07 does not make; it is not
 * needed to satisfy the resolution's back-stack wording.
 */
@Composable
private fun LegionBottomBar(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // The TAB the current route sits under, not the route itself - a
    // sub-route like `settings/key` keeps Settings lit. See
    // LegionRoute.topLevelOf.
    val selectedTab = LegionRoute.topLevelOf(currentRoute)

    NavigationBar {
        LegionRoute.TOP_LEVEL.forEach { route ->
            NavigationBarItem(
                selected = selectedTab == route,
                onClick = {
                    if (currentRoute != route) {
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = false
                            }
                            launchSingleTop = true
                        }
                    }
                },
                // No icon set chosen yet (ui/ is a clean slate - CLAUDE.md §6);
                // a letterform stands in rather than pulling in a Material
                // Icons dependency for a placeholder that ticket 08/09 will replace.
                icon = { Text(LegionRoute.label(route).take(1)) },
                label = { Text(LegionRoute.label(route)) },
            )
        }
    }
}
