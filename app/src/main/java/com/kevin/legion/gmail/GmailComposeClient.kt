package com.kevin.legion.gmail

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The three Gmail draft calls the `email` tool makes (ADR 0086), behind an interface so the tool's
 * draft, send and failure paths are tested with a fake. Needs a token carrying
 * [GmailAuth.GMAIL_COMPOSE_SCOPE]. Nothing here deletes mail or touches labels.
 *
 * Logs status codes and ids only, never a recipient, subject or body.
 */
interface GmailComposeApi {

    /** A draft Gmail saved: its draft id (what `drafts.send` takes) and its message id. */
    data class Created(val draftId: String, val messageId: String)

    /** How a call ended. [Failed.networkFailure] true means it never reached Google (offline,
     * timeout): for a send that is UNKNOWN, not "not sent". [statusCode] is the HTTP code if any. */
    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>
        data class Failed(val networkFailure: Boolean, val statusCode: Int? = null, val body: String? = null) :
            Result<Nothing>
    }

    /** `users.drafts.create` with a base64url RFC 2822 message ([EmailCompose.rawMessage]). */
    fun createDraft(raw: String): Result<Created>

    /** `users.drafts.send`. Ok only on a 2xx carrying the sent message's id. */
    fun sendDraft(draftId: String): Result<String>

    /** `users.drafts.get`: true it is still a draft, false Gmail says it is gone (404). */
    fun draftExists(draftId: String): Result<Boolean>

    companion object {
        private const val HTTP_FORBIDDEN = 403

        /** A 403 whose body names a scope: the token exists but lacks gmail.compose. */
        fun isMissingScope(failed: Result.Failed): Boolean =
            failed.statusCode == HTTP_FORBIDDEN && failed.body.orEmpty().let {
                it.contains("insufficient", ignoreCase = true) || it.contains("scope", ignoreCase = true)
            }
    }
}

class GmailComposeClient(private val accessToken: String) : GmailComposeApi {

    private val client = OkHttpClient.Builder()
        .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    override fun createDraft(raw: String): GmailComposeApi.Result<GmailComposeApi.Created> {
        val payload = JSONObject().put("message", JSONObject().put("raw", raw)).toString()
        return when (val r = post("$BASE/drafts", payload)) {
            is GmailComposeApi.Result.Failed -> r
            is GmailComposeApi.Result.Ok -> parseCreated(r.value)
                ?.let { GmailComposeApi.Result.Ok(it) }
                ?: GmailComposeApi.Result.Failed(networkFailure = false)
        }
    }

    override fun sendDraft(draftId: String): GmailComposeApi.Result<String> {
        val payload = JSONObject().put("id", draftId).toString()
        return when (val r = post("$BASE/drafts/send", payload)) {
            is GmailComposeApi.Result.Failed -> r
            is GmailComposeApi.Result.Ok -> parseSentId(r.value)
                ?.let { GmailComposeApi.Result.Ok(it) }
                // A 2xx we cannot read is not a confirmed send; the caller checks Drafts.
                ?: GmailComposeApi.Result.Failed(networkFailure = true)
        }
    }

    override fun draftExists(draftId: String): GmailComposeApi.Result<Boolean> =
        try {
            val req = Request.Builder().url("$BASE/drafts/$draftId?format=minimal")
                .header("Authorization", "Bearer $accessToken").get().build()
            client.newCall(req).execute().use { resp ->
                when {
                    resp.isSuccessful -> GmailComposeApi.Result.Ok(true)
                    resp.code == HTTP_NOT_FOUND -> GmailComposeApi.Result.Ok(false)
                    else -> GmailComposeApi.Result.Failed(false, resp.code, resp.body?.string())
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "drafts.get network error: ${e.javaClass.simpleName}")
            GmailComposeApi.Result.Failed(networkFailure = true)
        }

    private fun post(url: String, json: String): GmailComposeApi.Result<String> =
        try {
            val req = Request.Builder().url(url)
                .header("Authorization", "Bearer $accessToken")
                .post(json.toRequestBody(JSON))
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    GmailComposeApi.Result.Ok(resp.body?.string().orEmpty())
                } else {
                    val body = resp.body?.string()
                    Log.w(TAG, "POST ${url.substringAfterLast("/users/me")} failed: ${resp.code}")
                    GmailComposeApi.Result.Failed(networkFailure = false, statusCode = resp.code, body = body)
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "POST network error: ${e.javaClass.simpleName}")
            GmailComposeApi.Result.Failed(networkFailure = true)
        }

    companion object {
        private const val TAG = "GmailCompose"
        private const val BASE = "https://gmail.googleapis.com/gmail/v1/users/me"
        private const val CALL_TIMEOUT_S = 8L
        private const val HTTP_NOT_FOUND = 404
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** `{"id": draftId, "message": {"id": ...}}` from drafts.create, or null. */
        fun parseCreated(body: String): GmailComposeApi.Created? = runCatching {
            val json = JSONObject(body)
            val id = json.optString("id")
            val messageId = json.optJSONObject("message")?.optString("id").orEmpty()
            if (id.isBlank()) null else GmailComposeApi.Created(id, messageId)
        }.getOrNull()

        /** The sent message's id from drafts.send (a Message resource), or null. */
        fun parseSentId(body: String): String? =
            runCatching { JSONObject(body).optString("id").takeIf { it.isNotBlank() } }.getOrNull()
    }
}
