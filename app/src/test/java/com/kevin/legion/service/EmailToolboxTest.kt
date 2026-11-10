package com.kevin.legion.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kevin.legion.gmail.EmailCompose
import com.kevin.legion.gmail.EmailSendGate
import com.kevin.legion.gmail.GmailComposeApi
import com.kevin.legion.gmail.GmailComposeClient
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The `email` tool (ADR 0086): draft creates and sends nothing, send needs this conversation's
 * draft and the user's own "send", "sent" only on Gmail's success, every failure says not sent and
 * where the draft is, and a missing compose grant opens the approval and drafts nothing. Fakes
 * only; made-up people. Robolectric for `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
class EmailToolboxTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class FakeApi : GmailComposeApi {
        val created = mutableListOf<String>()
        val sent = mutableListOf<String>()
        var createResult: GmailComposeApi.Result<GmailComposeApi.Created> =
            GmailComposeApi.Result.Ok(GmailComposeApi.Created("draft-1", "msg-1"))
        var sendResult: GmailComposeApi.Result<String> = GmailComposeApi.Result.Ok("sent-1")
        var existsResult: GmailComposeApi.Result<Boolean> = GmailComposeApi.Result.Ok(true)

        override fun createDraft(raw: String) = createResult.also { created += raw }
        override fun sendDraft(draftId: String) = sendResult.also { sent += draftId }
        override fun draftExists(draftId: String) = existsResult
    }

    private val api = FakeApi()
    private var consentOpened = 0
    private var token: EmailToolbox.TokenOutcome = EmailToolbox.TokenOutcome.Token("t")
    private var contacts: List<EmailCompose.Candidate>? = emptyList()
    private var mail: List<EmailCompose.Candidate> = emptyList()
    private var opened = mutableListOf<String>()

    @Before
    fun setUp() {
        EmailToolbox.gate = EmailSendGate()
        EmailToolbox.tokenProvider = { token }
        EmailToolbox.apiFactory = { api }
        EmailToolbox.contactLookup = { _, _ -> contacts }
        EmailToolbox.mailLookup = { _, _ -> mail }
        EmailToolbox.opener = { _, id -> opened += id; true }
    }

    @After
    fun tearDown() {
        EmailToolbox.gate = EmailSendGate()
    }

    private fun call(args: JSONObject, words: String?): JSONObject =
        runBlocking { EmailToolbox.dispatch(context, "email", args, words)!! }

    private fun draftArgs(to: String = "jane.roe@example.com") = JSONObject()
        .put("action", "draft").put("to", to).put("subject", "Running late").put("body", "Ten minutes behind.")

    private fun send(words: String?, id: String = "draft-1") =
        call(JSONObject().put("action", "send").put("draft_id", id), words)

    private fun drafted(): JSONObject = call(draftArgs(), "email jane I'm running late")

    @Test
    fun `draft saves a draft and sends nothing`() {
        val r = drafted()
        assertTrue(r.getBoolean("success"))
        assertFalse(r.getBoolean("sent"))
        assertEquals("draft-1", r.getString("draft_id"))
        assertEquals(1, api.created.size)
        assertTrue(api.sent.isEmpty())
        assertTrue(r.getString("message").contains("NOT sent"))
    }

    @Test
    fun `send on the user's word send says sent only after Gmail confirms`() {
        drafted()
        val r = send("send it")
        assertTrue(r.getBoolean("success"))
        assertTrue(r.getBoolean("sent"))
        assertEquals(listOf("draft-1"), api.sent)
        assertTrue(r.getString("message").startsWith("Sent"))
    }

    @Test
    fun `send without the word send never reaches Gmail`() {
        drafted()
        for (words in listOf("yes", "let me check it", "", "email jane I'm running late")) {
            val r = send(words)
            assertFalse(r.getBoolean("success"))
            assertFalse(r.getBoolean("sent"))
        }
        assertTrue(api.sent.isEmpty())
    }

    @Test
    fun `send of a draft this conversation did not make never reaches Gmail`() {
        drafted()
        val r = send("send", id = "someone-elses-draft")
        assertFalse(r.getBoolean("success"))
        assertEquals(EmailSendGate.UNKNOWN_DRAFT, r.getString("message"))
        assertTrue(api.sent.isEmpty())
    }

    @Test
    fun `a refused send leaves the draft in Drafts`() {
        drafted()
        api.sendResult = GmailComposeApi.Result.Failed(networkFailure = false, statusCode = 400)
        api.existsResult = GmailComposeApi.Result.Ok(true)
        val r = send("send")
        assertFalse(r.getBoolean("success"))
        assertEquals(EmailCompose.NOT_SENT_STILL_DRAFT, r.getString("message"))
    }

    @Test
    fun `a timed out send with the draft still there is not sent`() {
        drafted()
        api.sendResult = GmailComposeApi.Result.Failed(networkFailure = true)
        api.existsResult = GmailComposeApi.Result.Ok(true)
        assertEquals(EmailCompose.NOT_SENT_STILL_DRAFT, send("send").getString("message"))
    }

    @Test
    fun `a timed out send with the draft gone is unconfirmed, never sent`() {
        drafted()
        api.sendResult = GmailComposeApi.Result.Failed(networkFailure = true)
        api.existsResult = GmailComposeApi.Result.Ok(false)
        val r = send("send")
        assertFalse(r.getBoolean("sent"))
        assertEquals(EmailCompose.SEND_UNCONFIRMED_DRAFT_GONE, r.getString("message"))
    }

    @Test
    fun `no answer at all is unconfirmed, never sent`() {
        drafted()
        api.sendResult = GmailComposeApi.Result.Failed(networkFailure = true)
        api.existsResult = GmailComposeApi.Result.Failed(networkFailure = true)
        assertEquals(EmailCompose.SEND_UNCONFIRMED, send("send").getString("message"))
    }

    @Test
    fun `a failed send can be retried with a new send`() {
        drafted()
        api.sendResult = GmailComposeApi.Result.Failed(networkFailure = false, statusCode = 500)
        send("send")
        api.sendResult = GmailComposeApi.Result.Ok("sent-1")
        assertFalse(send("send").getBoolean("success"))
        assertTrue(send("okay send it now").getBoolean("sent"))
    }

    @Test
    fun `missing compose grant opens the approval and drafts nothing`() {
        token = EmailToolbox.TokenOutcome.NeedsConsent { consentOpened++; true }
        val r = drafted()
        assertFalse(r.getBoolean("success"))
        assertTrue(r.getBoolean("needs_consent"))
        assertEquals(EmailCompose.NEEDS_COMPOSE_CONSENT, r.getString("message"))
        assertEquals(1, consentOpened)
        assertTrue(api.created.isEmpty())
    }

    @Test
    fun `approval that cannot open says so`() {
        token = EmailToolbox.TokenOutcome.NeedsConsent { false }
        assertEquals(EmailCompose.NEEDS_COMPOSE_CONSENT_NOT_OPENED, drafted().getString("message"))
    }

    @Test
    fun `a 403 scope refusal from Gmail is the approval path`() {
        api.createResult = GmailComposeApi.Result.Failed(
            networkFailure = false,
            statusCode = 403,
            body = "{\"error\":{\"message\":\"Request had insufficient authentication scopes.\"}}",
        )
        val r = drafted()
        assertFalse(r.getBoolean("success"))
        assertTrue(r.getBoolean("needs_consent"))
    }

    @Test
    fun `a name with one address in contacts drafts to it`() {
        contacts = listOf(EmailCompose.Candidate("Jane Roe", "jane.roe@example.com"))
        val r = call(draftArgs(to = "Jane"), "email jane")
        assertTrue(r.getBoolean("success"))
        assertEquals("jane.roe@example.com", r.getString("to"))
    }

    @Test
    fun `a name with two addresses asks and drafts nothing`() {
        contacts = listOf(
            EmailCompose.Candidate("Jane Roe", "jane.roe@example.com"),
            EmailCompose.Candidate("Jane Roe", "jroe@work.example.org"),
        )
        val r = call(draftArgs(to = "Jane Roe"), "email jane")
        assertFalse(r.getBoolean("success"))
        assertEquals(2, r.getJSONArray("choices").length())
        assertTrue(api.created.isEmpty())
    }

    @Test
    fun `a name only in recent mail resolves from it`() {
        mail = listOf(EmailCompose.Candidate("Sam Poe", "sam@example.net"))
        val r = call(draftArgs(to = "Sam Poe"), "email sam")
        assertEquals("sam@example.net", r.getString("to"))
    }

    @Test
    fun `a name found nowhere asks and never guesses`() {
        val r = call(draftArgs(to = "Quinn"), "email quinn")
        assertFalse(r.getBoolean("success"))
        assertEquals(EmailCompose.noMatch("Quinn"), r.getString("message"))
        assertTrue(api.created.isEmpty())
    }

    @Test
    fun `missing fields draft nothing`() {
        val r = call(JSONObject().put("action", "draft").put("to", "jane.roe@example.com"), "x")
        assertEquals(EmailCompose.NEED_FIELDS, r.getString("message"))
        assertTrue(api.created.isEmpty())
    }

    @Test
    fun `open shows the draft in Gmail and says it is not sent`() {
        drafted()
        val r = call(JSONObject().put("action", "open").put("draft_id", "draft-1"), "check it")
        assertTrue(r.getBoolean("success"))
        assertFalse(r.getBoolean("sent"))
        assertEquals(listOf("msg-1"), opened)
        assertTrue(api.sent.isEmpty())
    }

    @Test
    fun `open with no draft opens nothing`() {
        val r = call(JSONObject().put("action", "open"), "check it")
        assertFalse(r.getBoolean("success"))
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `other tools are not this one`() {
        assertEquals(null, runBlocking { EmailToolbox.dispatch(context, "search_mail", JSONObject(), "x") })
    }

    @Test
    fun `logged args never carry the body`() {
        val logged = EmailToolbox.redactArgs("email", draftArgs().toString())
        assertFalse(logged.contains("Ten minutes behind"))
        assertTrue(logged.contains("[body: 19 chars]"))
        val other = JSONObject().put("body", "kept").toString()
        assertEquals(other, EmailToolbox.redactArgs("manage_item", other))
    }

    @Test
    fun `Gmail responses parse`() {
        assertEquals(
            GmailComposeApi.Created("r-1", "m-1"),
            GmailComposeClient.parseCreated("{\"id\":\"r-1\",\"message\":{\"id\":\"m-1\",\"threadId\":\"t\"}}"),
        )
        assertEquals(null, GmailComposeClient.parseCreated("{}"))
        assertEquals("m-2", GmailComposeClient.parseSentId("{\"id\":\"m-2\",\"labelIds\":[\"SENT\"]}"))
        assertEquals(null, GmailComposeClient.parseSentId("not json"))
    }

    @Test
    fun `email is declared, mutating and read-through`() {
        val decls = LiveToolbox.declarations()
        val names = (0 until decls.length()).map { decls.getJSONObject(it).getString("name") }
        assertTrue("email" in names)
        assertTrue(LiveToolbox.isMutatingTool("email"))
        assertTrue("email" in LiveToolbox.EPISODIC_EXCLUDED_TOOLS)
    }
}
