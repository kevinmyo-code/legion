package com.kevin.legion.dumb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeDigestPersistence(var state: DigestState = DigestState()) : DigestPersistence {
    override fun read(): DigestState = state
    override fun write(state: DigestState) {
        this.state = state
    }
}

class HeldDigestTest {
    private val persistence = FakeDigestPersistence()
    private val digest = HeldDigest(persistence)

    @Test
    fun `counting adds up per package and keeps the latest time`() {
        digest.record("a", 10L)
        digest.record("b", 20L)
        digest.record("a", 30L)
        val a = digest.state.value.entries.single { it.packageName == "a" }
        assertEquals(2, a.count)
        assertEquals(30L, a.lastHeldAtMs)
        assertEquals(1, digest.state.value.entries.single { it.packageName == "b" }.count)
    }

    @Test
    fun `counts survive a new instance over the same persistence`() {
        digest.record("a", 10L)
        digest.markPending()
        val again = HeldDigest(persistence)
        assertEquals(listOf(DigestEntry("a", 1, 10L)), again.state.value.entries)
        assertTrue(again.state.value.pending)
    }

    @Test
    fun `pending needs something to show`() {
        digest.markPending()
        assertFalse(digest.state.value.pending)
        digest.record("a", 1L)
        digest.markPending()
        assertTrue(digest.state.value.pending)
    }

    @Test
    fun `clear all empties the list and ends the pending digest`() {
        digest.record("a", 1L)
        digest.markPending()
        digest.clear()
        assertEquals(DigestState(), digest.state.value)
    }

    @Test
    fun `dismiss ends pending but keeps the counts, remove drops one app`() {
        digest.record("a", 1L)
        digest.record("b", 2L)
        digest.markPending()
        digest.dismiss()
        assertFalse(digest.state.value.pending)
        assertEquals(2, digest.state.value.entries.size)
        digest.remove("a")
        assertEquals(listOf("b"), digest.state.value.entries.map { it.packageName })
    }

    @Test
    fun `prefs format round trips and drops malformed lines`() {
        val entries = listOf(DigestEntry("a.b", 3, 99L), DigestEntry("c.d", 1, 5L))
        assertEquals(entries, PrefsDigestPersistence.parse(PrefsDigestPersistence.format(entries)))
        assertEquals(listOf(DigestEntry("a.b", 3, 99L)), PrefsDigestPersistence.parse("junk\na.b|3|99\nx|y|z\n|1|2"))
        assertEquals(emptyList<DigestEntry>(), PrefsDigestPersistence.parse(""))
    }

    @Test
    fun `digest rows list the busiest app first with its app name`() {
        val rows = DigestRows.build(
            listOf(DigestEntry("p.one", 2, 1L), DigestEntry("p.two", 9, 1L), DigestEntry("p.three", 2, 1L)),
        ) { pkg -> pkg.substringAfter('.').replaceFirstChar { it.uppercase() } }
        assertEquals(listOf("Two", "One", "Three"), rows.map { it.appName })
        assertEquals(9, rows.first().count)
    }
}

class ChosenPeopleTest {
    private class MemoryStore(var people: List<ChosenPerson> = emptyList()) : ChosenPeopleStore {
        override fun read() = people
        override fun write(people: List<ChosenPerson>) {
            this.people = people
        }
    }

    private val store = MemoryStore()
    private val chosen = ChosenPeople(store)

    @Test
    fun `adding a contact twice keeps one copy with the newer numbers`() {
        chosen.add(ChosenPerson("1", "Mia", listOf("5551234567")))
        chosen.add(ChosenPerson("1", "Mia", listOf("5551234567", "5557654321")))
        assertEquals(1, chosen.people.value.size)
        assertEquals(2, chosen.people.value.single().numbers.size)
        assertEquals(listOf("Mia"), chosen.names())
    }

    @Test
    fun `remove drops by contact id and persists`() {
        chosen.add(ChosenPerson("1", "Mia", emptyList()))
        chosen.add(ChosenPerson("2", "Mom", emptyList()))
        chosen.remove("1")
        assertEquals(listOf("Mom"), store.people.map { it.displayName })
    }

    @Test
    fun `a chosen number is recognised in any format`() {
        chosen.add(ChosenPerson("1", "Mia", listOf("15551234567")))
        assertTrue(chosen.isChosenNumber("(555) 123-4567"))
        assertFalse(chosen.isChosenNumber("5550000000"))
        assertFalse(chosen.isChosenNumber(null))
    }

    @Test
    fun `prefs format round trips and a name cannot forge a field`() {
        val people = listOf(
            ChosenPerson("1", "Mia", listOf("15551234567", "5557654321")),
            ChosenPerson("2", "Evil\u001F3\u001EName", emptyList()),
        )
        val back = PrefsChosenPeopleStore.parse(PrefsChosenPeopleStore.format(people))
        assertEquals(2, back.size)
        assertEquals(people[0], back[0])
        assertEquals("Evil3Name", back[1].displayName)
        assertEquals(emptyList<ChosenPerson>(), PrefsChosenPeopleStore.parse(""))
    }
}

class AlertModeTest {
    @Test
    fun `the bypass channel is used unless the switch or the system says otherwise`() {
        assertEquals(AlertMode.CHANNEL_BYPASS, AlertMode.choose(forceFallback = false, canBypass = true))
        assertEquals(AlertMode.ALARM_STREAM_FALLBACK, AlertMode.choose(forceFallback = true, canBypass = true))
        assertEquals(AlertMode.ALARM_STREAM_FALLBACK, AlertMode.choose(forceFallback = false, canBypass = false))
        assertEquals(AlertMode.ALARM_STREAM_FALLBACK, AlertMode.choose(forceFallback = true, canBypass = false))
    }
}
