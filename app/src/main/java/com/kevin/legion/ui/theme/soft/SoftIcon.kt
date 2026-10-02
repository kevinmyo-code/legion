package com.kevin.legion.ui.theme.soft

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One helper over [painterResource] for the vendored `ms_*` Material Symbols Rounded vector
 * drawables (home-launcher ticket 02), so 03 and 04 do not each write their own tinting boilerplate.
 * [tint] is a required parameter, not a default, because every one of these vectors ships as plain
 * white (`android:fillColor="@android:color/white"`) with the theme-attribute tint line stripped at
 * vendor time (ticket 02's own instruction - a `?attr/colorControlNormal` reference parsed outside a
 * View theme is a crash risk) - an untinted icon here is a plain white square on a dark ground, not
 * a safe default to fall back to.
 */
@Composable
fun MsIcon(
    @DrawableRes res: Int,
    contentDescription: String?,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
) {
    Image(
        painter = painterResource(res),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(tint),
    )
}
