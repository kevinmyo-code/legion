package com.kevin.legion.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * The [Activity] hosting this composition, unwrapping any [ContextWrapper] chain - or null if
 * there genuinely is not one.
 *
 * **Relocated from `ui/SettingsScreen.kt` (command-center ticket 02, 2026-08-22)** when that
 * screen split into five subscreens under `ui/settings/`: [com.kevin.legion.ui.settings
 * .PermissionsDiagnosticsScreen] needs it for the same background-location
 * `shouldShowRequestPermissionRationale` check the old monolith did, and it lives here now, public,
 * rather than `internal` to one package, so any screen that needs it can import it without a
 * duplicate copy. Behaviour is unchanged from the original - only the location and visibility moved.
 *
 * Exists because `LocalContext.current as? Activity` is an assumption about how deep the context is
 * wrapped, and **its failure mode is silence**: the cast yields null, the caller reads a `false`
 * that means "no rationale needed" rather than "could not check", and a permission shortcut quietly
 * stops appearing. This is the standard AndroidX pattern and it cannot be wrong.
 */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
