@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.kevin.legion.ui.apps

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.os.Build
import android.os.UserHandle
import android.os.UserManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.soft.SoftTheme

private const val SEARCH_COLUMNS = 4

/**
 * The app drawer (ADR 0050, 2026-09-27). LEGION may now be the phone's home app, and a home app
 * that can't open other apps strands the user, so this lists every launchable app on the phone,
 * work profile included, with search.
 *
 * Reads through `LauncherApps` rather than `PackageManager.queryIntentActivities` because only
 * LauncherApps can see across profiles: the work profile's Outlook and Authenticator are a
 * different Android user, invisible to a plain package query.
 *
 * **Work apps can be paused and resumed from here.** A default home app is allowed to call
 * `UserManager.requestQuietModeEnabled`. That's the thing Kevin asked for on 2026-09-27 that a
 * shell could not do, because Intune refuses `am stop-user` on a managed profile.
 */
@Composable
fun AppsScreen() {
    val context = LocalContext.current
    var reload by remember { mutableIntStateOf(0) }
    // Cached: the last list shows instantly, then a cheap refresh brings it up to date.
    var loaded by remember { mutableStateOf(AppDrawerCache.peek()) }
    var query by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    // Home-launcher ticket 06: pin/unpin from here, re-read after every change so the "Pin to
    // home"/"Unpin from home" menu label always matches the dock's own current state.
    var pins by remember { mutableStateOf(DockPinsStore.read(context)) }
    var pinMenuApp by remember { mutableStateOf<DrawerApp?>(null) }
    // Ticket 07: the letter folder that is open as a dialog, if any.
    var openFolder by remember { mutableStateOf<LetterFolder?>(null) }

    LaunchedEffect(reload) {
        loaded = AppDrawerCache.refresh(context)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text("APPS", style = LegionType.stamp, color = MaterialTheme.colorScheme.onBackground)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search apps") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )

        val state = loaded
        val work = state?.workProfile
        if (work != null) {
            val paused = state.workPaused
            Text(
                if (paused) "WORK APPS ARE PAUSED - TAP TO TURN ON" else "PAUSE WORK APPS",
                style = LegionType.stamp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable {
                        message = setWorkPaused(context, work, pause = !paused)
                        reload++
                    }
                    .padding(vertical = 12.dp),
            )
        }
        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        when {
            state == null -> Text("Loading apps...", style = MaterialTheme.typography.bodyMedium)
            state.apps.isEmpty() ->
                // Never an empty list that reads as "you have no apps". If LauncherApps returned
                // nothing, the app couldn't read them, and it says so.
                Text(
                    "Couldn't read the installed apps. Nothing is listed because nothing was readable, not because nothing is installed.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            else -> {
                val searching = query.isNotBlank()
                val rows = if (searching) filterDrawer(state.apps, query) else emptyList()
                // Soft look for the new grid (ticket 07): the rest of this screen keeps its own
                // theme until it is converted, so only the folders and dialog are wrapped.
                SoftTheme {
                    if (searching) {
                        if (rows.isEmpty()) {
                            Text("No app matches \"${query.trim()}\".", style = MaterialTheme.typography.bodyMedium)
                        }
                        AppIconGrid(
                            apps = rows,
                            icons = state.icons,
                            workPaused = state.workPaused,
                            columns = SEARCH_COLUMNS,
                            onOpen = { app -> message = launchDrawerApp(context, app, state) },
                            onLongPress = { app -> pinMenuApp = app },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        FolderGrid(
                            folders = remember(state.apps) { letterFolders(state.apps) },
                            icons = state.icons,
                            onOpenFolder = { openFolder = it },
                        )
                    }
                    openFolder?.let { folder ->
                        FolderDialog(
                            folder = folder,
                            icons = state.icons,
                            workPaused = state.workPaused,
                            onDismiss = { openFolder = null },
                            onOpen = { app ->
                                openFolder = null
                                message = launchDrawerApp(context, app, state)
                            },
                            onLongPress = { app -> pinMenuApp = app },
                        )
                    }
                }
            }
        }
    }

    pinMenuApp?.let { app ->
        PinMenuSheet(
            app = app,
            pinned = DockPin(app.packageName, app.profileKey) in pins,
            onDismiss = { pinMenuApp = null },
            onTogglePin = {
                val slot = DockPin(app.packageName, app.profileKey)
                message = if (slot in pins) {
                    pins = DockPins.unpin(pins, slot)
                    DockPinsStore.write(context, pins)
                    null
                } else {
                    when (val outcome = DockPins.pin(pins, slot)) {
                        is DockPins.PinOutcome.Ok -> {
                            pins = outcome.pins
                            DockPinsStore.write(context, pins)
                            null
                        }
                        is DockPins.PinOutcome.Full -> "Dock is full. Unpin one first."
                    }
                }
                pinMenuApp = null
            },
        )
    }
}

/** Long-press on a drawer row (ticket 06's own "Pin from the drawer") - one row, its label reading
 * "Pin to home" or "Unpin from home" depending on [pinned], so the sheet never shows both at once. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PinMenuSheet(app: DrawerApp, pinned: Boolean, onDismiss: () -> Unit, onTogglePin: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            app.label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        Row(
            Modifier.fillMaxWidth().height(52.dp).clickable(onClick = onTogglePin).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (pinned) "Unpin from home" else "Pin to home",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Opens [app], or returns a sentence saying why it didn't. Never silent: a tap that does nothing
 * reads as a broken screen. `internal` (not `private`) so [com.kevin.legion.ui.home.AppDock]'s own
 * launch callback is the SAME `LauncherApps` call and the same failure sentence, per ticket 06's
 * own "Tap launches it exactly the way the drawer does". */
internal fun launchDrawerApp(context: Context, app: DrawerApp, state: Loaded): String? {
    val handle = state.handles[app.profileKey] ?: return "Couldn't find the profile ${app.label} belongs to."
    if (app.isWork && state.workPaused) {
        return "Work apps are paused. Tap \"WORK APPS ARE PAUSED\" above to turn them on first."
    }
    return runCatching {
        context.getSystemService(LauncherApps::class.java)
            .startMainActivity(ComponentName(app.packageName, app.className), handle, null, null)
        null
    }.getOrElse { "Couldn't open ${app.label}: ${it.message ?: it.javaClass.simpleName}" }
}

/** Pauses or resumes the work profile, or returns why it couldn't. Android only lets the DEFAULT
 * home app do this, so it fails, and says so, when LEGION isn't set as the Home app. */
private fun setWorkPaused(context: Context, profile: UserHandle, pause: Boolean): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "Pausing work apps needs Android 11 or later."
    return runCatching {
        context.getSystemService(UserManager::class.java).requestQuietModeEnabled(pause, profile)
        null
    }.getOrElse {
        "Couldn't ${if (pause) "pause" else "turn on"} work apps. Android only lets the phone's " +
            "Home app do this - set LEGION as the Home app first. (${it.message ?: it.javaClass.simpleName})"
    }
}
