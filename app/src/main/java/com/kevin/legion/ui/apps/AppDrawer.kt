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

/** One folder of the Apps screen (home-launcher ticket 07): a letter, or [OTHER_LETTER], and the
 * apps filed under it, already in drawer order. */
data class LetterFolder(val letter: String, val apps: List<DrawerApp>)

/** The folder for every label that does not start with A-Z: digits, symbols, non-Latin scripts. */
const val OTHER_LETTER = "#"

/**
 * The folder [label] is filed under. The first character is uppercased with [java.util.Locale.ROOT]
 * and accented Latin letters fold to their base letter (an "E-acute" files under E), by stripping
 * the combining marks off its canonical decomposition. Anything that is not A-Z after that - a
 * digit, a symbol, Cyrillic, an emoji, a blank label - goes to [OTHER_LETTER]. Never silently
 * dropped: an app with an unfileable label still has a folder.
 */
internal fun folderLetterFor(label: String): String {
    val first = label.trimStart().take(1)
    val base = java.text.Normalizer.normalize(first, java.text.Normalizer.Form.NFD)
        .take(1)
        .uppercase(java.util.Locale.ROOT)
    return if (base.length == 1 && base[0] in 'A'..'Z') base else OTHER_LETTER
}

/**
 * [apps] grouped into one [LetterFolder] per letter that has at least one app, A to Z in order and
 * [OTHER_LETTER] last. Inside a folder the order is [filterDrawer]'s own (label, personal before
 * work on a tie), so a work twin sits right beside its personal app. An empty list gives no
 * folders; the caller says "couldn't read the installed apps" in words rather than showing none.
 */
fun letterFolders(apps: List<DrawerApp>): List<LetterFolder> =
    filterDrawer(apps, "")
        .groupBy { folderLetterFor(it.label) }
        .map { (letter, members) -> LetterFolder(letter, members) }
        .sortedWith(compareBy({ it.letter == OTHER_LETTER }, { it.letter }))
