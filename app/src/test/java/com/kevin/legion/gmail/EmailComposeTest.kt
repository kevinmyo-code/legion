package com.kevin.legion.gmail

import okio.ByteString.Companion.decodeBase64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR 0086: the raw message, address checks and the never-guess recipient pick. Made-up people only. */
class EmailComposeTest {

    private fun decode(raw: String): String = raw.decodeBase64()!!.utf8()

    @Test
    fun `raw message carries to, subject and a base64 utf-8 body`() {
        val mime = decode(EmailCompose.rawMessage("jane.roe@example.com", "Running late", "Ten minutes.\nSorry."))
        assertTrue(mime.startsWith("To: jane.roe@example.com\r\nSubject: Running late\r\n"))
        assertTrue(mime.contains("Content-Type: text/plain; charset=UTF-8\r\n"))
        val body = mime.substringAfter("\r\n\r\n").replace("\r\n", "").decodeBase64()!!.utf8()
        assertEquals("Ten minutes.\r\nSorry.", body)
    }

    @Test
    fun `a subject cannot inject a header`() {
        val mime = decode(EmailCompose.rawMessage("jane.roe@example.com", "Hi\r\nBcc: x@example.com", "b"))
        assertFalse(mime.contains("\r\nBcc:"))
        assertTrue(mime.contains("Subject: Hi Bcc: x@example.com\r\n"))
    }

    @Test
    fun `a non-ascii subject is an encoded word`() {
        val mime = decode(EmailCompose.rawMessage("jane.roe@example.com", "Café at 5", "b"))
        assertTrue(mime.contains("Subject: =?UTF-8?B?"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a name is never written as an address`() {
        EmailCompose.rawMessage("Jane Roe", "s", "b")
    }

    @Test
    fun `address check`() {
        assertTrue(EmailCompose.isAddress("jane.roe@example.com"))
        assertFalse(EmailCompose.isAddress("jane roe"))
        assertFalse(EmailCompose.isAddress("<jane@example.com>"))
        assertFalse(EmailCompose.isAddress("jane@example"))
    }

    private val jane = EmailCompose.Candidate("Jane Roe", "jane.roe@example.com")
    private val janeWork = EmailCompose.Candidate("Jane Roe", "jroe@work.example.org")
    private val john = EmailCompose.Candidate("John Doe", "john.doe@example.com")

    @Test
    fun `one address is a pick`() {
        val p = EmailCompose.pick("jane", listOf(jane, john, jane.copy(name = "Jane R.")))
        assertEquals(EmailCompose.Pick.One(jane), p)
    }

    @Test
    fun `two addresses for one name is ask, never a guess`() {
        val p = EmailCompose.pick("Jane Roe", listOf(jane, janeWork, john))
        assertTrue(p is EmailCompose.Pick.Several)
        assertEquals(2, (p as EmailCompose.Pick.Several).candidates.size)
    }

    @Test
    fun `a near miss is no match`() {
        assertEquals(EmailCompose.Pick.NoMatch, EmailCompose.pick("Janet", listOf(jane, john)))
        assertEquals(EmailCompose.Pick.NoMatch, EmailCompose.pick("", listOf(jane)))
    }

    @Test
    fun `from headers parse`() {
        assertEquals(jane, EmailCompose.parseFrom("Jane Roe <jane.roe@example.com>"))
        assertEquals(jane, EmailCompose.parseFrom("\"Jane Roe\" <jane.roe@example.com>"))
        assertEquals(EmailCompose.Candidate("", "jane.roe@example.com"), EmailCompose.parseFrom("jane.roe@example.com"))
        assertNull(EmailCompose.parseFrom("Jane Roe"))
    }
}
