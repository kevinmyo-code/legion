package com.kevin.legion.dumb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val OWN = "com.kevin.legion"
private const val INVITE_CHANNEL = "game_invites_channel"

class NotificationHoldTest {
    private val mia = ChosenPerson("42", "Mia", listOf("15551234567"))
    private var on = true
    private val hold = NotificationHold(
        isOn = { on },
        ownPackage = OWN,
        inviteChannelId = INVITE_CHANNEL,
        people = { listOf(mia) },
    )

    private fun facts(
        pkg: String = "com.social.app",
        people: NotificationPeople = NotificationPeople(),
        configure: HeldFacts.() -> HeldFacts = { this },
    ) = HeldFacts(key = "0|$pkg|1", packageName = pkg, postTimeMs = 1_000L, people = people).configure()

    @Test
    fun `off means not our business`() {
        on = false
        assertEquals(HoldDecision.Ignore, hold.decide(facts()))
    }

    @Test
    fun `a stranger's notification on any app is held`() {
        assertEquals(HoldDecision.Hold, hold.decide(facts(people = NotificationPeople(title = "Promo Bot"))))
        assertEquals(HoldDecision.Hold, hold.decide(facts(pkg = "com.android.vending")))
    }

    @Test
    fun `a chosen person gets through on any app`() {
        val from = NotificationPeople(names = listOf("Mia"))
        for (pkg in listOf("com.whatsapp", "com.google.android.apps.messaging", "com.instagram.android")) {
            assertEquals(HoldDecision.LetThrough(mia), hold.decide(facts(pkg = pkg, people = from)))
        }
    }

    @Test
    fun `ongoing, foreground and non-clearable notifications are left alone`() {
        assertEquals(HoldDecision.LeaveAlone, hold.decide(facts { copy(ongoing = true) }))
        assertEquals(HoldDecision.LeaveAlone, hold.decide(facts { copy(foregroundService = true) }))
        assertEquals(HoldDecision.LeaveAlone, hold.decide(facts { copy(noClear = true) }))
    }

    @Test
    fun `call, navigation, media and alarm categories are left alone`() {
        for (category in listOf("call", "navigation", "transport", "alarm")) {
            assertEquals(HoldDecision.LeaveAlone, hold.decide(facts { copy(category = category) }))
        }
        assertEquals(HoldDecision.Hold, hold.decide(facts { copy(category = "social") }))
    }

    @Test
    fun `a group summary is left alone so its children are handled one by one`() {
        assertEquals(HoldDecision.LeaveAlone, hold.decide(facts { copy(groupSummary = true) }))
    }

    @Test
    fun `emergency alert receivers are never held`() {
        assertEquals(HoldDecision.LeaveAlone, hold.decide(facts(pkg = "com.google.android.cellbroadcastreceiver")))
        assertEquals(HoldDecision.LeaveAlone, hold.decide(facts(pkg = "com.samsung.android.emergencymode")))
    }

    @Test
    fun `a Tetris invite from a chosen person gets through`() {
        val invite = facts(pkg = OWN) { copy(channelId = INVITE_CHANNEL, inviteFrom = "Mia") }
        assertEquals(HoldDecision.LetThrough(mia), hold.decide(invite))
        val fullName = invite.copy(inviteFrom = "mia")
        assertEquals(HoldDecision.LetThrough(mia), hold.decide(fullName))
    }

    @Test
    fun `a Tetris invite from anyone else is held`() {
        val fromSam = facts(pkg = OWN) { copy(channelId = INVITE_CHANNEL, inviteFrom = "Sam") }
        val fromNobody = facts(pkg = OWN) { copy(channelId = INVITE_CHANNEL, inviteFrom = null) }
        assertEquals(HoldDecision.Hold, hold.decide(fromSam))
        assertEquals(HoldDecision.Hold, hold.decide(fromNobody))
    }

    @Test
    fun `LEGION's other notifications are left alone`() {
        assertEquals(HoldDecision.LeaveAlone, hold.decide(facts(pkg = OWN) { copy(channelId = "reminders") }))
    }
}

class HeldNotificationHandlerTest {
    private val mia = ChosenPerson("42", "Mia", listOf("15551234567"))
    private val persistence = FakeDigestPersistence()
    private val digest = HeldDigest(persistence)
    private val alerts = mutableListOf<List<Any?>>()
    private val cancelled = mutableListOf<String>()
    private var now = 5_000L
    private val handler = HeldNotificationHandler(
        hold = NotificationHold({ true }, OWN, INVITE_CHANNEL, { listOf(mia) }),
        digest = digest,
        reAlert = { name, pkg, key, tap -> alerts += listOf(name, pkg, key, tap) },
        clock = { now },
    )

    private fun held(key: String = "k1", pkg: String = "com.social.app", at: Long = 100L) =
        HeldFacts(key = key, packageName = pkg, postTimeMs = at, people = NotificationPeople(title = "Promo Bot"))

    private fun fromMia(key: String = "m1", at: Long = 200L) =
        HeldFacts(key = key, packageName = "com.whatsapp", postTimeMs = at, people = NotificationPeople(title = "Mia"))

    @Test
    fun `a held notification is cancelled and counted by package, count and time only`() {
        handler.handle(held(), null) { cancelled += it }
        assertEquals(listOf("k1"), cancelled)
        assertEquals(listOf(DigestEntry("com.social.app", 1, 5_000L)), digest.state.value.entries)
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `the same notification re-posted is counted once, a new one is counted again`() {
        handler.handle(held(), null) { cancelled += it }
        handler.handle(held(), null) { cancelled += it }
        assertEquals(1, digest.state.value.entries.single().count)
        handler.handle(held(key = "k2", at = 101L), null) { cancelled += it }
        assertEquals(2, digest.state.value.entries.single().count)
        assertEquals(3, cancelled.size)
    }

    @Test
    fun `a chosen person is re-alerted once and never cancelled or counted`() {
        handler.handle(fromMia(), null) { cancelled += it }
        handler.handle(fromMia(), null) { cancelled += it }
        assertEquals(listOf(listOf("Mia", "com.whatsapp", "m1", null)), alerts)
        assertTrue(cancelled.isEmpty())
        assertTrue(digest.state.value.entries.isEmpty())
    }

    @Test
    fun `a newer post from the same chat alerts again`() {
        handler.handle(fromMia(at = 200L), null) {}
        handler.handle(fromMia(at = 300L), null) {}
        assertEquals(2, alerts.size)
    }

    @Test
    fun `the digest record carries no content fields`() {
        // The only fields a DigestEntry has are the three below; a title or text has nowhere to live.
        // The Compose compiler adds a synthetic `$stable` field to stable classes; it holds no data.
        val fields = DigestEntry::class.java.declaredFields
            .filterNot { it.isSynthetic || it.name == "\$stable" }
            .map { it.name }
            .sorted()
        assertEquals(listOf("count", "lastHeldAtMs", "packageName"), fields)
    }

    @Test
    fun `ongoing notifications touch nothing`() {
        handler.handle(held().copy(ongoing = true), null) { cancelled += it }
        assertTrue(cancelled.isEmpty())
        assertTrue(digest.state.value.entries.isEmpty())
        assertNull(alerts.firstOrNull())
        assertFalse(digest.state.value.pending)
    }
}
