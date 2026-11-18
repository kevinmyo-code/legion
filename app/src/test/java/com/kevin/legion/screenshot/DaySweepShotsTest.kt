package com.kevin.legion.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import com.kevin.legion.ui.BodyScreen
import com.kevin.legion.ui.CalendarScreen
import com.kevin.legion.ui.CarProbeScreen
import com.kevin.legion.ui.CarsScreen
import com.kevin.legion.ui.CompanionsScreen
import com.kevin.legion.ui.DriveSyncScreen
import com.kevin.legion.ui.DrivingModeScreen
import com.kevin.legion.ui.FleetScreen
import com.kevin.legion.ui.KeyScreen
import com.kevin.legion.ui.LedgerScreen
import com.kevin.legion.ui.PantryScreen
import com.kevin.legion.ui.SavedPlacesScreen
import com.kevin.legion.ui.SettingsScreen
import com.kevin.legion.ui.SpotifyScreen
import com.kevin.legion.ui.TelemetryScreen
import com.kevin.legion.ui.apps.DrawerApp
import com.kevin.legion.ui.apps.letterFolders
import com.kevin.legion.ui.companions.PlaybookScreen
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LocalMatrixMode
import com.kevin.legion.ui.theme.soft.LocalTypesetEdition
import com.kevin.legion.ui.theme.soft.SoftTheme
import com.kevin.legion.ui.theme.soft.TypesetEdition
import com.kevin.legion.ui.typeset.TypesetAppsIndex
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Review renders for the day-mode sweep (home-ui 2026-11-17), NOT baselines. Every route the shell
 * reaches, drawn the way `MainActivity` nests it: Matrix off, the Typeset edition provided, then
 * `LegionTheme` and a `Scaffold` (its container is the shell's ground), the StatusLine under
 * `SoftTheme`, the screen in the height left. No ground painted by the harness, so a screen that
 * never paints its own shows the shell's. Written as plain PNGs to the directory named on the first
 * line of `~/.legion/day-sweep.dir` (the second line is a file prefix, "before" or "after"); skipped
 * when that file is absent, so the normal suite never runs them. A screen that cannot compose here
 * (a Hilt view model, a service) is logged to `sweep-errors.txt` in that directory, never dropped.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
class DaySweepShotsTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val marker = File(System.getProperty("user.home"), ".legion/day-sweep.dir")
    private val lines = marker.takeIf { it.isFile }?.readLines()?.map { it.trim() }.orEmpty()
    private val dir: File? = lines.firstOrNull()?.takeIf { it.isNotBlank() }?.let(::File)
    private val prefix = lines.getOrNull(1)?.ifBlank { null } ?: "shot"

    private fun shot(name: String, edition: TypesetEdition = TypesetEdition.DAY, screen: @Composable () -> Unit) {
        assumeTrue("day-sweep.dir unset: review renders skipped", dir != null)
        dir!!.mkdirs()
        val file = File(dir, "$prefix-$name-${edition.name.lowercase()}.png")
        runCatching {
            composeTestRule.mainClock.autoAdvance = false
            composeTestRule.setContent { Shell(edition, screen) }
            composeTestRule.mainClock.advanceTimeBy(FRAMES_MS)
            composeTestRule.onRoot().captureRoboImage(
                file,
                roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record),
            )
        }.onFailure { e ->
            val why = e.message?.lineSequence()?.firstOrNull()
            File(dir, "sweep-errors.txt").appendText("$prefix $name ${edition.name}: ${e.javaClass.simpleName}: $why\n")
        }
    }

    @Composable
    private fun Shell(edition: TypesetEdition, screen: @Composable () -> Unit) {
        CompositionLocalProvider(LocalMatrixMode provides false, LocalTypesetEdition provides edition) {
            LegionTheme {
                Scaffold(Modifier.fillMaxSize()) { pad ->
                    Column(Modifier.padding(pad).fillMaxSize()) {
                        ShellStatusBar()
                        Box(Modifier.weight(1f).fillMaxWidth()) { screen() }
                    }
                }
            }
        }
    }

    private fun app(label: String, pkg: String, work: Boolean = false) =
        DrawerApp(label, pkg, "$pkg.Main", work, if (work) 1 else 0)

    private val apps = listOf(
        app("Authenticator", "example.auth"), app("Books", "example.books"), app("Browser", "example.browser"),
        app("Camera", "example.camera"), app("Calendar", "example.calendar"), app("Feedly", "example.feed.one"),
        app("Files", "example.files"), app("Mail", "example.mail"), app("Mail", "example.mail", work = true),
        app("Maps", "example.maps"), app("Scroller", "example.feed.two"), app("Weather", "example.weather"),
        app("7 Minute", "example.seven"),
    )

    @Composable
    private fun AppsTray() = SoftTheme {
        TypesetAppsIndex(
            folders = letterFolders(apps),
            query = "",
            onQueryChange = {},
            blocked = { it.packageName.startsWith("example.feed.") },
            onOpen = {},
            onLongPress = {},
            workPaused = false,
        )
    }

    @Composable
    private fun Front(night: Boolean) = com.kevin.legion.ui.typeset.TypesetPage {
        com.kevin.legion.ui.typeset.TypesetHomeContent(
            glance = com.kevin.legion.ui.typeset.TypesetGlance(
                dateline = "Monday, 9 November",
                time = "14:20",
                next = com.kevin.legion.ui.typeset.TypesetFigure("15:30", null, "Study group"),
                due = com.kevin.legion.ui.typeset.TypesetFigure("3", null, "due today"),
                left = com.kevin.legion.ui.typeset.TypesetFigure("\$1,240", null, "left for November"),
            ),
            phase = com.kevin.legion.ui.typeset.OrbPhase.IDLE,
            status = "Tap to talk",
            night = night,
            cards = emptyList(),
            actions = com.kevin.legion.ui.typeset.TypesetHomeActions(),
        )
    }

    @Test fun home() = shot("home") { Front(night = false) }
    @Test fun homeNight() = shot("home", TypesetEdition.NIGHT) { Front(night = true) }

    @Test fun apps() = shot("apps") { AppsTray() }
    @Test fun appsNight() = shot("apps", TypesetEdition.NIGHT) { AppsTray() }

    @Test fun settings() = shot("settings") {
        SettingsScreen(
            onOpenAssistant = {}, onOpenProactiveSpeech = {}, onOpenConnections = {}, onOpenDataPrivacy = {},
            onOpenPermissionsDiagnostics = {},
        )
    }
    @Test fun settingsNight() = shot("settings", TypesetEdition.NIGHT) {
        SettingsScreen(
            onOpenAssistant = {}, onOpenProactiveSpeech = {}, onOpenConnections = {}, onOpenDataPrivacy = {},
            onOpenPermissionsDiagnostics = {},
        )
    }
    @Test fun settingsAssistant() = shot("settings-assistant") {
        com.kevin.legion.ui.settings.AssistantSettingsScreen(
            onBack = {}, onOpenCompanions = {}, onOpenPlaybooks = {}, onOpenVoiceGuide = {}, onOpenWakeTuning = {},
        )
    }
    @Test fun updates() = shot("updates") { com.kevin.legion.ui.settings.UpdatesScreen(onBack = {}) }
    @Test fun wakeTuning() = shot("wake-tuning") { com.kevin.legion.ui.settings.WakeTuningScreen(onBack = {}) }
    @Test fun voiceGuide() = shot("voice-guide") { com.kevin.legion.ui.help.VoiceGuideScreen(onBack = {}) }
    @Test fun dumbMode() = shot("dumb-mode") {
        com.kevin.legion.ui.dumb.DumbModeSettingsScreen(onBack = {}, onOpenHome = {})
    }
    @Test fun proactive() = shot("proactive") {
        com.kevin.legion.ui.settings.ProactiveSpeechScreen(onBack = {}, onOpenWatches = {})
    }
    @Test fun connections() = shot("connections") {
        com.kevin.legion.ui.settings.ConnectionsScreen(
            onBack = {}, onOpenKeyScreen = {}, onOpenGoogleAccess = {}, onOpenSpotify = {},
        )
    }
    @Test fun dataPrivacy() = shot("data-privacy") {
        com.kevin.legion.ui.settings.DataPrivacyScreen(onBack = {}, onOpenMemory = {})
    }
    @Test fun permissions() = shot("permissions") {
        com.kevin.legion.ui.settings.PermissionsDiagnosticsScreen(onBack = {}, onOpenCarProbe = {}, onOpenDialer = {})
    }
    @Test fun key() = shot("key") { KeyScreen(onBack = {}) }
    @Test fun phoneDial() = shot("phone-dial") { com.kevin.legion.ui.phone.PhoneDialScreen(onBack = {}) }
    @Test fun companions() = shot("companions") { CompanionsScreen(onBack = {}) }
    @Test fun google() = shot("google") {
        com.kevin.legion.ui.sync.GoogleAccessScreen(onBack = {}, onOpenDriveSync = {})
    }
    @Test fun driveSync() = shot("drive-sync") { DriveSyncScreen(onBack = {}) }
    @Test fun carProbe() = shot("car-probe") { CarProbeScreen(onBack = {}) }
    @Test fun playbooks() = shot("playbooks") { PlaybookScreen(onBack = {}) }
    @Test fun fleet() = shot("car") {
        FleetScreen(onOpenPlaces = {}, onOpenCars = {}, onBack = {}, onOpenDrivingMode = {})
    }
    @Test fun fleetNight() = shot("car", TypesetEdition.NIGHT) {
        FleetScreen(onOpenPlaces = {}, onOpenCars = {}, onBack = {}, onOpenDrivingMode = {})
    }
    @Test fun cars() = shot("cars") { CarsScreen(onBack = {}) }
    @Test fun telemetry() = shot("telemetry") { TelemetryScreen(onBack = {}) }
    @Test fun driving() = shot("driving") { DrivingModeScreen(onExit = {}) }
    @Test fun places() = shot("places") { SavedPlacesScreen(onBack = {}, onOpenConditions = {}) }
    @Test fun spotify() = shot("spotify") {
        SpotifyScreen(onBack = {}, authOk = null, authNonce = 0, onOpenMedia = {})
    }
    @Test fun media() = shot("media") { com.kevin.legion.ui.media.MediaScreen(onBack = {}) }
    @Test fun dashboard() = shot("dashboard") { com.kevin.legion.ui.widgets.WidgetPagerRoot(onOpenRoute = {}) }
    @Test fun today() = shot("today") { CalendarScreen(onBack = {}) }
    @Test fun money() = shot("money") {
        LedgerScreen(onOpenGroceries = {}, onBack = {})
    }
    @Test fun pantry() = shot("pantry") { PantryScreen(onOpenImport = {}, onBack = {}) }
    @Test fun body() = shot("body") { BodyScreen(onBack = {}) }
    @Test fun news() = shot("news") { com.kevin.legion.ui.news.NewsScreen(onBack = {}) }
    @Test fun ask() = shot("ask") { com.kevin.legion.ui.ask.AskScreen(onBack = {}) }
    @Test fun grades() = shot("grades") { com.kevin.legion.ui.grades.GradesScreen(onBack = {}) }
    @Test fun voiceNotes() = shot("voice-notes") { com.kevin.legion.ui.voicenotes.VoiceNotesScreen(onBack = {}) }

    private companion object {
        const val FRAMES_MS = 600L
    }
}
