package com.kevin.midnightai.media

import android.content.Context

/**
 * HOW a volume press is delivered to Android, and the whole point of the 2026-11-23 change.
 *
 * The new evidence, from the car (2026-11-23): a press opens the system's own volume slider and
 * moves the bar, and the loudness does not change; touching or dragging that same bar by hand DOES
 * change the loudness. So the app's write reaches Android's STREAM_MUSIC index and the system UI,
 * and the amp still does not follow. That kills the premise both earlier fixes shared - that the
 * index and the loudness are the same thing on this unit - and it moves the question from "which
 * flags" to "which CALL", because the vendor's volume path hangs off something the slider's own
 * touch does and `setStreamVolume` from this app does not.
 *
 * [STREAM] is what every press used before today and is exactly what Kevin watched fail, so it is
 * no longer the default. [VolumePathLogic.DEFAULT] is [SUGGESTED_STREAM], the call the framework
 * itself makes for a hardware volume key it has to handle.
 *
 * **Nothing here can hear the car.** Every path moves the index, so the app cannot tell which one
 * reaches the amp; only Kevin in the car can. That is why each path is also a button in the Volume
 * check panel, and why the panel lets him make the one that works the one the keys use.
 *
 * [readsBackAtOnce] is false for the two paths that hand the press to another process: the index
 * read straight after says nothing, so such a press claims nothing and never falls back.
 */
enum class VolumePath(
    val label: String,
    val what: String,
    val readsBackAtOnce: Boolean,
) {
    SUGGESTED_STREAM(
        "LIKE A HARDWARE KEY",
        "adjustSuggestedStreamVolume(dir, STREAM_MUSIC, FLAG_SHOW_UI): the call the framework makes " +
            "for a volume key it has to handle itself. The most system-like path this app can reach.",
        readsBackAtOnce = true,
    ),
    STREAM(
        "STREAM MUSIC (TODAY'S)",
        "adjustStreamVolume(STREAM_MUSIC, dir, FLAG_SHOW_UI). What every press used until 2026-11-23, " +
            "and what moved the bar without moving the sound.",
        readsBackAtOnce = true,
    ),
    MASTER(
        "NO STREAM NAMED",
        "adjustVolume(dir, FLAG_SHOW_UI): names no stream, so AudioService picks the one it believes " +
            "is in front. A vendor ROM that routes one stream to the amp may only accept this one.",
        readsBackAtOnce = true,
    ),
    MEDIA_SESSION(
        "THE PLAYER'S OWN",
        "MediaController.adjustVolume(dir, FLAG_SHOW_UI) on the player holding the speakers. The only " +
            "path that reaches a session's own volume provider, which is where a remote-volume " +
            "player's loudness lives. Nothing can be read back at once.",
        readsBackAtOnce = false,
    ),
    MEDIA_KEY(
        "VOLUME KEY EVENT",
        "dispatchMediaKeyEvent(KEYCODE_VOLUME_UP/DOWN). Android may drop it, because a volume key is " +
            "not a media key; that it was dropped is itself worth knowing. Nothing can be read back at once.",
        readsBackAtOnce = false,
    ),
}

/**
 * Which path a press takes, and when a press may try a second one. Pure, so every rule below is
 * JVM-tested; the Android calls live in [AndroidVolumeNudger].
 */
object VolumePathLogic {

    /** Most system-like first. The panel shows them in this order. */
    val ORDER: List<VolumePath> = listOf(
        VolumePath.SUGGESTED_STREAM,
        VolumePath.STREAM,
        VolumePath.MASTER,
        VolumePath.MEDIA_SESSION,
        VolumePath.MEDIA_KEY,
    )

    /** Used until the driver picks another in the Volume check panel. */
    val DEFAULT: VolumePath = VolumePath.SUGGESTED_STREAM

    /** The stored pick by name, or [DEFAULT] when nothing is stored or the name is not one of ours. */
    fun resolve(chosen: String?): VolumePath = ORDER.firstOrNull { it.name == chosen } ?: DEFAULT

    /**
     * The one path a failed [VolumePath.SUGGESTED_STREAM] press falls back to, so a ROM that does
     * not implement the newer call still moves the volume. One hop only, and only away from the
     * default: a longer chain would be guessing, because an index that moved proves nothing about
     * the amp.
     */
    fun fallbackFor(path: VolumePath): VolumePath? = if (path == DEFAULT) VolumePath.STREAM else null

    /**
     * Whether to try [fallbackFor] after this press. Only a call Android refused or an index that
     * did not budge qualifies, and never when:
     * - the driver picked this path by hand (his pick is the answer this change exists to keep);
     * - the path hands the press to another process, so "it did not move" cannot be read;
     * - the output's volume is fixed, where every write is a documented no-op and the words say so;
     * - the stream is already at the end the driver pushed towards, which is ordinary.
     */
    @Suppress("LongParameterList")
    fun shouldFallBack(
        path: VolumePath,
        chosenByDriver: Boolean,
        threw: Boolean,
        moved: Boolean,
        atEnd: Boolean,
        volumeFixed: Boolean,
    ): Boolean {
        if (chosenByDriver || fallbackFor(path) == null) return false
        // A call Android refused delivered nothing, whatever the output is like.
        if (threw) return true
        if (!path.readsBackAtOnce) return false
        if (volumeFixed || atEnd) return false
        return !moved
    }
}

/**
 * The driver's pick, from the Volume check panel. Its own tiny store rather than
 * [com.kevin.midnightai.ai.CompanionProfile]: this belongs to the UNIT's wiring, not to a car or a
 * companion, so it must not move with a car profile.
 */
object VolumePathStore {
    private const val PREFS = "volume_path"
    private const val KEY = "chosen"

    /** The path presses take: the driver's pick if he made one, else [VolumePathLogic.DEFAULT]. */
    fun path(context: Context): VolumePath = VolumePathLogic.resolve(chosenName(context))

    /** True while the path in use is the driver's own pick, which is never second-guessed. */
    fun chosenByDriver(context: Context): Boolean = chosenName(context) != null

    fun choose(context: Context, path: VolumePath) {
        prefs(context).edit().putString(KEY, path.name).apply()
    }

    /** Back to [VolumePathLogic.DEFAULT], and back to being second-guessed. */
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).apply()
    }

    private fun chosenName(context: Context): String? =
        prefs(context).getString(KEY, null)?.takeIf { name -> VolumePathLogic.ORDER.any { it.name == name } }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
