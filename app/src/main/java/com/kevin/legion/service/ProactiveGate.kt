package com.kevin.legion.service

import android.content.Context

/**
 * The idle/mute/call/onboarding gate for a caller that has no `Service` instance to
 * call [AriaForegroundService] on directly - pulled out into a standalone object so
 * that caller can reuse the exact same "never talk over the driver, never talk during
 * onboarding or a phone call, respect the mute toggle" rule instead of re-deriving it.
 *
 * **The gate itself now lives in [ProactiveBus.speakIfAllowed]** (`.scratch/
 * proactive-mode/issues/01-one-gate-not-three.md`, 2026-08-18) - this object is kept
 * as a thin delegate purely so the 11 existing raise sites (`AriaForegroundService.
 * speakProactive` and [ReminderAlarmReceiver]) don't have to churn their call sites.
 * Before this, the same four checks were duplicated inline here AND hand-rolled again
 * at [AmbientListener] and [TelephonyController], which is exactly the shape that let
 * two of those three copies quietly diverge (neither checked onboarding). Putting the
 * gate on the bus instead of here means the raw emit can go private and every caller,
 * present or future, is forced through it - this object stops being load-bearing and
 * becomes a compatibility shim.
 *
 * The concrete reason this exists: `.scratch/notes-lists-calendar/issues/12-what-a-fired-reminder-
 * does.md`'s "Alfred speaks it aloud at a turn boundary if a live session is active" is implemented
 * by [com.kevin.legion.service.ReminderAlarmReceiver], a `BroadcastReceiver` fired by `AlarmManager`
 * that may run with the service NOT alive at all (an inexact alarm can legitimately fire after the
 * process was killed). [AriaForegroundService.speakProactive] now just delegates here so its own
 * existing callers (the opener, drive-monitor chatter, arrival reminders) are unaffected.
 */
object ProactiveGate {
    fun speakIfIdle(context: Context, prompt: String) {
        ProactiveBus.speakIfAllowed(context, prompt)
    }
}
