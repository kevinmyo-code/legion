package com.kevin.legion.dumb

import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * What the setup screen needs to know about each one-time grant (ADR 0062). A snapshot of facts,
 * not Android calls, so the wording is testable. Each later slice adds its own field; a grant that
 * has no feature behind it yet is not listed.
 */
data class DumbGrantFacts(
    val notificationAccess: Boolean,
    val dndAccess: Boolean,
)

/** One line on the setup screen: what it is for, its state in words, and what the button does. */
data class DumbGrantRow(
    val id: Id,
    val title: String,
    val state: String,
    val granted: Boolean,
    val buttonLabel: String,
    /** Said when this grant is missing and dumb mode is on: what is NOT happening. */
    val missingEffect: String,
) {
    enum class Id { NOTIFICATION_ACCESS, DND_ACCESS }
}

object DumbSetup {
    fun rows(facts: DumbGrantFacts): List<DumbGrantRow> = listOf(
        DumbGrantRow(
            id = DumbGrantRow.Id.NOTIFICATION_ACCESS,
            title = "Notification access",
            state = if (facts.notificationAccess) {
                "Granted. LEGION can hold other apps' notifications."
            } else {
                "Not granted. LEGION cannot hold other apps' notifications."
            },
            granted = facts.notificationAccess,
            buttonLabel = if (facts.notificationAccess) "Open settings" else "Grant access",
            missingEffect = "Notifications are not being held.",
        ),
        DumbGrantRow(
            id = DumbGrantRow.Id.DND_ACCESS,
            title = "Do Not Disturb access",
            state = if (facts.dndAccess) {
                "Granted. LEGION can silence the phone while the mode is on and put it back after."
            } else {
                "Not granted. The phone's sounds are left as they are."
            },
            granted = facts.dndAccess,
            buttonLabel = if (facts.dndAccess) "Open settings" else "Grant access",
            missingEffect = "The phone's sounds are not being silenced.",
        ),
    )

    /** "Not active: ..." lines for every missing grant, or null when nothing is missing. */
    fun notActive(rows: List<DumbGrantRow>): String? =
        rows.filter { !it.granted }.takeIf { it.isNotEmpty() }?.joinToString(" ") { it.missingEffect }
            ?.let { "Not active: $it" }

    fun factsFor(context: Context): DumbGrantFacts = DumbGrantFacts(
        notificationAccess = NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName),
        dndAccess = context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted,
    )

    /** The system page to open for [id]. */
    fun settingsAction(id: DumbGrantRow.Id): String = when (id) {
        DumbGrantRow.Id.NOTIFICATION_ACCESS -> Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
        DumbGrantRow.Id.DND_ACCESS -> Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
    }
}
