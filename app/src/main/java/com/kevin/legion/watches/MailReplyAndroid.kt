package com.kevin.legion.watches

import android.content.Context
import com.kevin.legion.gmail.GmailAuth
import com.kevin.legion.gmail.GmailClient
import com.kevin.legion.service.ProactiveCategory
import com.kevin.legion.service.ProactiveRaise
import com.kevin.legion.service.ProactiveSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [MailReplyCursor] in a small private SharedPreferences file: a time and a sentence, no mail. */
internal class PrefsMailReplyCursor(context: Context) : MailReplyCursor {
    private val prefs = context.getSharedPreferences("watch_mail_reply", Context.MODE_PRIVATE)

    override fun get(): Long? = prefs.getLong(KEY_SINCE, -1L).takeIf { it >= 0 }

    override fun set(ms: Long?) {
        prefs.edit().apply { if (ms == null) remove(KEY_SINCE) else putLong(KEY_SINCE, ms) }.apply()
    }

    override fun cannotSee(sentence: String?) {
        prefs.edit().apply { if (sentence == null) remove(KEY_BLIND) else putString(KEY_BLIND, sentence) }.apply()
    }

    override fun cannotSeeSentence(): String? = prefs.getString(KEY_BLIND, null)

    private companion object {
        const val KEY_SINCE = "checked_through_ms"
        const val KEY_BLIND = "cannot_see"
    }
}

/**
 * The Android half of [MailReplyCheck]: the engine's watch list for the company names, Gmail's
 * read-only search for the headers, and the raise. Nothing read from mail is kept or sent.
 */
internal object MailReplyAndroid {
    private const val MAX_RESULTS = 50

    fun check(app: Context): MailReplyCheck = MailReplyCheck(
        // With the master or Watches switch off, no mail is read at all.
        companyLists = {
            ProactiveSettings.load(app)
            if (ProactiveSettings.mayRaise(ProactiveCategory.WATCHES)) {
                companyLists(WatchesController.forContext(app))
            } else {
                null
            }
        },
        search = { query -> search(app, query) },
        cursor = PrefsMailReplyCursor(app),
        announce = { facts -> announce(app, facts) },
    )

    internal suspend fun companyLists(controller: WatchesController): List<List<String>>? =
        when (val out = controller.list()) {
            is WatchOutcome.Ok -> out.value.watches
                .filter { it.kind == KIND_MAIL_REPLY && it.status == "on" }
                .map(MailReplyCheck::companiesOf)
            else -> null
        }

    /** Null when the phone can read mail; otherwise the sentence that says it cannot. */
    suspend fun access(app: Context): String? =
        when (val t = GmailAuth.tokenOrReason(app)) {
            is GmailAuth.TokenResult.Token -> null
            is GmailAuth.TokenResult.Failed ->
                if (GmailAuth.looksLikeNetworkFailure(t.error)) null else MailReplyWording.CANNOT_SEE
            GmailAuth.TokenResult.NeedsConsent -> MailReplyWording.CANNOT_SEE
        }

    @Suppress("ReturnCount") // no grant, offline, or the search: three distinct answers
    private suspend fun search(app: Context, query: String): MailCheck {
        val token = when (val t = GmailAuth.tokenOrReason(app)) {
            is GmailAuth.TokenResult.Token -> t.accessToken
            GmailAuth.TokenResult.NeedsConsent ->
                return MailCheck.CannotSee(MailReplyWording.CANNOT_SEE)
            // Offline is "try later"; a refused or revoked grant is "cannot see", never "no replies".
            is GmailAuth.TokenResult.Failed -> return if (GmailAuth.looksLikeNetworkFailure(t.error)) {
                MailCheck.Failed
            } else {
                MailCheck.CannotSee(MailReplyWording.CANNOT_SEE)
            }
        }
        val page = withContext(Dispatchers.IO) {
            GmailClient(token).search(query, MAX_RESULTS)
        }
        return when (page) {
            is GmailClient.FetchResult.Ok ->
                MailCheck.Found(page.value.messages.map { MailHeaders(it.from, it.subject, it.timestampMs) })
            is GmailClient.FetchResult.Failed -> if (page.networkFailure) {
                MailCheck.Failed
            } else {
                MailCheck.CannotSee(MailReplyWording.CANNOT_SEE)
            }
        }
    }

    internal fun raise(facts: String, nowMs: Long): ProactiveRaise =
        ProactiveRaise(
            // One rule per check, so brushing off one never suppresses the next reply for a day.
            ruleId = "watch_mail_reply_${nowMs / MINUTE_MS}",
            category = ProactiveCategory.WATCHES,
            reason = MailReplyWording.REASON,
            facts = facts,
            prompt = MailReplyWording.prompt(facts),
            // It is about mail: a reply to it is never written to episodic memory.
            carriesReadThroughContent = true,
        )

    private suspend fun announce(app: Context, facts: String): WatchDelivery =
        WatchPolling.deliver(app, raise(facts, System.currentTimeMillis()))

    private const val MINUTE_MS = 60_000L
}
