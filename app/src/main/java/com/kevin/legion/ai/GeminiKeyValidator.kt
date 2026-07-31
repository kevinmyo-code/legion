package com.kevin.legion.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Outcome of a first-run key validation ping. */
enum class KeyCheck { VALID, INVALID_KEY, NETWORK_ERROR }

/**
 * One-shot validation ping for a pasted Gemini API key: a ~1-token
 * generateContent call on the user's own key. Distinguishes a bad key
 * (typo, revoked) from an unreachable network so the first-run screen can
 * offer the right recovery — retry the paste vs. save-and-go-offline.
 */
object GeminiKeyValidator {
    private const val TAG = "GeminiKeyValidator"
    // Cheapest current text model - this is a 1-token ping, no reasoning needed.
    // Bumped 2026-07-22 alongside SubAgent.DEFAULT_MODEL (same "fast, cheap" class).
    private const val URL_BASE =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent"

    suspend fun check(key: String): KeyCheck = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", "hi")))
            }))
            put("generationConfig", JSONObject().put("maxOutputTokens", 1))
        }
        try {
            val connection = (URL("$URL_BASE?key=$key").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 10000
                readTimeout = 10000
            }
            try {
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                when (connection.responseCode) {
                    200 -> KeyCheck.VALID
                    400, 401, 403 -> KeyCheck.INVALID_KEY
                    else -> KeyCheck.NETWORK_ERROR
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "key check failed: ${e.message}")
            KeyCheck.NETWORK_ERROR
        }
    }
}
