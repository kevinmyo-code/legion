package com.kevin.legion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kevin.legion.ui.common.EqualHeightRow
import com.kevin.legion.ui.common.HalfTile

/**
 * The third tab ("C", [LegionRoute.METERS]'s own doc comment - Kevin, verbatim: "C as another tab.
 * retire the bottom headers like cred fleet etc. those we tap through from view C the meters.") -
 * a dashboard of at-a-glance meters over every aspect this app's old six-tab bottom bar used to
 * expose directly, each one tapping through to its own screen exactly the way
 * [TodayScreen]'s existing INTAKE/BIO/LOG/CRED/FLEET tile rows already tap through (`onOpenBody`
 * etc - see that screen's own `HalfTile(... modifier = Modifier.clickable(onClick = onOpenBody))`
 * pattern, reused here rather than a second tap-through convention).
 *
 * **SKELETON ONLY (calendar-home ticket).** Every tile below is a clearly-marked placeholder - no
 * meter VALUE is computed in this file, on purpose (the brief's own instruction: "Do not compute
 * meter values here"). A follow-up ticket fills each tile from the SAME pure builders the demoted
 * tabs already compute their own tile figures from ([com.kevin.legion.ui.buildIntakeTile],
 * [com.kevin.legion.ui.ledger.LedgerPendingResolver]'s CRED-tile equivalent, FLEET's own tile
 * builder, etc - `TodayScreen.kt`'s own tile-row sections name each one) rather than inventing a
 * second reading of any of them here.
 */
@Composable
fun MetersScreen(
    onOpenBody: () -> Unit,
    onOpenMoney: () -> Unit,
    onOpenFleet: () -> Unit,
    onOpenNotes: () -> Unit,
    onOpenPantry: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            "METERS",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
        Text(
            "Placeholder tiles - no figures computed yet. Tap a tile to open its full screen.",
            style = com.kevin.legion.ui.theme.LegionType.stamp,
            color = com.kevin.legion.ui.theme.LocalLegionSemantics.current.faint,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )

        EqualHeightRow(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp), horizontalGap = 9.dp) {
            PlaceholderMeterTile(header = "Bio", onClick = onOpenBody)
            PlaceholderMeterTile(header = "Cred", onClick = onOpenMoney)
        }
        EqualHeightRow(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp), horizontalGap = 9.dp) {
            PlaceholderMeterTile(header = "Fleet", onClick = onOpenFleet)
            PlaceholderMeterTile(header = "Log", onClick = onOpenNotes)
        }
        EqualHeightRow(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp), horizontalGap = 9.dp) {
            PlaceholderMeterTile(header = "Pantry", onClick = onOpenPantry)
        }
        // Voice notes (ticket 04, `.scratch/voice-notes/issues/04-voice-tools-and-the-hands-path.md`)
        // does NOT get a tile here. A concurrent session registered
        // `LegionRoute.SETTINGS_VOICE_NOTES` -> `ui/voicenotes/VoiceNotesScreen.kt` in
        // `MainActivity.kt` while this file was being edited (both landed the same day) - see that
        // route's own doc comment. A METERS tile was drafted here too, briefly, and was reverted
        // rather than left in to avoid two live routes reaching the same screen with no ruling on
        // which one is canonical. That is a decision for Kevin, not something to guess at under a
        // known concurrent-writer collision - see this ticket's own build report.
    }
}

/** One SKELETON meter tile - a real [HalfTile] shell (this app's own tiling grammar, `DeckTiles.kt`)
 * with a placeholder hero/caption rather than a computed reading, tapping through to [onClick]
 * exactly like every demoted tab's own home-tile row already does. */
@Composable
private fun PlaceholderMeterTile(header: String, onClick: () -> Unit) {
    HalfTile(
        header = header,
        hero = "--",
        caption = "not built yet",
        modifier = Modifier.clickable(onClick = onClick),
    )
}
