package com.kevin.legion.service

import android.Manifest
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.kevin.legion.gmail.EmailCompose
import com.kevin.legion.gmail.EmailSendGate
import com.kevin.legion.gmail.GmailAuth
import com.kevin.legion.gmail.GmailClient
import com.kevin.legion.gmail.GmailComposeApi
import com.kevin.legion.gmail.GmailComposeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * `email` (ADR 0086, Kevin 2026-11-10): Alfred drafts mail in Kevin's own Gmail, offers to open it
 * in the Gmail app, and sends it only when Kevin says send. One action-dispatched tool, declared in
 * `service/tools/mail/Declarations.kt`; this object is its dispatch.
 *
 * - `draft` resolves the recipient (an address as given; a name only when contacts or recent mail
 *   give exactly ONE address, else it asks), saves a real Gmail draft, registers it with [gate], and
 *   returns the read-back instruction. Sends nothing.
 * - `open` opens the Gmail app at that draft (falling back to Gmail itself, then the web). The
 *   result never claims which screen Gmail showed, only that Gmail is open and where the draft is.
 * - `send` goes through [EmailSendGate]: a draft id from this conversation and Kevin's own words
 *   saying send. "Sent" is returned only on Gmail's 2xx with the sent message id. Any other ending
 *   checks Drafts and says what is actually known.
 *
 * **What is logged.** The tool's args (to, subject, body) would reach LIVE_AUDIT and the conversation
 * record like every tool's args; [redactArgs] replaces `body` with its length first. `email` is in
 * [LiveToolbox.EPISODIC_EXCLUDED_TOOLS] (a recipient lookup reads recent mail), so its result and
 * Alfred's read-back that turn are redacted too. Kevin's own spoken words log as always. Logcat gets
 * HTTP status codes only.
 */
// Early returns read the refusals in order; the object holds one tool's dispatch plus its seams.
@Suppress("ReturnCount", "TooManyFunctions")
object EmailToolbox {

    const val TOOL_NAME = "email"
    private const val TAG = "EmailToolbox"

    /** How a compose-capable token request ended, Android types kept out so tests can fake it. */
    sealed interface TokenOutcome {
        data class Token(val accessToken: String) : TokenOutcome
        /** [openConsent] shows Google's approval screen; false when Android would not show it. */
        data class NeedsConsent(val openConsent: () -> Boolean) : TokenOutcome
        data class Failed(val networkFailure: Boolean) : TokenOutcome
    }

    internal var gate = EmailSendGate()

    internal var tokenProvider: suspend (Context) -> TokenOutcome = ::composeToken
    internal var apiFactory: (String) -> GmailComposeApi = { GmailComposeClient(it) }

    /** Contacts with an email whose name fits; null when contacts cannot be read (no permission). */
    internal var contactLookup: suspend (Context, String) -> List<EmailCompose.Candidate>? = ::contactsNamed

    /** `From` addresses in recent mail matching the name, read-only and read-through. */
    internal var mailLookup: suspend (String, String) -> List<EmailCompose.Candidate> = ::recentSenders

    internal var opener: (Context, String) -> Boolean = ::openInGmail

    suspend fun dispatch(context: Context, name: String, args: JSONObject, userText: String?): JSONObject? {
        if (name != TOOL_NAME) return null
        return when (args.optString("action")) {
            "draft" -> draft(context, args, userText)
            "open" -> open(context, args.optString("draft_id"))
            "send" -> send(context, args.optString("draft_id"), userText)
            else -> result(false, "Say whether to draft, open or send an email. Nothing was done.")
        }
    }

    internal suspend fun draft(context: Context, args: JSONObject, userText: String?): JSONObject {
        val toSpoken = args.optString("to").trim()
        val subject = EmailCompose.oneLine(args.optString("subject"))
        val body = args.optString("body").trim()
        if (listOf(toSpoken, subject, body).any { it.isEmpty() }) return result(false, EmailCompose.NEED_FIELDS)

        val token = when (val t = tokenProvider(context)) {
            is TokenOutcome.Token -> t.accessToken
            is TokenOutcome.NeedsConsent -> return consentNeeded(t)
            is TokenOutcome.Failed -> return tokenFailed(t)
        }

        val recipient = when (val r = resolveRecipient(context, token, toSpoken)) {
            is EmailCompose.Pick.One -> r.candidate
            is EmailCompose.Pick.Several -> return result(false, EmailCompose.several(toSpoken, r.candidates))
                .put("choices", JSONArray(r.candidates.map { it.address }))
            EmailCompose.Pick.NoMatch -> return result(false, EmailCompose.noMatch(toSpoken))
        }

        val raw = EmailCompose.rawMessage(recipient.address, subject, body)
        val created = withContext(Dispatchers.IO) { apiFactory(token).createDraft(raw) }
        return when (created) {
            is GmailComposeApi.Result.Ok -> drafted(created.value, recipient, subject, userText)
            is GmailComposeApi.Result.Failed -> when {
                GmailComposeApi.isMissingScope(created) -> consentNeeded(tokenProvider(context))
                created.networkFailure -> result(false, EmailCompose.CREATE_OFFLINE)
                else -> result(false, EmailCompose.CREATE_FAILED)
            }
        }
    }

    private fun tokenFailed(t: TokenOutcome.Failed): JSONObject =
        result(false, if (t.networkFailure) EmailCompose.CREATE_OFFLINE else EmailCompose.CREATE_FAILED)

    private fun drafted(
        created: GmailComposeApi.Created,
        recipient: EmailCompose.Candidate,
        subject: String,
        userText: String?,
    ): JSONObject {
        gate.register(created.draftId, created.messageId, recipient.address, subject, userText)
        val toSaid = if (recipient.name.isBlank()) recipient.address else "${recipient.name} (${recipient.address})"
        return result(true, EmailCompose.drafted(toSaid, subject))
            .put("draft_id", created.draftId)
            .put("to", recipient.address)
            .put("subject", subject)
            .put("sent", false)
    }

    internal suspend fun resolveRecipient(context: Context, token: String, spoken: String): EmailCompose.Pick {
        if (EmailCompose.isAddress(spoken)) return EmailCompose.Pick.One(EmailCompose.Candidate("", spoken))
        val fromContacts = contactLookup(context, spoken).orEmpty()
        val contactPick = EmailCompose.pick(spoken, fromContacts)
        if (contactPick != EmailCompose.Pick.NoMatch) return contactPick
        val fromMail = withContext(Dispatchers.IO) { mailLookup(token, spoken) }
        return EmailCompose.pick(spoken, fromMail)
    }

    internal fun open(context: Context, draftId: String?): JSONObject {
        // No draft from this conversation (a restart, or past the window): Gmail's Drafts anyway.
        val draft = gate.find(draftId) ?: return if (opener(context, "")) {
            result(true, EmailCompose.OPENED_NO_DRAFT).put("sent", false)
        } else {
            result(false, EmailCompose.OPEN_FAILED).put("sent", false)
        }
        return if (opener(context, draft.messageId)) {
            result(true, EmailCompose.opened(draft.subject)).put("sent", false)
        } else {
            result(false, EmailCompose.OPEN_FAILED).put("sent", false)
        }
    }

    internal suspend fun send(context: Context, draftId: String?, userText: String?): JSONObject {
        val draft = when (val d = gate.check(draftId, userText)) {
            is EmailSendGate.Decision.Refused -> return result(false, d.message).put("sent", false)
            is EmailSendGate.Decision.Go -> d.draft
        }
        val token = when (val t = tokenProvider(context)) {
            is TokenOutcome.Token -> t.accessToken
            is TokenOutcome.NeedsConsent -> return consentNeeded(t).put("sent", false)
            is TokenOutcome.Failed -> return result(false, EmailCompose.NOT_SENT_STILL_DRAFT).put("sent", false)
        }
        val api = apiFactory(token)
        return when (val r = withContext(Dispatchers.IO) { api.sendDraft(draft.draftId) }) {
            is GmailComposeApi.Result.Ok -> {
                gate.consume(draft.draftId)
                result(true, EmailCompose.sent(draft.to)).put("sent", true)
            }
            is GmailComposeApi.Result.Failed -> {
                if (GmailComposeApi.isMissingScope(r)) return consentNeeded(tokenProvider(context)).put("sent", false)
                // Not confirmed. What is known depends on whether the draft is still there.
                val message = when (val still = withContext(Dispatchers.IO) { api.draftExists(draft.draftId) }) {
                    is GmailComposeApi.Result.Ok ->
                        if (still.value) EmailCompose.NOT_SENT_STILL_DRAFT else EmailCompose.SEND_UNCONFIRMED_DRAFT_GONE
                    is GmailComposeApi.Result.Failed -> EmailCompose.SEND_UNCONFIRMED
                }
                result(false, message).put("sent", false)
            }
        }
    }

    private fun consentNeeded(outcome: TokenOutcome): JSONObject = when (outcome) {
        is TokenOutcome.NeedsConsent ->
            if (outcome.openConsent()) {
                result(false, EmailCompose.NEEDS_COMPOSE_CONSENT).put("needs_consent", true)
            } else {
                result(false, EmailCompose.NEEDS_COMPOSE_CONSENT_NOT_OPENED).put("needs_consent", true)
            }
        // A 403 on a token Google just reissued: the grant is not there, and no screen to show.
        else -> result(false, EmailCompose.NEEDS_COMPOSE_CONSENT_NOT_OPENED).put("needs_consent", true)
    }

    /**
     * The args as LIVE_AUDIT and the conversation record keep them: `body` replaced with its length.
     * Any other tool's args pass through unchanged.
     */
    fun redactArgs(name: String, argsJson: String): String {
        if (name != TOOL_NAME) return argsJson
        return runCatching {
            val o = JSONObject(argsJson)
            if (o.has("body")) o.put("body", "[body: ${o.optString("body").length} chars]")
            o.toString()
        }.getOrDefault("{}")
    }

    // ------------------------------------------------------------------ production seams

    private suspend fun composeToken(context: Context): TokenOutcome =
        when (val o = GmailAuth.authorize(context, GmailAuth.COMPOSE_SCOPES)) {
            is GmailAuth.Outcome.Authorized -> TokenOutcome.Token(o.accessToken)
            is GmailAuth.Outcome.NeedsConsent -> TokenOutcome.NeedsConsent {
                // Google's own approval screen. Its result Intent is not read here: once approved,
                // the grant lives with Google and the next authorize() returns a token.
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        val opts = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                        )
                        o.pendingIntent.send(context, 0, null, null, null, null, opts.toBundle())
                    } else {
                        o.pendingIntent.send()
                    }
                    true
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    Log.w(TAG, "consent screen did not open: ${e.javaClass.simpleName}")
                    false
                }
            }
            is GmailAuth.Outcome.Failed -> TokenOutcome.Failed(GmailAuth.looksLikeNetworkFailure(o.error))
        }

    private suspend fun contactsNamed(context: Context, name: String): List<EmailCompose.Candidate>? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val emailKind = ContactsContract.CommonDataKinds.Email.CONTENT_URI
        val nameCol = ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
        val addressCol = ContactsContract.CommonDataKinds.Email.ADDRESS
        val firstWord = name.trim().split(Regex("\\s+")).first()
        return withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.query(
                    emailKind, arrayOf(nameCol, addressCol), "$nameCol LIKE ?", arrayOf("%$firstWord%"), null,
                )?.use { c ->
                    buildList {
                        while (c.moveToNext()) {
                            add(EmailCompose.Candidate(c.getString(0).orEmpty(), c.getString(1).orEmpty()))
                        }
                    }
                }.orEmpty()
            }.getOrNull()
        }
    }

    private fun recentSenders(token: String, name: String): List<EmailCompose.Candidate> {
        val clean = name.replace("\"", "").trim()
        val page = GmailClient(token).search("from:(\"$clean\") newer_than:2y", EmailCompose.MAX_CHOICES + 1)
        val messages = when (page) {
            is GmailClient.FetchResult.Ok -> page.value.messages
            is GmailClient.FetchResult.Failed -> return emptyList()
        }
        return messages
            .mapNotNull { EmailCompose.parseFrom(it.from) }
    }

    /**
     * Gmail app at the draft, else Gmail's own front screen, else the web. No public Gmail intent
     * opens one draft; the `#drafts/<message id>` link is tried and the result never says which
     * screen came up. Unverified on the A25: the phone checklist in the ADR 0086 report covers it.
     */
    private fun openInGmail(context: Context, messageId: String): Boolean {
        val pm = context.packageManager
        val drafts = "https://mail.google.com/mail/u/0/#drafts"
        val link = Uri.parse(if (messageId.isBlank()) drafts else "$drafts/$messageId")
        val attempts = listOfNotNull(
            Intent(Intent.ACTION_VIEW, link).setPackage(GMAIL_PACKAGE),
            pm.getLaunchIntentForPackage(GMAIL_PACKAGE),
            Intent(Intent.ACTION_VIEW, link),
        )
        for (intent in attempts) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(pm) == null) continue
            val started = runCatching { context.startActivity(intent) }.isSuccess
            if (started) return true
        }
        return false
    }

    private const val GMAIL_PACKAGE = "com.google.android.gm"

    private fun result(success: Boolean, message: String): JSONObject =
        JSONObject().put("success", success).put("message", message)
}
