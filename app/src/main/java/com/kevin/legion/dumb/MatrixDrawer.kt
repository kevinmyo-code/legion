package com.kevin.legion.dumb

import com.kevin.legion.ui.apps.DrawerApp

/**
 * The Matrix "All apps" drawer's judgement (ADR 0062, 2026-10-24), free of Android types so it is
 * testable. Blocked apps are never listed; the verdict is [BlockPolicy]'s, the same one the bounce
 * and the settings list use.
 */
object MatrixDrawer {
    /** Alphabetical, personal apps before work apps on a tie, blocked apps removed. */
    fun listed(apps: List<DrawerApp>, isBlocked: (String) -> Boolean): List<DrawerApp> =
        apps.filterNot { isBlocked(it.packageName) }
            .sortedWith(compareBy<DrawerApp>({ it.label.lowercase() }, { it.isWork }))

    /** How many distinct packages the drawer hides. */
    fun blockedCount(apps: List<DrawerApp>, isBlocked: (String) -> Boolean): Int =
        apps.map { it.packageName }.distinct().count(isBlocked)

    fun footer(blocked: Int): String = when (blocked) {
        0 -> "No apps blocked. Change in Matrix settings."
        1 -> "1 app blocked. Change in Matrix settings."
        else -> "$blocked apps blocked. Change in Matrix settings."
    }
}
