package com.kevin.legion.ui.apps

/**
 * The app drawer's judgement, kept free of Android types so it is testable without a device
 * (ADR 0050: LEGION may be the phone's home app). `AppsScreen` does the PackageManager and
 * LauncherApps work and hands rows here.
 */
data class DrawerApp(
    val label: String,
    val packageName: String,
    val className: String,
    /** True for an app in the work profile. Shown in words, never by the system's badge alone,
     * because two "Outlook" rows that differ only by a small briefcase are easy to tap wrong. */
    val isWork: Boolean,
    /** Opaque key back to the Android `UserHandle` the row came from. */
    val profileKey: Int,
)

/**
 * The rows to show for [query]: case-insensitive match on the label or the package name, sorted by
 * label with personal apps before work apps on a tie. A blank query shows everything. Matching the
 * package too lets "bofa" find "Bank of America", whose label shares no letters with what people
 * call it.
 */
fun filterDrawer(apps: List<DrawerApp>, query: String): List<DrawerApp> {
    val q = query.trim().lowercase()
    val hits = if (q.isEmpty()) apps else apps.filter {
        it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
    }
    return hits.sortedWith(compareBy<DrawerApp>({ it.label.lowercase() }, { it.isWork }))
}
