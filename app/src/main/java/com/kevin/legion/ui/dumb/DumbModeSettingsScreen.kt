package com.kevin.legion.ui.dumb

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevin.legion.MidnightApplication
import com.kevin.legion.dumb.AndroidKeptAppResolver
import com.kevin.legion.dumb.DumbGrantRow
import com.kevin.legion.dumb.DumbSetup
import com.kevin.legion.dumb.GrantAction
import com.kevin.legion.dumb.rain.MatrixWallpaperService
import com.kevin.legion.dumb.KeptEntry
import com.kevin.legion.dumb.MapsChoice
import com.kevin.legion.ui.common.DeckScreenHeader
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics

/**
 * Dumb phone mode's setup (ADR 0062): the switch, every one-time grant with its state in words and
 * a button to the right system page, and the kept-apps list. Turning the mode on with a grant
 * missing still works; the screen says in words what is not active because of it.
 */
@Composable
fun DumbModeSettingsScreen(onBack: () -> Unit, onOpenHome: () -> Unit) {
    val context = LocalContext.current
    val sem = LocalLegionSemantics.current
    val controller = remember(context) { (context.applicationContext as MidnightApplication).dumbMode }
    val state by controller.state.collectAsStateWithLifecycle()
    var facts by remember { mutableStateOf(DumbSetup.factsFor(context)) }
    // The grants are made on system pages; re-read when the user comes back from one.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { facts = DumbSetup.factsFor(context) }
    val rows = DumbSetup.rows(facts)

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            DeckScreenHeader(title = "Matrix mode", onBack = onBack)
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "A short list instead of a phone. Other apps' notifications are held, strangers' calls " +
                        "are silent, and blocked apps send you home. Leaving takes a 60 second wait and a sentence.",
                    style = LegionType.stamp,
                    color = sem.faint,
                )
                ModeSwitchRow(
                    on = state.on,
                    onTurnOn = {
                        controller.turnOn()
                        onOpenHome()
                    },
                    onLeave = {
                        controller.requestLeave()
                        onOpenHome()
                    },
                )
                DumbSetup.notActive(rows)?.let { Text(it, style = LegionType.stamp, color = sem.estimated) }

                Spacer(Modifier.height(8.dp))
                Text("One-time grants", style = MaterialTheme.typography.labelLarge)
                rows.forEach { row -> GrantRowView(row) }

                Spacer(Modifier.height(8.dp))
                DumbPeopleSection()

                Spacer(Modifier.height(8.dp))
                MapsChoiceSection()

                Spacer(Modifier.height(8.dp))
                BlockedAppsSection()

                Spacer(Modifier.height(8.dp))
                KeptAppsSection()
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

private fun packageFor(entry: KeptEntry, resolver: AndroidKeptAppResolver): String? = when (entry) {
    KeptEntry.PHONE -> resolver.dialerPackage()
    KeptEntry.MESSAGES -> resolver.smsPackage()
    KeptEntry.CAMERA -> resolver.cameraPackage()
    KeptEntry.MUSIC -> resolver.musicPackage()
    KeptEntry.WHATSAPP -> resolver.whatsappPackage()
    else -> null
}

/**
 * Where the Maps entry goes (Kevin, 2026-10-23): Google Maps (default) or LEGION's own navigation.
 * Stored live; the bounce reads it on every window change, so a change applies at once.
 */
@Composable
private fun MapsChoiceSection() {
    val context = LocalContext.current
    val sem = LocalLegionSemantics.current
    val store = remember(context) { (context.applicationContext as MidnightApplication).dumb.mapsChoice }
    var choice by remember { mutableStateOf(store.get()) }
    val googleInstalled = remember(context) { AndroidKeptAppResolver(context).googleMapsPackage() != null }
    Text("Maps", style = MaterialTheme.typography.labelLarge)
    Text(
        when {
            choice == MapsChoice.LEGION_NAV -> "Maps opens LEGION navigation."
            googleInstalled -> "Maps opens Google Maps, which stays open in Matrix mode."
            else -> "Google Maps is not installed on this phone, so Maps opens LEGION navigation."
        },
        style = LegionType.stamp,
        color = if (choice == MapsChoice.GOOGLE_MAPS && !googleInstalled) sem.estimated else sem.faint,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MapsChoice.entries.forEach { option ->
            val label = if (option == MapsChoice.GOOGLE_MAPS) "Google Maps" else "LEGION navigation"
            val picked = option == choice
            OutlinedButton(
                onClick = {
                    store.set(option)
                    choice = option
                },
                modifier = Modifier.semantics { selected = picked },
            ) { Text(if (picked) "$label (chosen)" else label) }
        }
    }
}

@Composable
private fun ModeSwitchRow(on: Boolean, onTurnOn: () -> Unit, onLeave: () -> Unit) {
    val sem = LocalLegionSemantics.current
    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Matrix mode", style = MaterialTheme.typography.bodyMedium)
                Text(if (on) "On. Leave it from the home list." else "Off", style = LegionType.stamp, color = sem.faint)
            }
            if (on) {
                OutlinedButton(onClick = onLeave) { Text("Leave") }
            } else {
                Switch(checked = false, onCheckedChange = { onTurnOn() })
            }
        }
    }
}

@Composable
private fun GrantRowView(row: DumbGrantRow) {
    val context = LocalContext.current
    // The result is ignored: the screen re-reads every grant when it resumes.
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    val sem = LocalLegionSemantics.current
    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(row.title, style = MaterialTheme.typography.bodyMedium)
                Text(row.state, style = LegionType.stamp, color = if (row.granted) sem.faint else sem.estimated)
            }
            if (row.action != GrantAction.None) {
                OutlinedButton(onClick = {
                    runCatching {
                        when (val action = row.action) {
                            is GrantAction.OpenSettings -> context.startActivity(
                                Intent(action.intentAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                            GrantAction.PickWallpaper -> context.startActivity(
                                Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                                    .putExtra(
                                        WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                                        ComponentName(context, MatrixWallpaperService::class.java),
                                    )
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                            GrantAction.OpenAppInfo -> context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    .setData(Uri.fromParts("package", context.packageName, null))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                            is GrantAction.RequestRole ->
                                DumbSetup.roleRequestIntent(context, action.role)?.let(roleLauncher::launch)
                            GrantAction.None -> Unit
                        }
                    }
                }) { Text(row.buttonLabel) }
            }
        }
    }
}

@Composable
private fun KeptAppsSection() {
    val context = LocalContext.current
    val sem = LocalLegionSemantics.current
    val resolver = remember(context) { AndroidKeptAppResolver(context) }
    Text("Matrix home list", style = MaterialTheme.typography.labelLarge)
    Text(
        "The short list on Matrix home. These are never blocked by category or the feed-app list; every other " +
            "app stays open unless it is blocked under Blocked apps.",
        style = LegionType.stamp,
        color = sem.faint,
    )
    KeptEntry.entries.forEach { entry ->
        val pkg = packageFor(entry, resolver)
        // WhatsApp's row is hidden from Home when it is not installed, so it is not "missing" here.
        if (entry == KeptEntry.WHATSAPP && pkg == null) return@forEach
        // Maps is described by its own setting above; the row says what the setting currently does.
        if (entry == KeptEntry.MAPS) return@forEach
        val missing = entry.isExternal && pkg == null
        Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(entry.label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    when {
                        missing -> "Nothing on this phone answers this, so it will say so when tapped."
                        pkg != null -> "Opens $pkg"
                        else -> "Opens inside LEGION"
                    },
                    style = LegionType.stamp,
                    color = if (missing) sem.estimated else sem.faint,
                )
            }
        }
    }
    val clock = remember(resolver) { resolver.clockPackages() }
    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("Clock (the big time on Home)", style = MaterialTheme.typography.bodyMedium)
            Text(
                if (clock.isEmpty()) {
                    "No clock app on this phone can show alarms, so tapping the time will say so."
                } else {
                    "Opens " + clock.joinToString(", ")
                },
                style = LegionType.stamp,
                color = if (clock.isEmpty()) sem.estimated else sem.faint,
            )
        }
    }
}
