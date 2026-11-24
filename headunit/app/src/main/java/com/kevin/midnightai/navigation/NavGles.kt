package com.kevin.midnightai.navigation

import android.content.SharedPreferences
import android.opengl.EGL14
import android.opengl.EGLExt

/**
 * Whether this unit can draw the map's OpenGL ES 3 surface, and the words for when it cannot.
 *
 * Mapbox Maps SDK v11 needs ES 3: its docs say "Devices that support OpenGL ES 3", and
 * `sdk-base`'s manifest declares `<uses-feature android:glEsVersion="0x00030000">`
 * (docs.mapbox.com/android/maps/guides, and the v11.0.0 tag, both read 2026-11-23). That is not
 * negotiable inside the SDK, so the only question this file answers is whether the unit really
 * lacks ES 3.
 *
 * **Two different facts, and the gate used to read only the weaker one.** Until 2026-11-23 the
 * check was `ActivityManager.deviceConfigurationInfo.reqGlEsVersion` alone. That number is what the
 * unit SAYS about itself - it comes from the platform's `ro.opengles.version` build property and the
 * system feature list, set by whoever assembled the ROM. A cheap double-DIN board is exactly the
 * place where that is left at ES 2.0 on hardware whose driver does ES 3.x, and then the map is
 * refused for a shortcoming the GPU does not have. So the declared value is now only the FAST yes:
 * when it does not say ES 3, [probeEs3Configs] asks EGL, which answers for the driver.
 *
 * It stays conservative in the other direction: a probe that cannot run leaves the map off, same as
 * before, because a GL stack that cannot be asked is not one to hand a native renderer.
 *
 * The probe MAKES an ES 3 context rather than counting configs that advertise the ES 3 bit: a
 * driver can advertise the bit and still fail `eglCreateContext`, and a wrong yes here costs a
 * native crash in the launcher - caught by [NavRuntime]'s guard, but only after it has happened
 * once. Creating and destroying one context is the call the renderer itself would make.
 */
internal object NavGles {

    const val GLES_3 = 0x30000

    /** How we know (or do not) that the map's surface can be drawn here. */
    enum class Es3 {
        /** The unit declares ES 3 or better. */
        DECLARED,

        /** It declares less, but EGL really made an ES 3 context: the declaration was wrong. */
        PROBED,

        /** EGL cannot make an ES 3 context. The map genuinely cannot run. */
        NO,

        /** The probe could not run at all. Treated as no. */
        UNKNOWN,
        ;

        val mapAllowed: Boolean get() = this == DECLARED || this == PROBED
    }

    /**
     * [declared] is `reqGlEsVersion` (0x30000 = ES 3.0). [es3Context] is whether EGL really made an
     * ES 3 context, or null when the probe could not run at all. Pure: this is the whole decision.
     */
    fun decide(declared: Int, es3Context: Boolean?): Es3 = when {
        declared >= GLES_3 -> Es3.DECLARED
        es3Context == null -> Es3.UNKNOWN
        es3Context -> Es3.PROBED
        else -> Es3.NO
    }

    /** "2.0" for 0x20000. The low half is the minor version. */
    fun version(declared: Int): String = "${declared shr 16}.${declared and 0xffff}"

    /**
     * Why the map is off, in words, or null when it may run. The version is named on purpose: the
     * old sentence said only "older than OpenGL ES 3", which told nobody what the unit reported.
     */
    fun words(declared: Int, decision: Es3): String? = when (decision) {
        Es3.DECLARED, Es3.PROBED -> null
        Es3.NO ->
            "its graphics driver cannot make an OpenGL ES 3 context, which the map needs " +
                "(it reports OpenGL ES ${version(declared)})"
        Es3.UNKNOWN ->
            "this unit reports OpenGL ES ${version(declared)} and its graphics check could not " +
                "run, so the map is kept off"
    }

    /**
     * True when this unit really makes an ES 3 context, false when it cannot, null when EGL could
     * not be asked at all. The context is destroyed immediately; no surface is ever made current,
     * so nothing this unit is drawing is disturbed. A pbuffer config, because a context needs no
     * window.
     *
     * Deliberately does NOT call `eglTerminate`: the default display is the one HWUI draws the
     * launcher with, and terminating it under the render thread is not ours to do. `eglInitialize`
     * on an already-initialised display is a no-op that returns true.
     */
    fun probeEs3Context(): Boolean? = runCatching {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == null || display == EGL14.EGL_NO_DISPLAY) return@runCatching null
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return@runCatching null
        val attrs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val found = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, found, 0)) return@runCatching null
        if (found[0] == 0 || configs[0] == null) return@runCatching false
        val context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
        )
        if (context == null || context == EGL14.EGL_NO_CONTEXT) return@runCatching false
        EGL14.eglDestroyContext(display, context)
        true
    }.getOrNull()
}

/**
 * The crash guard around [NavGles.probeEs3Context] (review F2, 2026-11-23).
 *
 * `runCatching` stops JVM throws only, and `eglCreateContext` is native code inside a vendor
 * driver. The probe runs at the first NAV open, which on a unit that declares less than ES 3 is
 * effectively app start, and the map's own guard is armed around the MapView, not around this. A
 * native fault here would therefore take the launcher down every time, on a unit with no ADB and no
 * way back.
 *
 * So: a flag is committed before the probe and removed after. A flag found set means the last probe
 * did not come back, and the map stays off without asking again. The same TRY MAPS AGAIN that
 * clears the map guard clears this one, so the driver is never permanently stuck on one bad start.
 */
internal class GlProbeGuard(private val prefs: SharedPreferences) {

    /** True when a previous probe was still running when the process died. */
    fun crashedBefore(): Boolean = prefs.getBoolean(KEY, false)

    /** Runs [probe] with the flag armed, and clears it however the probe ends. */
    fun <T> guarded(probe: () -> T): T {
        prefs.edit().putBoolean(KEY, true).commit()
        return try {
            probe()
        } finally {
            clear()
        }
    }

    fun clear() {
        prefs.edit().remove(KEY).commit()
    }

    private companion object {
        const val KEY = "gl_probing"
    }
}

/**
 * The one way the native EGL probe may be reached (review A1, 2026-11-24): at most once, only when
 * the answer is needed, and only inside [guard].
 *
 * Three rules, each closing a hole the first cut of the F2 guard left open:
 *
 * - **Only when needed.** [NavGles.decide] answers `DECLARED` off [declared] alone and never looks
 *   at the probe, so on a unit declaring ES 3 the probe was pure waste - and a native fault there
 *   would have set a crash flag that nothing ever reads, leaving that unit no loop protection at
 *   all. Returns null without probing.
 * - **Only inside the guard.** A null [guard] (before `NavRuntime.init`) means no probe at all. It
 *   used to mean an unguarded one, which is the exact call F2 exists to wrap, on a unit with no ADB.
 * - **Never twice.** The old `glProbe?.guarded { probe() } ?: probe()` re-ran the probe unguarded
 *   whenever the guarded one returned NULL, which is what the probe answers on all three of its EGL
 *   early returns. A unit whose EGL is sick enough to answer null is the likeliest to fault on the
 *   retry.
 *
 * Null therefore covers both "could not be asked" and "was not asked", and [NavGles.decide] maps it
 * to `UNKNOWN`, which keeps the map off and says so. That is the conservative direction [NavGles]
 * already documents.
 */
internal fun probeEs3Guarded(
    declared: Int,
    guard: GlProbeGuard?,
    probe: () -> Boolean? = { NavGles.probeEs3Context() },
): Boolean? {
    if (declared >= NavGles.GLES_3) return null
    return guard?.guarded(probe)
}
