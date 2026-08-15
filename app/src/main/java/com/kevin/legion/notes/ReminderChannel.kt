package com.kevin.legion.notes

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * The fired-reminder notification channel - `.scratch/notes-lists-calendar/issues/12-*`'s Answer:
 * "its own channel, at `IMPORTANCE_DEFAULT` or higher. The two channels that already exist
 * (`AriaForegroundService.kt:841`, `LedgerIngestService.kt:148`) are both `IMPORTANCE_LOW`, which
 * makes no sound - a silent reminder is not a reminder." Uses `IMPORTANCE_HIGH` (heads-up + sound)
 * rather than the minimum `DEFAULT`, since a reminder is by definition something the driver asked
 * to be interrupted for.
 */
object ReminderChannel {
    const val CHANNEL_ID = "reminders_channel"

    /** Idempotent - `createNotificationChannel` with an unchanged channel is a no-op, same pattern
     * as [com.kevin.legion.service.AriaForegroundService]'s `createNotificationChannel`. */
    fun ensureCreated(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Reminders",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Scheduled reminders and events. Makes a sound - unlike this app's other, silent channels."
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
