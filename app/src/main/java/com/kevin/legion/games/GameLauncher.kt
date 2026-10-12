package com.kevin.legion.games

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log

/**
 * What a launch attempt did, in terms the UI can say in words. Only [Started] may be reported as
 * opened. [target] is the emulator's name, or null for a native Android game (no middleman).
 */
sealed interface LaunchOutcome {
    val title: String
    val target: String?

    /** `startActivity` returned without throwing. That is all this knows: the emulator may still refuse the file. */
    data class Started(override val title: String, override val target: String?) : LaunchOutcome

    /** The emulator, or the native game itself, is not installed. */
    data class Missing(override val title: String, override val target: String?) : LaunchOutcome

    data class Failed(override val title: String, override val target: String?, val reason: String) : LaunchOutcome
}

/** The words for an outcome. "Opening" only follows a `startActivity` that returned. */
fun LaunchOutcome.message(): String = when (this) {
    is LaunchOutcome.Started -> if (target == null) "Opening $title" else "Opening $title in $target"
    is LaunchOutcome.Missing ->
        if (target == null) {
            "$title isn't installed any more, so it can't start."
        } else {
            "$target isn't installed, so $title can't start."
        }
    is LaunchOutcome.Failed -> "$title didn't start: $reason"
}

/**
 * Starts a game in its emulator, or a native Android game directly. The seams are what a test
 * fakes: [isInstalled] (package visibility is declared in the manifest), [start] (the real
 * `startActivity`), and [startApp] (launch intent for a package; throws if there is none).
 */
@Suppress("TooGenericExceptionCaught", "SwallowedException") // failures are said in words
class GameLauncher(
    val isInstalled: (Emulator) -> Boolean,
    private val start: (LaunchSpec) -> Unit,
    private val startApp: (String) -> Boolean = { false },
) {
    fun launch(game: Game, documentUri: String): LaunchOutcome {
        val emulator = game.system.emulator
        if (!isInstalled(emulator)) return LaunchOutcome.Missing(game.title, emulator.label)
        val spec = LaunchSpecs.forGame(game, documentUri)
        return try {
            start(spec.getOrThrow())
            LaunchOutcome.Started(game.title, emulator.label)
        } catch (e: Exception) {
            // A spec that could not be built lands here too: nothing started either way.
            Log.w(TAG, "launch of ${game.fileName} threw: ${e.message}")
            LaunchOutcome.Failed(game.title, emulator.label, e.message ?: "${emulator.label} refused to open it")
        }
    }

    /** [startApp] returns false when the package has no launch intent (uninstalled or hidden). */
    fun launchApp(game: AndroidGame): LaunchOutcome = try {
        if (startApp(game.packageName)) {
            LaunchOutcome.Started(game.label, null)
        } else {
            LaunchOutcome.Missing(game.label, null)
        }
    } catch (e: Exception) {
        Log.w(TAG, "launch of ${game.packageName} threw: ${e.message}")
        LaunchOutcome.Failed(game.label, null, e.message ?: "Android refused to open it")
    }

    companion object {
        private const val TAG = "GameLauncher"

        fun forContext(context: Context): GameLauncher {
            val app = context.applicationContext
            return GameLauncher(
                isInstalled = { emulator ->
                    try {
                        app.packageManager.getPackageInfo(emulator.packageName, 0)
                        true
                    } catch (@Suppress("SwallowedException") e: PackageManager.NameNotFoundException) {
                        // Not installed is the answer, not an error.
                        false
                    }
                },
                start = { spec -> app.startActivity(toIntent(spec)) },
                startApp = { pkg ->
                    val intent = app.packageManager.getLaunchIntentForPackage(pkg)
                    intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { app.startActivity(it) } != null
                },
            )
        }

        /** Real `Intent` for a [LaunchSpec]. NEW_TASK is always set: this starts from the application context. */
        fun toIntent(spec: LaunchSpec): Intent {
            val intent = Intent()
            spec.action?.let { intent.action = it }
            if (spec.className != null) {
                intent.component = ComponentName(spec.packageName, spec.className)
            } else {
                intent.setPackage(spec.packageName)
            }
            spec.dataUri?.let { intent.data = Uri.parse(it) }
            spec.extras.forEach { (key, value) -> intent.putExtra(key, value) }
            // A read grant covers only the intent data and ClipData, never a string extra, so
            // melonDS's `uri` extra is also carried as ClipData for the grant to reach it.
            if (spec.grantRead && spec.dataUri == null) {
                spec.extras["uri"]?.let { intent.clipData = ClipData.newRawUri("rom", Uri.parse(it)) }
            }
            var flags = Intent.FLAG_ACTIVITY_NEW_TASK
            if (spec.clearTask) flags = flags or Intent.FLAG_ACTIVITY_CLEAR_TASK
            if (spec.grantRead) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
            intent.addFlags(flags)
            return intent
        }
    }
}
