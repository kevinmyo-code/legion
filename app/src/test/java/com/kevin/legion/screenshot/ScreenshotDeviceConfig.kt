package com.kevin.legion.screenshot

/**
 * The one device profile every screenshot test (hardening ticket 01) renders against: the real
 * phone this app is verified on (`memory/MEMORY.md`: "the real phone is a Galaxy A25 (SM-A256U)...
 * 384x832dp"). A Robolectric `@Config(qualifiers = ...)` resource-qualifier string, not a literal
 * pixel size - Robolectric resolves `w384dp-h832dp` into a concrete pixel canvas using its own
 * default density for the sdk under test, matching how every other Robolectric-driven test in this
 * module already lets Robolectric own the device metrics rather than hand-picking a raw px size.
 *
 * **Dark-only, on purpose, not a gap.** `ui/theme/Theme.kt`'s own doc comment: "VACUUM/SENTRY is
 * dark-only... the OS light/dark toggle stops mattering... `LegionThemeFollowingSystem` and the
 * `darkTheme` parameter... are all REMOVED, not merely unused." There is no light `ColorScheme` in
 * this app to render a second baseline against - every screenshot test here renders exactly once,
 * through [com.kevin.legion.ui.theme.LegionTheme], which is unconditionally the dark scheme.
 */
object ScreenshotDeviceConfig {
    /** The A25's own dp size, per `memory/MEMORY.md`. */
    const val QUALIFIERS = "w384dp-h832dp"
}
