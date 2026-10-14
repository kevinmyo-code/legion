package com.kevin.midnightai.ui

/**
 * The vendor activity the reverse-camera button should hand off to. The
 * camera itself is wired composite/RCA straight into the head unit's own
 * video input - there is no USB camera to open and no `/dev/video` for this
 * app to read, so all this app can ever do is jump to whatever app or
 * activity the vendor firmware uses to show that input. We cannot know that
 * package name in advance (it varies per head-unit vendor/ROM), so it is a
 * one-time configured target rather than a hardcoded intent.
 *
 * [className] is nullable because most vendor camera activities have no
 * launcher icon at all (they are reached only by a hardware reverse-gear
 * signal in the stock firmware) and so cannot be resolved from
 * `PackageManager.getLaunchIntentForPackage`. When null, the resolved intent
 * falls back to the package's own launch intent (main/launcher activity);
 * when set, it targets that exact `package/class` component.
 */
data class ReverseCameraTarget(
    val packageName: String,
    val className: String? = null,
)

/**
 * Serializes a [ReverseCameraTarget] to/from the single SharedPreferences
 * string [ui.ReverseCameraSettings] stores - "pkg" or "pkg/Class". Pulled out
 * as its own object (rather than inlined into the preferences reader) so the
 * round-trip and the malformed-input cases are unit-testable without a
 * Context.
 */
object ReverseCameraTargetCodec {
    fun encode(target: ReverseCameraTarget): String =
        if (target.className != null) "${target.packageName}/${target.className}" else target.packageName

    /**
     * Null for a blank/unset preference (the "nothing configured yet" case
     * the button must speak out loud) and for input with no package half at
     * all - a lone "/Foo" or an empty string names no app to launch.
     */
    fun decode(raw: String?): ReverseCameraTarget? {
        if (raw.isNullOrBlank()) return null
        val slash = raw.indexOf('/')
        if (slash < 0) return ReverseCameraTarget(packageName = raw)
        val pkg = raw.substring(0, slash)
        val cls = raw.substring(slash + 1)
        if (pkg.isBlank()) return null
        return ReverseCameraTarget(packageName = pkg, className = cls.ifBlank { null })
    }
}

/**
 * Every distinct sentence the reverse-camera button can end up saying, so the
 * three real failure shapes never collapse into the same "nothing happened"
 * silence CLAUDE.md sec 7 forbids for an outcome verb: a missing target, a
 * launch Android could not resolve to any activity
 * ([android.content.ActivityNotFoundException]), a launch the OS refused
 * ([SecurityException] - an exported=false activity, for instance), and a
 * launch that actually ran.
 */
sealed class ReverseCameraLaunchResult {
    /** No target has ever been configured - the button must say so, not just no-op. */
    object NotConfigured : ReverseCameraLaunchResult()

    /** startActivity returned normally. */
    object Launched : ReverseCameraLaunchResult()

    /** ActivityNotFoundException - the configured component/package does not resolve. */
    data class NotFound(val detail: String?) : ReverseCameraLaunchResult()

    /** SecurityException - the OS refused to start it (not exported, permission, etc). */
    data class Denied(val detail: String?) : ReverseCameraLaunchResult()

    /** Anything else that isn't one of the two known shapes above. */
    data class Failed(val detail: String?) : ReverseCameraLaunchResult()
}

/**
 * The decision half of the launch path, kept free of any Android startActivity
 * call so it is unit-testable: given a possibly-unconfigured target and a
 * caller-supplied attempt function (which does the real, un-testable
 * `context.startActivity` and reports back what happened), decides whether to
 * attempt at all and returns whatever the attempt reported.
 *
 * The actual mapping of [android.content.ActivityNotFoundException] /
 * [SecurityException] to [ReverseCameraLaunchResult.NotFound] /
 * [ReverseCameraLaunchResult.Denied] happens in the real caller
 * (`ui/ReverseCameraLauncher.kt`), not here - constructing those Android
 * exception types in a plain JUnit test (no Robolectric) is not reliable, so
 * this function's own tests exercise the decision logic by passing already-
 * classified [ReverseCameraLaunchResult] values through [attempt] directly.
 */
fun resolveReverseCameraLaunch(
    target: ReverseCameraTarget?,
    attempt: (ReverseCameraTarget) -> ReverseCameraLaunchResult,
): ReverseCameraLaunchResult {
    if (target == null) return ReverseCameraLaunchResult.NotConfigured
    return attempt(target)
}
