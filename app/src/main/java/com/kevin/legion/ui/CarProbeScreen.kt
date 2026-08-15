package com.kevin.legion.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kevin.legion.car.CarCallProbe
import com.kevin.legion.car.CarProbeLog
import com.kevin.legion.car.ProbeEntry
import com.kevin.legion.ui.common.Hairline
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * `settings/car-probe` - the on-screen readout for [CarProbeLog]. Wave 1 of
 * the Android Auto probe harness (`.scratch/android-auto/map.md`): the A17k
 * filters this app's own logcat (`memory/MEMORY.md`), so this screen is the
 * ONLY reporting channel later waves' Telecom/media-session/OBD probes have.
 * Reachable from Settings as a debug row - see [SettingsScreen] and
 * [LegionRoute.SETTINGS_CAR_PROBE].
 *
 * Split per `compose-state-holder-ui-split`: [CarProbeScreen] owns the
 * `StateFlow` collection and the clipboard side effect, [CarProbeContent] is
 * plain state plus callbacks and is what the `@Preview`s exercise.
 */
@Composable
fun CarProbeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val entries by CarProbeLog.entries.collectAsState()

    CarProbeContent(
        entries = entries,
        onBack = onBack,
        onCopy = { copyDumpToClipboard(context) },
        onClear = { CarProbeLog.clear() },
        onRegisterAccount = { CarCallProbe.registerAccount(context) },
        onPlaceCall = { CarCallProbe.placeCall(context) },
        onEndCall = { CarCallProbe.endCall() },
    )
}

private fun copyDumpToClipboard(context: Context) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("Car probe log", CarProbeLog.dump()))
}

private val TIMESTAMP_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

/** Plain UI: [entries] plus callbacks, no `CarProbeLog` reference - see the file doc comment. */
@Composable
fun CarProbeContent(
    entries: List<ProbeEntry>,
    onBack: () -> Unit,
    onCopy: () -> Unit,
    onClear: () -> Unit,
    onRegisterAccount: () -> Unit = {},
    onPlaceCall: () -> Unit = {},
    onEndCall: () -> Unit = {},
) {
    val sem = LocalLegionSemantics.current
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) {
                    Text("< BACK", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
                }
                Text("CAR PROBE", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                Row {
                    TextButton(onClick = onCopy) {
                        Text("COPY", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
                    }
                    TextButton(onClick = onClear) {
                        // DESTRUCTIVE (ticket 13 re-home, ticket 04 answer §4): neutral ink, not a
                        // standing alarm colour. This debug screen has no two-step confirm, so there
                        // is no confirming step to spend full chrome on.
                        Text("CLEAR", style = LegionType.stamp, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            Hairline()

            // Wave 2's self-managed call probe (ticket 14) - deliberately three flat text
            // buttons, not a styled control, so this reads as a debug surface and not as
            // product UI. Each call is fire-and-forget; every observation it makes lands back
            // in the log list below, not in any state this row holds.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                TextButton(onClick = onRegisterAccount) {
                    Text("REGISTER ACCOUNT", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
                }
                TextButton(onClick = onPlaceCall) {
                    Text("PLACE CALL", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
                }
                TextButton(onClick = onEndCall) {
                    // DESTRUCTIVE (ticket 13 re-home): same reasoning as CLEAR above.
                    Text("END CALL", style = LegionType.stamp, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            Hairline()

            if (entries.isEmpty()) {
                Text(
                    "Nothing logged yet. This screen fills as the Android Auto probes " +
                        "(media browse/search callbacks, later the Telecom and OBD probes) " +
                        "run - logcat is not usable on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = sem.faint,
                    modifier = Modifier.padding(12.dp),
                )
            } else {
                // Reverse-chronological: newest observation first, since that
                // is the one whoever opened this screen mid-probe wants to see
                // without scrolling.
                val reversed = entries.asReversed()
                LazyColumn(Modifier.fillMaxSize()) {
                    items(reversed) { entry ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                            Text(
                                TIMESTAMP_FORMAT.format(entry.timestamp),
                                style = LegionType.stamp,
                                fontFamily = FontFamily.Monospace,
                                color = sem.faint,
                            )
                            Text(
                                entry.tag,
                                style = LegionType.stamp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                entry.message,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Hairline()
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------ previews

@Preview(name = "Car probe: empty", widthDp = 360, heightDp = 720)
@Composable
private fun PreviewCarProbeEmpty() = LegionTheme {
    CarProbeContent(entries = emptyList(), onBack = {}, onCopy = {}, onClear = {})
}

@Preview(name = "Car probe: populated", widthDp = 360, heightDp = 720)
@Composable
private fun PreviewCarProbePopulated() = LegionTheme {
    CarProbeContent(
        entries = listOf(
            ProbeEntry(1_755_000_000_000L, "MediaLibraryService", "onGetLibraryRoot clientPackageName=com.google.android.projection.gearhead"),
            ProbeEntry(1_755_000_001_500L, "MediaLibraryService", "onGetChildren parentId=root"),
            ProbeEntry(1_755_000_003_200L, "MediaLibraryService", "onPlayFromSearch query=\"live moderat\" extras.user_query=\"play Live from Moderat on SoundCloud\""),
        ),
        onBack = {}, onCopy = {}, onClear = {},
    )
}
