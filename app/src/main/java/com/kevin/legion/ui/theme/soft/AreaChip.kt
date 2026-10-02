package com.kevin.legion.ui.theme.soft

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The tonal icon chip HOME's tiles draw for an area: a circle in [AreaAccent.container] holding a
 * [MsIcon] tinted [AreaAccent.onContainer]. Extracted out of `HomeScreen.kt`'s private `TileCard`
 * (same 28dp / 16dp defaults) so a drill-down's header draws the identical chip rather than a
 * second hand-rolled copy that drifts. [iconRes] defaults to the area's own [AreaAccent.icon].
 */
@Suppress("FunctionNaming") // @Composable convention is PascalCase; detekt's rule has no Composable exemption here
@Composable
fun AreaChip(
    accent: AreaAccent,
    modifier: Modifier = Modifier,
    @DrawableRes iconRes: Int = accent.icon,
    size: Dp = 28.dp,
    iconSize: Dp = 16.dp,
) {
    Box(
        modifier.size(size).background(accent.container, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        MsIcon(iconRes, contentDescription = null, tint = accent.onContainer, size = iconSize)
    }
}
