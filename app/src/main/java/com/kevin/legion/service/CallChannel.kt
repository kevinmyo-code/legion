package com.kevin.legion.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * The incoming-call notification channel (command-center ticket 05, ADR 0035's founding case:
 * `answer_call`/`decline_call` had zero UI callers).
 *
 * Its own channel rather than riding [ReminderChannel]'s or [ProactiveDelivery]'s: those two exist
 * for a fired reminder and an unsolicited nudge respectively, and a ringing phone is neither -
 * conflating them would mean muting one silently mutes the other, which is exactly the kind of
 * cross-talk a per-purpose channel is meant to prevent (same reasoning `ReminderChannel`'s own doc
 * comment gives for not reusing the two `IMPORTANCE_LOW` service channels).
 *
 * `IMPORTANCE_HIGH`, same as [com.kevin.legion.notes.ReminderChannel]: a ringing call is by
 * definition something the user wants to be interrupted for, so this is heads-up-plus-sound, not
 * the silent posture the two foreground-service channels use.
 */
object CallChannel {
    const val CHANNEL_ID = "incoming_call_channel"

    /** Idempotent, same pattern as every other channel in this codebase - a repeat call with an
     * unchanged channel is a no-op. */
    fun ensureCreated(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Incoming calls",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "The ring-time notification with Answer and Decline, while a call rings."
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
