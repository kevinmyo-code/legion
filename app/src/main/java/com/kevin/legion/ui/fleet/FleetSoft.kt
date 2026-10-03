package com.kevin.legion.ui.fleet

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kevin.legion.ui.theme.soft.SoftTheme

/**
 * Sentence-case a tile hero the shared resolvers state in capitals ("NO LINK" -> "No link",
 * "3 DUE" -> "3 due"); a bare "OK" and a figure pass through. The words are unchanged, only the
 * case, so the trust wording a hero carries is still there. "No drives" stays two words (the tile caption already says "nothing recorded yet", and a
 * longer hero clips in the half tile). (HOME's tiles still use the capitals; this is the soft drill-down's own
 * rendering of the same resolver output.)
 */
internal fun softHero(text: String): String {
    if (text == "NO DRIVES") return "No drives"
    val words = text.split(" ").map { if (it.length > 1 && it.any(Char::isLetter) && it != "OK") it.lowercase() else it }
    return words.joinToString(" ").replaceFirstChar { it.uppercase() }
}

/**
 * The one wrap every Fleet screen shares (soft-fleet conversion, ADR 0051): [SoftTheme] around a
 * full-screen [Surface] painted [MaterialTheme.colorScheme.background] (SoftColors.ground) rather
 * than the `surface` default, which under the soft scheme is the CARD colour. Kept as one helper so
 * the ~15 Fleet screens cannot drift on it.
 *
 * Anything a screen reads from [com.kevin.legion.ui.theme.LocalLegionSemantics] must be read INSIDE
 * this lambda: a read above it still resolves to the mission-control values.
 */
@Composable
internal fun FleetSoftSurface(content: @Composable () -> Unit) {
    SoftTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
    }
}
