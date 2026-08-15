package com.kevin.legion.service

import android.content.Context
import com.kevin.legion.ai.OnboardingState

/**
 * The idle/mute/call/onboarding gate [AriaForegroundService.speakProactive] applies before handing
 * a line to [ProactiveBus] - pulled out into a standalone object so a caller with no `Service`
 * instance can reuse the exact same "never talk over the driver, never talk during onboarding or a
 * phone call, respect the mute toggle" rule instead of re-deriving it.
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
        // Stay silent until first-run onboarding is done - see speakProactive's own comment for why.
        if (!OnboardingState.isComplete(context)) return
        if (ConversationState.isBusy) return
        // Don't talk over a phone call - the call owns the speakers.
        if (TelephonyController.isInCall) return
        // Mute toggle: silences unsolicited chatter only, same scope as speakProactive's.
        if (ProactivePreferences.muted.value) return
        ProactiveBus.requestSpeak(prompt)
    }
}
