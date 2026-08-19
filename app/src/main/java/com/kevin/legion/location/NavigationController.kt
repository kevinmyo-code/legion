package com.kevin.legion.location

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.kevin.legion.MidnightEvents

/**
 * Hands the driver's destination to whatever map app is installed, by intent. LEGION never
 * draws a map itself - embedded navigation and the Mapbox stack were dropped in the pivot
 * (CLAUDE.md sec 2), so this fires `google.navigation:` / `geo:` at an app the driver already
 * has and reports exactly what happened.
 *
 * Built for `.scratch/drive-test-2026-08-18/issues/03-no-navigation-capability.md`: the
 * assistant told Kevin on a real drive that it had opened Maps when no navigation capability
 * existed anywhere in the app. The load-bearing requirement of that ticket, and the reason
 * every path here returns an [Outcome] rather than Unit, is that **the tool result must
 * reflect whether [Context.startActivity] actually ran.** A launcher that reports success
 * unconditionally reproduces the original bug behind a tool call instead of in front of one.
 *
 * Package visibility (API 30+) is the silent failure mode this guards: without the `<queries>`
 * entries in `AndroidManifest.xml`, [Intent.resolveActivity] returns null and the launch does
 * nothing at all. That resolves to [Outcome.NoMapApp] here, which is spoken, not swallowed.
 */
object NavigationController {

    /** What the driver asked for: turn-by-turn guidance, or just show me where it is. */
    enum class Mode { NAVIGATE, SHOW }

    sealed interface Outcome {
        /** The requested intent was accepted by a map app. */
        data object Launched : Outcome

        /**
         * Turn-by-turn was asked for, nothing could serve `google.navigation:` (in practice:
         * Google Maps is absent), but a plain `geo:` map pin did launch. A real, lesser thing
         * happened and the driver has to be told which one, hence a distinct outcome rather
         * than folding it into [Launched].
         */
        data object LaunchedAsMapPin : Outcome

        /** Nothing on the device could handle the intent. Nothing was launched. */
        data object NoMapApp : Outcome

        /** The destination was empty, so there was nothing to launch. */
        data object BlankDestination : Outcome

        /** The launch was attempted and threw. Nothing is running. */
        data class Failed(val reason: String) : Outcome
    }

    /**
     * The intent URI for [destination]. Pure and `internal` so
     * [com.kevin.legion.location.NavigationControllerTest] can exercise it on a plain JVM with
     * no Android framework, which is also why the encoding below is hand-rolled rather than
     * [Uri.encode].
     */
    internal fun uriFor(destination: String, mode: Mode): String {
        val q = percentEncode(destination.trim())
        return when (mode) {
            // Google Maps' documented turn-by-turn entry point. Only Maps answers this scheme.
            Mode.NAVIGATE -> "google.navigation:q=$q"
            // The generic map-pin scheme any map app may answer. 0,0 is the documented
            // placeholder for "no coordinates, use the query".
            Mode.SHOW -> "geo:0,0?q=$q"
        }
    }

    /**
     * Percent-encodes everything outside RFC 3986's unreserved set, UTF-8, uppercase hex.
     * `java.net.URLEncoder` is deliberately not used: it is form encoding and turns a space
     * into `+`, which a `geo:` query carries through literally.
     */
    internal fun percentEncode(raw: String): String {
        val unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"
        val out = StringBuilder(raw.length)
        for (b in raw.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt().toChar()
            if (c in unreserved) out.append(c)
            else out.append('%').append("%02X".format(b.toInt() and 0xFF))
        }
        return out.toString()
    }

    /**
     * What the driver hears. Pure, and never optimistic: only [Outcome.Launched] is allowed to
     * be silent (the map is visibly up, so a spoken confirmation is noise). Every other outcome
     * says in words what actually happened.
     */
    internal fun message(outcome: Outcome, destination: String): String? = when (outcome) {
        Outcome.Launched -> null
        Outcome.LaunchedAsMapPin ->
            "I couldn't start turn-by-turn - Google Maps isn't here - so I've put $destination " +
                "on the map instead. You'll have to start the directions yourself."
        Outcome.NoMapApp ->
            "I couldn't open a map - there's no map app I can reach on this phone, so nothing " +
                "opened. Nothing's navigating to $destination."
        Outcome.BlankDestination ->
            "I didn't catch where you want to go, so I haven't opened anything."
        is Outcome.Failed ->
            "I couldn't open the map - nothing opened. Nothing's navigating to $destination."
    }

    /** Whether the outcome represents something actually happening on screen. */
    internal fun succeeded(outcome: Outcome): Boolean =
        outcome is Outcome.Launched || outcome is Outcome.LaunchedAsMapPin

    /**
     * Launches [destination]. Called from [com.kevin.legion.service.AriaForegroundService]'s
     * tool dispatch, which has **no Activity context**, so [Intent.FLAG_ACTIVITY_NEW_TASK] is
     * mandatory - without it the launch throws rather than no-ops.
     */
    fun launch(context: Context, destination: String, mode: Mode): Outcome {
        val target = destination.trim()
        if (target.isEmpty()) return Outcome.BlankDestination

        val outcome = attempt(context, target, mode)
        // Turn-by-turn asked for and nothing served it - either nothing answers the
        // `google.navigation:` scheme, or the launch was refused (a disabled component, a
        // restricted profile). A map pin is strictly better than silence, but it is a different
        // thing and [message] says so.
        if (mode == Mode.NAVIGATE && (outcome == Outcome.NoMapApp || outcome is Outcome.Failed)) {
            val fallback = attempt(context, target, Mode.SHOW)
            // The fallback's own outcome is returned as-is when it fails: a launch that threw
            // must not be relabelled "no map app", or the log names the wrong cause.
            return if (fallback == Outcome.Launched) Outcome.LaunchedAsMapPin else fallback
        }
        return outcome
    }

    private fun attempt(context: Context, target: String, mode: Mode): Outcome {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriFor(target, mode)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Null here is the package-visibility failure as often as it is a genuinely missing
        // map app. Either way nothing would launch, so it is reported, never assumed away.
        if (intent.resolveActivity(context.packageManager) == null) {
            MidnightEvents.navigationLaunch(mode.name, "NoMapApp")
            return Outcome.NoMapApp
        }
        return try {
            context.startActivity(intent)
            MidnightEvents.navigationLaunch(mode.name, "Launched")
            Outcome.Launched
        } catch (e: Exception) {
            MidnightEvents.navigationLaunch(mode.name, "Failed:${e.javaClass.simpleName}")
            Outcome.Failed(e.javaClass.simpleName)
        }
    }
}
