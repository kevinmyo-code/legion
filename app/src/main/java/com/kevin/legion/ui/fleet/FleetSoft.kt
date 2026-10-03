package com.kevin.legion.ui.fleet

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kevin.legion.ui.theme.soft.SoftTheme

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
