package com.kevin.legion.dumb

import android.app.NotificationManager
import android.app.NotificationManager.Policy
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log

/**
 * Do Not Disturb as dumb mode needs it, in plain ints so the save/restore logic is testable
 * without Android. [filter] is an `INTERRUPTION_FILTER_*` value; the rest mirror
 * [NotificationManager.Policy]'s fields. [conversationSenders] only exists from API 30 and is
 * carried as [NO_CONVERSATION_SETTING] below it.
 */
data class DndSnapshot(
    val filter: Int,
    val categories: Int,
    val callSenders: Int,
    val messageSenders: Int,
    val suppressedEffects: Int,
    val conversationSenders: Int,
) {
    companion object {
        const val NO_CONVERSATION_SETTING = -1
    }
}

/** Reads and writes the system's DND state. The seam that lets [DumbDnd] be tested with a fake. */
interface DndGateway {
    fun hasAccess(): Boolean
    fun read(): DndSnapshot
    fun apply(snapshot: DndSnapshot)
}

/** Where the user's own DND state waits while dumb mode owns it. */
interface DndSavedStore {
    fun load(): DndSnapshot?
    fun save(snapshot: DndSnapshot)
    fun clear()
}

/** What entering or leaving did, in words for the setup screen. */
sealed interface DndResult {
    data object Applied : DndResult
    data object NoAccess : DndResult
    data object NothingToRestore : DndResult
}

/**
 * Dumb mode's Do Not Disturb policy (ADR 0062): Priority only; calls from any sender (the call
 * screener decides per person), no messages, no conversations; alarms, media and system sounds on.
 * Chosen people's messages get through by a re-alert on a bypass channel, not by this policy.
 *
 * **The user's own state is saved once and restored exactly.** [enter] saves the live filter and
 * policy only if nothing is saved yet, so entering twice (a restart while the mode is on) cannot
 * overwrite the user's setting with dumb mode's own. [exit] writes the saved policy back first and the
 * filter second, then forgets it.
 *
 * Quiet mode (`quiet/QuietMode.kt`) uses an automatic zen RULE precisely so as not to touch the
 * global policy; this class does touch it, as the brief for dumb mode specifies. If both are on at
 * once the more restrictive of the two wins, and on leaving, the global state returns to what it was
 * when dumb mode began.
 */
class DumbDnd(private val gateway: DndGateway, private val saved: DndSavedStore) {

    fun enter(): DndResult {
        if (!gateway.hasAccess()) return DndResult.NoAccess
        return try {
            if (saved.load() == null) saved.save(gateway.read())
            gateway.apply(DUMB_POLICY)
            DndResult.Applied
        } catch (e: SecurityException) {
            // Access was revoked between the check and the call. Nothing was changed by the failed call.
            Log.w(TAG, "Do Not Disturb access vanished while entering", e)
            DndResult.NoAccess
        }
    }

    fun exit(): DndResult {
        val previous = saved.load()
        return when {
            previous == null -> DndResult.NothingToRestore
            !gateway.hasAccess() -> DndResult.NoAccess
            else -> restore(previous)
        }
    }

    private fun restore(previous: DndSnapshot): DndResult = try {
        gateway.apply(previous)
        saved.clear()
        DndResult.Applied
    } catch (e: SecurityException) {
        // The save is kept so the next exit can still put the user's state back.
        Log.w(TAG, "Do Not Disturb access vanished while restoring", e)
        DndResult.NoAccess
    }

    companion object {
        private const val TAG = "DumbDnd"
        private const val FILTER_PRIORITY = NotificationManager.INTERRUPTION_FILTER_PRIORITY

        val DUMB_POLICY = DndSnapshot(
            filter = FILTER_PRIORITY,
            categories = Policy.PRIORITY_CATEGORY_CALLS or Policy.PRIORITY_CATEGORY_ALARMS or
                Policy.PRIORITY_CATEGORY_MEDIA or Policy.PRIORITY_CATEGORY_SYSTEM,
            callSenders = Policy.PRIORITY_SENDERS_ANY,
            messageSenders = Policy.PRIORITY_SENDERS_ANY,
            suppressedEffects = 0,
            conversationSenders = CONVERSATION_SENDERS_NONE,
        )

        /** `Policy.CONVERSATION_SENDERS_NONE`, API 30; spelled out because minSdk is 24. */
        private const val CONVERSATION_SENDERS_NONE = 3
    }
}

/** The real [NotificationManager]. */
class AndroidDndGateway(context: Context) : DndGateway {
    private val nm = context.getSystemService(NotificationManager::class.java)

    override fun hasAccess(): Boolean = nm.isNotificationPolicyAccessGranted

    override fun read(): DndSnapshot {
        val p = nm.notificationPolicy
        return DndSnapshot(
            filter = nm.currentInterruptionFilter,
            categories = p.priorityCategories,
            callSenders = p.priorityCallSenders,
            messageSenders = p.priorityMessageSenders,
            suppressedEffects = p.suppressedVisualEffects,
            conversationSenders = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                p.priorityConversationSenders
            } else {
                DndSnapshot.NO_CONVERSATION_SETTING
            },
        )
    }

    override fun apply(snapshot: DndSnapshot) {
        // Policy first, filter second: the filter is what makes the policy take effect.
        nm.notificationPolicy = buildPolicy(snapshot)
        // UNKNOWN (0) cannot be set; it means the platform did not say, so treat it as "all".
        val filter = snapshot.filter.takeIf { it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN }
            ?: NotificationManager.INTERRUPTION_FILTER_ALL
        nm.setInterruptionFilter(filter)
    }

    private fun buildPolicy(s: DndSnapshot): Policy {
        val hasConversations = s.conversationSenders != DndSnapshot.NO_CONVERSATION_SETTING
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hasConversations) {
            Policy(s.categories, s.callSenders, s.messageSenders, s.suppressedEffects, s.conversationSenders)
        } else {
            Policy(s.categories, s.callSenders, s.messageSenders, s.suppressedEffects)
        }
    }
}

/** The saved user state in SharedPreferences, so it survives the process dying while the mode is on. */
class PrefsDndSavedStore(private val prefs: SharedPreferences) : DndSavedStore {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    override fun load(): DndSnapshot? {
        if (!prefs.getBoolean(KEY_HAS, false)) return null
        return DndSnapshot(
            filter = prefs.getInt(KEY_FILTER, NotificationManager.INTERRUPTION_FILTER_ALL),
            categories = prefs.getInt(KEY_CATEGORIES, 0),
            callSenders = prefs.getInt(KEY_CALLS, 0),
            messageSenders = prefs.getInt(KEY_MESSAGES, 0),
            suppressedEffects = prefs.getInt(KEY_EFFECTS, 0),
            conversationSenders = prefs.getInt(KEY_CONVERSATIONS, DndSnapshot.NO_CONVERSATION_SETTING),
        )
    }

    override fun save(snapshot: DndSnapshot) {
        prefs.edit()
            .putBoolean(KEY_HAS, true)
            .putInt(KEY_FILTER, snapshot.filter)
            .putInt(KEY_CATEGORIES, snapshot.categories)
            .putInt(KEY_CALLS, snapshot.callSenders)
            .putInt(KEY_MESSAGES, snapshot.messageSenders)
            .putInt(KEY_EFFECTS, snapshot.suppressedEffects)
            .putInt(KEY_CONVERSATIONS, snapshot.conversationSenders)
            .commit()
    }

    override fun clear() {
        prefs.edit().clear().commit()
    }

    private companion object {
        const val PREFS = "dumb_mode_dnd_saved"
        const val KEY_HAS = "has"
        const val KEY_FILTER = "filter"
        const val KEY_CATEGORIES = "categories"
        const val KEY_CALLS = "calls"
        const val KEY_MESSAGES = "messages"
        const val KEY_EFFECTS = "effects"
        const val KEY_CONVERSATIONS = "conversations"
    }
}
