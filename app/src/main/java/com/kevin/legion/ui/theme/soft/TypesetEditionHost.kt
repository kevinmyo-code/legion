package com.kevin.legion.ui.theme.soft

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevin.legion.R
import com.kevin.legion.clock.ClockGraph
import com.kevin.legion.ui.theme.LocalMatrixMode

/**
 * Resolves the Typeset edition once per activity root and provides it as [LocalTypesetEdition]
 * (ADR 0081): night when the phone is dark or bedtime is on, otherwise day, and the one-tap
 * [EditionSwitch] on top. Wrap each activity's `setContent` body in it, inside `LocalMatrixMode`
 * where the activity provides that.
 *
 * With [systemBars] true it also sets the status and navigation bars to the page: paper with dark
 * icons by day, warm black with light icons by night, and the old dark ground under Matrix mode.
 * The app is not edge-to-edge (opaque bars from `themes.xml`), so a paper screen under the dark
 * launch-theme bar would look broken. The reader passes false: it owns its own bars.
 */
@Composable
fun ProvideTypesetEdition(systemBars: Boolean = true, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val clock = remember(context) { ClockGraph.controller(context.applicationContext) }
    val clockState by clock.state.collectAsStateWithLifecycle()
    val auto = automaticEdition(isSystemInDarkTheme(), clockState.bedtime.active)
    val switch = EditionSwitch.shared
    val version by switch.version.collectAsState()
    LaunchedEffect(auto) { switch.settle(auto) }
    val edition = remember(auto, version) { switch.resolve(auto) }
    if (systemBars) TypesetSystemBars(edition, LocalMatrixMode.current)
    CompositionLocalProvider(LocalTypesetEdition provides edition, content = content)
}

/** The bar colour and icon tone for [edition]; Matrix mode keeps the launch theme's dark ground. */
@Composable
private fun TypesetSystemBars(edition: TypesetEdition, matrix: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val matrixGround = colorResource(R.color.instrument_ground)
    val (bar: Color, lightIcons: Boolean) = when {
        matrix -> matrixGround to false
        edition == TypesetEdition.DAY -> TypesetDayPalette.ground to true
        else -> TypesetNightPalette.ground to false
    }
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        @Suppress("DEPRECATION") // targetSdk 34: opaque bars still honour these colours.
        run {
            window.statusBarColor = bar.toArgb()
            window.navigationBarColor = bar.toArgb()
        }
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = lightIcons
            isAppearanceLightNavigationBars = lightIcons
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
