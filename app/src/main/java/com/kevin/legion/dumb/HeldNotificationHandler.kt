package com.kevin.legion.dumb

import android.app.PendingIntent
import android.service.notification.StatusBarNotification

/**
 * Acts on [NotificationHold]'s decision for one posted notification (ADR 0062). Called by
 * `MediaNotificationListener`; the Android-specific edges (reading the extras, cancelling, the
 * re-alert, the app label) are passed in so the policy around them is testable.
 *
 * **A held notification is cancelled and counted, and that is all that is kept**: the package, a
 * count and the time. A chosen person's notification stays where it is and gets a re-alert whose
 * text is their name and the app's label. The same key+time is never counted or alerted twice
 * (apps re-post the same notification when it updates).
 */
class HeldNotificationHandler(
    private val hold: NotificationHold,
    private val digest: HeldDigest,
    private val reAlert: (name: String, appPackage: String, key: String, tap: PendingIntent?) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val heldSeen: SeenKeys = SeenKeys(),
    private val alertedSeen: SeenKeys = SeenKeys(),
) {
    /** Returns what was decided, so a caller (and a test) can see it. */
    fun handle(facts: HeldFacts, tap: PendingIntent?, cancel: (key: String) -> Unit): HoldDecision {
        val decision = hold.decide(facts)
        when (decision) {
            is HoldDecision.LetThrough ->
                if (alertedSeen.firstTime(facts.key, facts.postTimeMs)) {
                    reAlert(decision.person.displayName, facts.packageName, facts.key, tap)
                }
            HoldDecision.Hold -> {
                cancel(facts.key)
                if (heldSeen.firstTime(facts.key, facts.postTimeMs)) digest.record(facts.packageName, clock())
            }
            HoldDecision.Ignore, HoldDecision.LeaveAlone -> Unit
        }
        return decision
    }

    fun handle(sbn: StatusBarNotification, cancel: (key: String) -> Unit): HoldDecision =
        handle(NotificationFactsReader.read(sbn), sbn.notification.contentIntent, cancel)
}
