package com.kevin.legion.games

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/** A native Android game: an installed app the system itself files under games. */
data class AndroidGame(val packageName: String, val label: String)

/** What the filter needs to know about one installed app. A fake list stands in for it in tests. */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val isGameCategory: Boolean,
    val hasLauncher: Boolean,
)

/**
 * Native games are found, not registered: any installed app with `ApplicationInfo.CATEGORY_GAME`
 * and a launcher entry. Discovery needs no config, so a new game (a Godot build, say) appears the
 * next time the sheet is opened. Package visibility is already covered by the manifest's
 * MAIN/LAUNCHER `<queries>` (the app drawer's), which is exactly the set of apps with a launcher.
 *
 * Caveat, said plainly: the category is whatever the app's manifest or the store declared. A Godot
 * export that sets none is not CATEGORY_GAME and will not be listed; that is the rule asked for,
 * not a bug to paper over with a hand-kept list.
 */
object AndroidGames {
    /** The last-played key for an app, kept apart from ROM document ids (`primary:...`). */
    private const val KEY_PREFIX = "android:"

    fun select(apps: List<InstalledApp>, ownPackage: String? = null): List<AndroidGame> =
        apps.filter { it.isGameCategory && it.hasLauncher && it.packageName != ownPackage }
            .map { AndroidGame(it.packageName, it.label) }
            .sortedBy { it.label.lowercase() }

    fun key(game: AndroidGame): String = KEY_PREFIX + game.packageName

    fun packageOf(key: String): String? = key.removePrefix(KEY_PREFIX).takeIf { key.startsWith(KEY_PREFIX) }

    /** Reads every visible installed app. `hasLauncher` is the real answer from the package manager. */
    fun read(pm: PackageManager): List<InstalledApp> =
        pm.getInstalledApplications(0).map { info ->
            InstalledApp(
                packageName = info.packageName,
                label = info.loadLabel(pm).toString(),
                isGameCategory = info.category == ApplicationInfo.CATEGORY_GAME,
                hasLauncher = pm.getLaunchIntentForPackage(info.packageName) != null,
            )
        }
}
