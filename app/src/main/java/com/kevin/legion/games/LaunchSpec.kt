package com.kevin.legion.games

/**
 * Pure description of the intent that starts a game, so the per-emulator extras are unit-testable
 * without Android. [GameLauncher] turns it into a real `Intent`.
 *
 * [clearTask] is the equivalent of `am start -S` in ticket 02's command: without it, a RetroArch
 * already running last game's core would simply be brought to the front instead of loading this one.
 */
data class LaunchSpec(
    val action: String?,
    val packageName: String,
    val className: String?,
    val dataUri: String?,
    val extras: Map<String, String>,
    val clearTask: Boolean,
    val grantRead: Boolean,
)


object LaunchSpecs {
    const val RETROARCH_ACTIVITY = "com.retroarch.browser.retroactivity.RetroActivityFuture"
    const val RETROARCH_CONFIG = "/storage/emulated/0/Android/data/com.retroarch.aarch64/files/retroarch.cfg"
    const val MELONDS_ACTIVITY = "me.magnum.melonds.ui.emulator.EmulatorActivity"
    const val MELONDS_ACTION = "me.magnum.melonds.LAUNCH_ROM"
    const val ACTION_VIEW = "android.intent.action.VIEW"

    /**
     * @param documentUri the SAF content URI of the ROM document, used by the two emulators that take
     * one (melonDS, PPSSPP); RetroArch is given a path derived from the document id instead.
     *
     * RetroArch: on-device verified for psx (ticket 02). melonDS's `uri` extra name is from
     * Daijisho's launch template and is UNVERIFIED on the phone. PPSSPP: its VIEW filter exists
     * (dumpsys) but resolving a VIEW of a real file was never proven. Both ship as a first attempt.
     */
    fun forGame(game: Game, documentUri: String): Result<LaunchSpec> = when (game.system.emulator) {
        Emulator.RETROARCH -> retroArch(game)
        Emulator.MELONDS -> Result.success(
            LaunchSpec(
                action = MELONDS_ACTION,
                packageName = Emulator.MELONDS.packageName,
                className = MELONDS_ACTIVITY,
                dataUri = null,
                extras = mapOf("uri" to documentUri),
                clearTask = false,
                grantRead = true,
            ),
        )
        Emulator.PPSSPP -> Result.success(
            LaunchSpec(
                action = ACTION_VIEW,
                packageName = Emulator.PPSSPP.packageName,
                className = null,
                dataUri = documentUri,
                extras = emptyMap(),
                clearTask = false,
                grantRead = true,
            ),
        )
    }

    private fun retroArch(game: Game): Result<LaunchSpec> {
        val core = game.system.core
        val path = RomPaths.filesystemPath(game.documentId)
        if (core == null || path == null) {
            val why = if (core == null) {
                "${game.system.displayName} has no RetroArch core"
            } else {
                "could not work out where ${game.fileName} is stored"
            }
            return Result.failure(IllegalStateException(why))
        }
        return Result.success(
            LaunchSpec(
                action = null,
                packageName = Emulator.RETROARCH.packageName,
                className = RETROARCH_ACTIVITY,
                dataUri = null,
                extras = mapOf("ROM" to path, "LIBRETRO" to core, "CONFIGFILE" to RETROARCH_CONFIG),
                clearTask = true,
                grantRead = false,
            ),
        )
    }
}
