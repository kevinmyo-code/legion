package com.kevin.legion.ui.apps

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.os.Build
import android.os.UserHandle
import android.os.UserManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.kevin.legion.ui.theme.LegionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    var loaded by remember { mutableStateOf<Loaded?>(null) }
    var query by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reload) {
        loaded = withContext(Dispatchers.IO) { load(context) }
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
                val rows = filterDrawer(state.apps, query)
                if (rows.isEmpty()) {
                    Text("No app matches \"${query.trim()}\".", style = MaterialTheme.typography.bodyMedium)
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(rows, key = { "${it.profileKey}/${it.packageName}/${it.className}" }) { app ->
                        AppRow(
                            app = app,
                            icon = state.icons["${app.profileKey}/${app.packageName}/${app.className}"],
                            paused = app.isWork && state.workPaused,
                            onOpen = { message = launch(context, app, state) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: DrawerApp, icon: ImageBitmap?, paused: Boolean, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        if (icon != null) Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(40.dp))
        else Spacer(Modifier.size(40.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(app.label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
            if (app.isWork) {
                Text(
                    if (paused) "WORK - PAUSED" else "WORK",
                    style = LegionType.stamp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private class Loaded(
    val apps: List<DrawerApp>,
    val icons: Map<String, ImageBitmap>,
    val handles: Map<Int, UserHandle>,
    val workProfile: UserHandle?,
    val workPaused: Boolean,
)

private fun load(context: Context): Loaded {
    val launcherApps = context.getSystemService(LauncherApps::class.java)
    val userManager = context.getSystemService(UserManager::class.java)
    val me = android.os.Process.myUserHandle()
    val apps = mutableListOf<DrawerApp>()
    val icons = mutableMapOf<String, ImageBitmap>()
    val handles = mutableMapOf<Int, UserHandle>()
    var work: UserHandle? = null
    val profiles = runCatching { launcherApps.profiles }.getOrDefault(listOf(me))
    for (profile in profiles) {
        val key = userManager.getSerialNumberForUser(profile).toInt()
        handles[key] = profile
        val isWork = profile != me
        if (isWork) work = profile
        val activities = runCatching { launcherApps.getActivityList(null, profile) }.getOrDefault(emptyList())
        for (info in activities) {
            val component = info.componentName
            if (!isWork && component.packageName == context.packageName) continue // already here
            val row = DrawerApp(info.label.toString(), component.packageName, component.className, isWork, key)
            apps += row
            runCatching { info.getBadgedIcon(0).toBitmap(96, 96).asImageBitmap() }.getOrNull()?.let {
                icons["$key/${component.packageName}/${component.className}"] = it
            }
        }
    }
    val paused = work?.let { runCatching { userManager.isQuietModeEnabled(it) }.getOrDefault(false) } ?: false
    return Loaded(apps, icons, handles, work, paused)
}

/** Opens [app], or returns a sentence saying why it didn't. Never silent: a tap that does nothing
 * reads as a broken screen. */
private fun launch(context: Context, app: DrawerApp, state: Loaded): String? {
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
