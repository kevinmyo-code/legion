package com.kevin.legion.location

import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.PlaceReminder

/**
 * Location-triggered reminders: the driver binds a reminder to a saved place
 * ("remind me to grab my gym bag when I get to the gym"), and it's surfaced on
 * arrival by the arrival monitor in [com.kevin.legion.service.AriaForegroundService].
 *
 * Place labels are folded onto the same canonical forms [PlaceController] uses
 * (home/work synonyms), so a reminder for "the office" matches arrival at the
 * place saved as "work".
 */
object ReminderController {

    /** Stores a reminder for [placeLabel]; returns a short spoken acknowledgement. */
    suspend fun add(context: Context, placeLabel: String, text: String): String {
        val label = normalizeLabel(placeLabel)
        val body = text.trim()
        if (label.isBlank() || body.isBlank()) {
            return "I need both a place and what to remind you about."
        }
        CarDatabase.getDatabase(context).placeReminderDao().insert(
            PlaceReminder(placeLabel = label, text = body, createdAt = System.currentTimeMillis())
        )
        return "Got it. I'll remind you to $body when you reach ${displayLabel(label)}."
    }

    /** Active (not-yet-done) reminders bound to [label]. */
    suspend fun activeFor(context: Context, label: String): List<PlaceReminder> =
        CarDatabase.getDatabase(context).placeReminderDao().activeForPlace(normalizeLabel(label))

    /** All active reminders across every place. */
    suspend fun allActive(context: Context): List<PlaceReminder> =
        CarDatabase.getDatabase(context).placeReminderDao().allActive()

    /** Marks a reminder acknowledged so it stops surfacing. */
    suspend fun markDone(context: Context, id: Long) {
        CarDatabase.getDatabase(context).placeReminderDao().markDone(id)
    }

    private fun displayLabel(label: String): String =
        if (label == "home" || label == "work") label else "\"$label\""

    /** Folds spoken place words onto canonical labels, mirroring PlaceController. */
    private fun normalizeLabel(raw: String): String {
        var s = raw.lowercase()
            .replace(Regex("\\b(location|place|spot|address)\\b"), " ")
            .replace(Regex("[.!?,]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        s = s.removePrefix("the ").removePrefix("my ").removePrefix("a ").trim()
        return when (s) {
            "work", "office", "job", "where i work" -> "work"
            "home", "house", "where i live", "live" -> "home"
            else -> s
        }
    }
}
