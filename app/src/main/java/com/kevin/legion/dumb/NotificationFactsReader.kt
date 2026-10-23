package com.kevin.legion.dumb

import android.app.Notification
import android.app.Person
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.kevin.legion.games.race.GameInviteNotifier

/**
 * Flattens a [StatusBarNotification] into [HeldFacts]. This is the one place that reads a
 * notification's extras, and it reads only what the hold decision needs (who it is from, never what
 * it says): sender names and URIs from the people list and the messaging style's sender persons, plus
 * the title as a one-to-one conversation's name. The result is handed to a pure function and dropped;
 * nothing here writes anywhere (CLAUDE.md section 7, ADR 0062).
 */
object NotificationFactsReader {

    fun read(sbn: StatusBarNotification): HeldFacts {
        val n = sbn.notification
        val extras = n.extras ?: Bundle.EMPTY
        val style = runCatching {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        }.getOrNull()

        val uris = mutableListOf<String>()
        val names = mutableListOf<String>()
        peopleList(extras).forEach { p ->
            p.uri?.let(uris::add)
            p.name?.toString()?.let(names::add)
        }
        legacyPeople(extras).forEach(uris::add)
        style?.messages?.forEach { m ->
            m.person?.uri?.let(uris::add)
            m.person?.name?.toString()?.let(names::add)
        }

        val title = style?.conversationTitle?.toString() ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        return HeldFacts(
            key = sbn.key,
            packageName = sbn.packageName,
            postTimeMs = sbn.postTime,
            channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) n.channelId else null,
            category = n.category,
            ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0,
            foregroundService = n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0,
            noClear = n.flags and Notification.FLAG_NO_CLEAR != 0,
            groupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            people = NotificationPeople(
                uris = uris.distinct(),
                names = names.distinct(),
                title = title,
                isGroup = style?.isGroupConversation == true,
            ),
            inviteFrom = extras.getString(EXTRA_INVITE_FROM),
        )
    }

    /** LEGION's own invite notification puts the sender's household name under this extra. */
    const val EXTRA_INVITE_FROM = GameInviteNotifier.INVITE_FROM_EXTRA

    private fun peopleList(extras: Bundle): List<Person> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptyList()
        @Suppress("DEPRECATION") // the typed overload is API 33; the untyped one reads the same list
        val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST, Person::class.java)
        } else {
            extras.getParcelableArrayList<Person>(Notification.EXTRA_PEOPLE_LIST)
        }
        return list.orEmpty()
    }

    /** `android.people`: the pre-API-28 list of URI strings that `addPerson(String)` still writes. */
    private fun legacyPeople(extras: Bundle): List<String> {
        @Suppress("DEPRECATION") // generic get(): the value is a String[], a CharSequence[] or a list, by app
        val raw = extras.get(LEGACY_PEOPLE_KEY)
        return when (raw) {
            is Array<*> -> raw.mapNotNull { it?.toString() }
            is Collection<*> -> raw.mapNotNull { it?.toString() }
            is CharSequence -> listOf(raw.toString())
            else -> emptyList()
        }
    }

    private const val LEGACY_PEOPLE_KEY = "android.people"
}
