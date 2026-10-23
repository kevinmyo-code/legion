package com.kevin.legion.dumb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNumbersTest {
    @Test
    fun `normalize keeps digits only`() {
        assertEquals("15551234567", PhoneNumbers.normalize("+1 (555) 123-4567"))
        assertEquals("", PhoneNumbers.normalize(null))
        assertEquals("", PhoneNumbers.normalize("no digits"))
    }

    @Test
    fun `the same line in different formats matches`() {
        assertTrue(PhoneNumbers.same("+1 (555) 123-4567", "5551234567"))
        assertTrue(PhoneNumbers.same("+15551234567", "(555) 123-4567"))
        assertTrue(PhoneNumbers.same("555 123 4567", "+44 5551234567"))
    }

    @Test
    fun `different lines and short codes never match`() {
        assertFalse(PhoneNumbers.same("5551234567", "5551234568"))
        assertFalse(PhoneNumbers.same("12345", "12345"))
        assertFalse(PhoneNumbers.same(null, "5551234567"))
        assertFalse(PhoneNumbers.same("", ""))
    }

    @Test
    fun `a 7 digit local number matches the tail of a full one`() {
        assertTrue(PhoneNumbers.same("1234567", "+1 555 123 4567"))
        assertFalse(PhoneNumbers.same("1234568", "+1 555 123 4567"))
    }

    @Test
    fun `a tel uri is decoded and other schemes give nothing`() {
        assertEquals("15551234567", PhoneNumbers.fromTelUri("tel:%2B1-555-123-4567"))
        assertEquals("5551234567", PhoneNumbers.fromTelUri("TEL:555-123-4567;phone-context=x"))
        assertNull(PhoneNumbers.fromTelUri("mailto:mia@example.com"))
        assertNull(PhoneNumbers.fromTelUri("tel:"))
        assertNull(PhoneNumbers.fromTelUri(null))
    }
}

class PersonMatcherTest {
    private val mia = ChosenPerson("42", "Mia", listOf("15551234567"))
    private val mom = ChosenPerson("7", "Mom", listOf("5559876543", "5550001111"))
    private val dad = ChosenPerson("9", "Dad", emptyList())
    private val matcher = PersonMatcher(listOf(mia, mom, dad))

    @Test
    fun `a tel uri matches the chosen number`() {
        assertEquals(mia, matcher.match(NotificationPeople(uris = listOf("tel:+1-555-123-4567"))))
        assertEquals(mom, matcher.match(NotificationPeople(uris = listOf("tel:5550001111"))))
    }

    @Test
    fun `a contacts lookup uri matches by contact id`() {
        val lookup = "content://com.android.contacts/contacts/lookup/0r7-2A3B/7"
        assertEquals(mom, matcher.match(NotificationPeople(uris = listOf(lookup))))
        val direct = "content://com.android.contacts/contacts/"
        assertEquals(mia, matcher.match(NotificationPeople(uris = listOf(direct + "42"))))
        assertNull(matcher.match(NotificationPeople(uris = listOf(direct + "99"))))
    }

    @Test
    fun `a mailto uri alone matches nobody`() {
        assertNull(matcher.match(NotificationPeople(uris = listOf("mailto:mia@example.com"))))
    }

    @Test
    fun `a one to one conversation title matches the display name`() {
        assertEquals(mia, matcher.match(NotificationPeople(title = "Mia")))
        assertEquals(mom, matcher.match(NotificationPeople(title = "  MOM ❤️ ")))
    }

    @Test
    fun `a group chat title is never taken for a person`() {
        assertNull(matcher.match(NotificationPeople(title = "Mia", isGroup = true)))
    }

    @Test
    fun `in a group chat the sender person still matches`() {
        assertEquals(mia, matcher.match(NotificationPeople(names = listOf("Mia"), title = "Family", isGroup = true)))
        val bySenderNumber = NotificationPeople(uris = listOf("tel:5551234567"), title = "Family", isGroup = true)
        assertEquals(mia, matcher.match(bySenderNumber))
    }

    @Test
    fun `a number used as a name matches by number`() {
        assertEquals(mom, matcher.match(NotificationPeople(title = "+1 555-987-6543")))
        assertNull(matcher.match(NotificationPeople(title = "+1 555-987-0000")))
    }

    @Test
    fun `names match exactly and a first name guess does not`() {
        assertNull(matcher.match(NotificationPeople(title = "Mia Wong")))
        assertNull(matcher.match(NotificationPeople(title = "Miami")))
        assertNull(matcher.match(NotificationPeople(title = "")))
    }

    @Test
    fun `a stranger matches nobody`() {
        val stranger = NotificationPeople(uris = listOf("tel:5550009999"), names = listOf("Promo Bot"), title = "Sale")
        assertNull(matcher.match(stranger))
        assertNull(PersonMatcher(emptyList()).match(NotificationPeople(title = "Mia")))
    }

    @Test
    fun `normalizeName drops case, emoji and punctuation`() {
        assertEquals("mom", PersonMatcher.normalizeName("  Mom!! 💛 "))
        assertEquals("mia wong", PersonMatcher.normalizeName("Mia   Wong"))
    }

    @Test
    fun `contact id comes only from a contacts uri`() {
        assertEquals("7", PersonMatcher.contactIdOf("content://com.android.contacts/contacts/lookup/abc/7"))
        assertEquals("7", PersonMatcher.contactIdOf("content://com.android.contacts/contacts/7?x=1"))
        assertNull(PersonMatcher.contactIdOf("content://com.android.contacts/contacts/lookup/abc"))
        assertNull(PersonMatcher.contactIdOf("content://other/contacts/7"))
    }

    @Test
    fun `invite names overlap by whole words`() {
        assertTrue(PersonMatcher.namesOverlap("Mia", "Mia Wong"))
        assertTrue(PersonMatcher.namesOverlap("Mia Wong", "mia"))
        assertFalse(PersonMatcher.namesOverlap("Mia", "Miami"))
        assertFalse(PersonMatcher.namesOverlap("", "Mia"))
    }
}
