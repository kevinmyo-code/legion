package com.kevin.legion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics

/**
 * `settings` tab. As of command-center ticket 02 (2026-08-22) this is a short list of five
 * subscreens, not the 718-line wall it used to be - Kevin: *"in setup screen especially the
 * proactive levers can be put in a submenu etc."* Every row that used to live directly on this
 * screen moved to one of `ui/settings/AssistantSettingsScreen.kt`,
 * `ui/settings/ProactiveSpeechScreen.kt`, `ui/settings/ConnectionsScreen.kt`,
 * `ui/settings/DataPrivacyScreen.kt`, or `ui/settings/PermissionsDiagnosticsScreen.kt` - moved, not
 * rewritten, so every single-writer pattern and write path is exactly what it was before. Their own
 * class docs carry the reasoning for which row landed where and the row-by-row accounting lives in
 * the ticket's own build report.
 *
 * **How you get here (2026-08-12).** Via the SETUP stamp on the global
 * [com.kevin.legion.ui.common.StatusLine]. Before that stamp existed this screen was unreachable on
 * any ordinary device - see StatusLine's own doc for the closed loop.
 *
 * This screen itself now owns no state at all - it is five navigation calls plus the same
 * disclaimer stamp the old screen opened with, so there is nothing here left to reload on
 * `ON_RESUME`.
 */
@Composable
fun SettingsScreen(
    onOpenAssistant: () -> Unit,
    onOpenVoiceGuide: () -> Unit = {},
    onOpenProactiveSpeech: () -> Unit,
    onOpenConnections: () -> Unit,
    onOpenDataPrivacy: () -> Unit,
    onOpenPermissionsDiagnostics: () -> Unit,
) {
    val sem = LocalLegionSemantics.current

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("SETUP", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "Credentials live on this device and in your own accounts. Nothing goes through " +
                    "a server I run.",
                style = LegionType.stamp,
                color = sem.faint,
            )

            Spacer(Modifier.height(12.dp))
            // First row on purpose (Kevin, 2026-08-22: "where is the what can i do screen? dont
            // see it anywhere") - a discovery screen buried one level down inside Assistant was a
            // discoverability failure OF the discoverability screen. It stays linked from
            // Assistant too; two doors to the same room is fine, zero visible doors is the bug.
            SettingsNavRow(
                label = "What can I do",
                status = "Every voice command, and where its button lives.",
                onClick = onOpenVoiceGuide,
            )
            Spacer(Modifier.height(6.dp))
            SettingsNavRow(
                label = "Assistant",
                status = "Companion, voice, wake word, and how it behaves.",
                onClick = onOpenAssistant,
            )

            Spacer(Modifier.height(8.dp))
            SettingsNavRow(
                label = "Proactive speech",
                status = "When it's allowed to speak first.",
                onClick = onOpenProactiveSpeech,
            )

            Spacer(Modifier.height(8.dp))
            SettingsNavRow(
                label = "Connections",
                status = "Gemini key, Google, Spotify.",
                onClick = onOpenConnections,
            )

            Spacer(Modifier.height(8.dp))
            SettingsNavRow(
                label = "Data and privacy",
                status = "What's remembered, and what's stored.",
                onClick = onOpenDataPrivacy,
            )

            Spacer(Modifier.height(8.dp))
            SettingsNavRow(
                label = "Permissions and diagnostics",
                status = "Calls, location, and diagnostic probes.",
                onClick = onOpenPermissionsDiagnostics,
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}
