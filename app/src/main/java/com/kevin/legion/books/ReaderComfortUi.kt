@file:Suppress("FunctionNaming") // @Composable convention is PascalCase; detekt's rule does not know it.

package com.kevin.legion.books

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kevin.legion.R
import com.kevin.legion.ui.theme.soft.MsIcon
import com.kevin.legion.ui.theme.soft.SoftColors
import kotlin.math.roundToInt

// The reader's comfort controls (ticket books/06), split out of ReaderScreen.kt to keep that file small.

/** Says the current theme and what one tap does, in words, so the icon is never the only signal. */
internal fun themeDescription(theme: ReaderTheme): String =
    "Reading theme: ${theme.label}. Tap for ${theme.next().label}."

internal fun warmDescription(settings: ReaderSettings): String {
    val state = when {
        settings.warmAuto -> "Auto, on from sunset to sunrise"
        settings.warmOn -> "on"
        else -> "off"
    }
    return "Warm filter: $state. Tap to turn it ${if (settings.warmOn) "off" else "on"}."
}

/** Day is a sun, Night a moon, Sepia a paper-coloured dot (the app has no sepia icon). */
@Composable
internal fun ThemeGlyph(theme: ReaderTheme, palette: ReaderPalette) {
    when (theme) {
        ReaderTheme.DAY -> MsIcon(R.drawable.ms_sunny, null, palette.text, size = 20.dp)
        ReaderTheme.NIGHT -> MsIcon(R.drawable.ms_dark_mode, null, palette.text, size = 20.dp)
        ReaderTheme.SEPIA -> Box(
            Modifier.size(18.dp).clip(CircleShape).background(ReaderPalette.of(ReaderTheme.SEPIA).page)
                .border(2.dp, palette.text, CircleShape),
        )
    }
}

/** Full screen, the warm filter's strength, and its Auto option. */
@Composable
internal fun WarmSettings(settings: ReaderSettings, actions: ReaderActions) {
    ToggleRow("Full screen", "Hides the status and navigation bars. Swipe from an edge to peek.",
        settings.fullScreen, actions.onFullScreen)
    Text(
        "Warm filter: ${settings.warmStrength}%",
        style = MaterialTheme.typography.titleSmall,
        color = SoftColors.text,
    )
    Slider(
        value = settings.warmStrength.toFloat(),
        onValueChange = { actions.onWarmStrength(it.roundToInt()) },
        valueRange = 0f..WarmFilter.MAX_STRENGTH.toFloat(),
        modifier = Modifier.semantics { contentDescription = "Warm filter strength" },
    )
    ToggleRow(
        "Auto warm filter",
        "On from sunset to sunrise where you are. With no location fix it follows the on/off button.",
        settings.warmAuto,
        actions.onWarmAuto,
    )
}

@Composable
private fun ToggleRow(title: String, note: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = SoftColors.text)
            Text(note, style = MaterialTheme.typography.bodySmall, color = SoftColors.text3)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
internal fun SmallRound(description: String, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(SoftColors.cardHighest)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = SoftColors.text, style = MaterialTheme.typography.bodyMedium) }
}
