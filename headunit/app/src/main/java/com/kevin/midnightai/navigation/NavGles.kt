package com.kevin.midnightai.navigation

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
 */
internal object NavGles {

    const val GLES_3 = 0x30000

    /** How we know (or do not) that the map's surface can be drawn here. */
    enum class Es3 {
        /** The unit declares ES 3 or better. */
        DECLARED,

        /** It declares less, but EGL offers an ES 3 config: the declaration was wrong. */
        PROBED,

        /** EGL offers no ES 3 config. The map genuinely cannot run. */
        NO,

        /** The probe could not run at all. Treated as no. */
        UNKNOWN,
        ;

        val mapAllowed: Boolean get() = this == DECLARED || this == PROBED
    }

    /**
     * [declared] is `reqGlEsVersion` (0x30000 = ES 3.0). [es3Configs] is how many EGL configs can be
     * rendered with ES 3, or null when the probe could not run. Pure: this is the whole decision.
     */
    fun decide(declared: Int, es3Configs: Int?): Es3 = when {
        declared >= GLES_3 -> Es3.DECLARED
        es3Configs == null -> Es3.UNKNOWN
        es3Configs > 0 -> Es3.PROBED
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
            "its graphics chip offers no OpenGL ES 3 drawing surface, which the map needs " +
                "(it reports OpenGL ES ${version(declared)})"
        Es3.UNKNOWN ->
            "this unit reports OpenGL ES ${version(declared)} and its graphics check could not " +
                "run, so the map is kept off"
    }

    /**
     * EGL configs this unit can render with ES 3, or null if EGL could not be asked. Counts only,
     * no config is chosen and no context is made.
     *
     * Deliberately does NOT call `eglTerminate`: the default display is the one HWUI draws the
     * launcher with, and terminating it under the render thread is not ours to do. `eglInitialize`
     * on an already-initialised display is a no-op that returns true.
     */
    fun probeEs3Configs(): Int? = runCatching {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == null || display == EGL14.EGL_NO_DISPLAY) return@runCatching null
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return@runCatching null
        val attrs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE,
        )
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attrs, 0, null, 0, 0, count, 0)) null else count[0]
    }.getOrNull()
}
