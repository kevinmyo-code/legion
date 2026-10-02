package com.kevin.legion.quiet

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.service.notification.Condition
import android.service.notification.ZenPolicy
import androidx.annotation.RequiresApi
import com.kevin.legion.ui.MainActivity

/**
 * One switch for "phone on vibrate, notifications silent" (2026-09-27). Kevin: *"i want a quick
 * toggle button to mute all notifications and put the phone on vibrate only."*
 *
 * On: the ringer goes to vibrate, and LEGION's OWN Do Not Disturb mode comes on. It lets calls
 * (from anyone) and alarms through, so they vibrate, and silences everything else: no sound, no
 * buzz, but notifications still land in the shade, so nothing is lost. Off: the ringer goes back to
 * whatever it was before, not to a default.
 *
 * **Why a separate mode instead of the system DND switch.** `setNotificationPolicy` rewrites the
 * user's own DND policy, and a toggle that quietly clobbers someone's DND setup is worse than no
 * toggle. An [AutomaticZenRule] is LEGION's own mode with its own policy. Turning it on and off
 * never touches his.
 */
object QuietMode {

    sealed interface Result {
        data object On : Result
        data object Off : Result
        /** Nothing changed, and [message] says why in words. */
        data class Refused(val message: String) : Result
    }

    private const val PREFS = "quiet_mode"
    private const val KEY_ON = "on"
    private const val KEY_PREVIOUS_RINGER = "previous_ringer"
    private const val KEY_RULE_ID = "rule_id"
    private const val KEY_VOLUME_PREFIX = "volume_"

    /**
     * Every stream Quiet puts back on "off" (2026-09-27). Kevin: *"when i toggle it off, the phone
     * stays quiet, like everything is at 0 volume. i want it restored to maybe 50% for all volumes."*
     * Switching the ringer to vibrate makes Android zero the ring and notification volumes, and
     * restoring the ringer mode never restored them. Alarm is included deliberately: an alarm left
     * at 0 after Quiet is the one failure here that costs more than annoyance.
     */
    internal val RESTORED_STREAMS = intArrayOf(
        AudioManager.STREAM_RING,
        AudioManager.STREAM_NOTIFICATION,
        AudioManager.STREAM_MUSIC,
        AudioManager.STREAM_ALARM,
        AudioManager.STREAM_SYSTEM,
    )
    private val CONDITION: Uri = Uri.parse("condition://com.kevin.legion/quiet")

    const val NEEDS_ACCESS =
        "Quiet needs Do Not Disturb access for LEGION. Nothing was changed. Grant it in the " +
            "screen that just opened, then tap Quiet again."
    const val NEEDS_ANDROID_10 = "Quiet needs Android 10 or later. Nothing was changed."

    fun isOn(context: Context): Boolean = prefs(context).getBoolean(KEY_ON, false)

    fun toggle(context: Context): Result = if (isOn(context)) disable(context) else enable(context)

    fun enable(context: Context): Result {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return Result.Refused(NEEDS_ANDROID_10)
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) return Result.Refused(NEEDS_ACCESS)
        val audio = context.getSystemService(AudioManager::class.java)
        val p = prefs(context)
        // Remember what to go back to, but only on the first switch-on. A second "on" must not
        // overwrite the saved ringer with VIBRATE, or "off" would restore vibrate forever.
        val previous = QuietState.ringerToRemember(isOn = p.getBoolean(KEY_ON, false), current = audio.ringerMode,
            alreadySaved = p.getInt(KEY_PREVIOUS_RINGER, -1))
        p.edit().putInt(KEY_PREVIOUS_RINGER, previous).apply()
        // Remember every volume before vibrate zeroes some of them, on the first "on" only, for
        // the same reason as the ringer: a second "on" would remember the zeros.
        if (!p.getBoolean(KEY_ON, false)) {
            val e = p.edit()
            for (stream in RESTORED_STREAMS) e.putInt(KEY_VOLUME_PREFIX + stream, audio.getStreamVolume(stream))
            e.apply()
        }
        audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        setRuleActive(context, nm, true)
        p.edit().putBoolean(KEY_ON, true).apply()
        return Result.On
    }

    fun disable(context: Context): Result {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return Result.Refused(NEEDS_ANDROID_10)
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) return Result.Refused(NEEDS_ACCESS)
        val p = prefs(context)
        setRuleActive(context, nm, false)
        val audio = context.getSystemService(AudioManager::class.java)
        audio.ringerMode = QuietState.ringerToRestore(p.getInt(KEY_PREVIOUS_RINGER, -1))
        // The ringer mode alone leaves the volumes vibrate zeroed. Put each back: what it was, or
        // half-way if it was at 0 or never recorded.
        val e = p.edit()
        for (stream in RESTORED_STREAMS) {
            val target = QuietState.volumeToRestore(
                saved = p.getInt(KEY_VOLUME_PREFIX + stream, -1),
                max = audio.getStreamMaxVolume(stream),
            )
            runCatching { audio.setStreamVolume(stream, target, 0) }
            e.remove(KEY_VOLUME_PREFIX + stream)
        }
        e.putBoolean(KEY_ON, false).remove(KEY_PREVIOUS_RINGER).apply()
        return Result.Off
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun setRuleActive(context: Context, nm: NotificationManager, active: Boolean) {
        val id = ruleId(context, nm)
        nm.setAutomaticZenRuleState(
            id,
            Condition(CONDITION, if (active) "Quiet on" else "Quiet off", if (active) Condition.STATE_TRUE else Condition.STATE_FALSE),
        )
    }

    /** LEGION's own DND mode, created once and reused. Recreated if the user deleted it. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun ruleId(context: Context, nm: NotificationManager): String {
        val p = prefs(context)
        p.getString(KEY_RULE_ID, null)?.let { if (nm.getAutomaticZenRule(it) != null) return it }
        val policy = ZenPolicy.Builder()
            .disallowAllSounds()
            .allowCalls(ZenPolicy.PEOPLE_TYPE_ANYONE)
            .allowAlarms(true)
            // Silent, not hidden: every notification still reaches the shade.
            .showAllVisualEffects()
            .build()
        val rule = AutomaticZenRule(
            "LEGION Quiet",
            null,
            ComponentName(context, MainActivity::class.java),
            CONDITION,
            policy,
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            true,
        )
        val id = nm.addAutomaticZenRule(rule)
        p.edit().putString(KEY_RULE_ID, id).apply()
        return id
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** The ringer bookkeeping, pure so it is testable. */
object QuietState {
    /** What to remember on switch-on: the current ringer the first time, the saved one after. */
    fun ringerToRemember(isOn: Boolean, current: Int, alreadySaved: Int): Int =
        if (isOn && alreadySaved >= 0) alreadySaved else current

    /** What to put back on switch-off. Never vibrate-by-default if nothing was saved: normal. */
    fun ringerToRestore(saved: Int): Int =
        if (saved >= 0) saved else AudioManager.RINGER_MODE_NORMAL

    /** A stream's volume on switch-off: what it was before Quiet, or half of its range if it was
     * at 0 or never recorded, so "off" never leaves the phone silent. Kevin asked for 50%. */
    fun volumeToRestore(saved: Int, max: Int): Int =
        if (saved > 0) saved.coerceAtMost(max) else ((max + 1) / 2).coerceAtLeast(1)
}
