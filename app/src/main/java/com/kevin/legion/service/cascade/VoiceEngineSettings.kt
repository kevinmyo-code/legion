package com.kevin.legion.service.cascade

import android.content.Context
import com.kevin.legion.ai.KeyVault

/**
 * Which voice pipeline a conversation runs on (Kevin, 2026-11-10: "i want to be able to switch
 * between the two to compare, a settings in the UI on the phone perhaps"). [LIVE] is Gemini Live,
 * the proven path and the default. [LAPTOP] is the cascade: speech-to-text and text-to-speech on
 * the home laptop's voice service, Gemini text with tools on the phone in between.
 */
enum class VoiceEngine { LIVE, LAPTOP }

/**
 * How the phone reaches the laptop voice service (wire contract protocol 1,
 * `.legion/reports/laptop-voice-contract.md`, not in the repo). Entered at runtime in
 * Settings > Assistant and kept app-private; never baked into the APK, never logged.
 *
 * [baseUrl] is the service root: the laptop's address on Kevin's Tailscale network (Kevin,
 * 2026-11-10: Tailscale, not a Cloudflare tunnel), e.g. `http://<laptop>.<tailnet>.ts.net:8765`,
 * or `http://127.0.0.1:8765` through `adb reverse` at the desk. [token] goes out as
 * `Authorization: Bearer`. [headers] are optional generic extras (none are needed on Tailscale).
 */
data class LaptopVoiceConfig(
    val baseUrl: String,
    val token: String,
    val headers: List<Pair<String, String>> = emptyList(),
) {
    /** A URL and a token: enough to try. Headers are optional (no tunnel yet). */
    val isComplete: Boolean get() = isValidBaseUrl(baseUrl) && token.isNotBlank()

    val healthUrl: String get() = root() + "/health"

    /** `http` becomes `ws`, `https` becomes `wss`. */
    val conversationUrl: String
        get() {
            val r = root()
            val ws = when {
                r.startsWith("https://") -> "wss://" + r.removePrefix("https://")
                r.startsWith("http://") -> "ws://" + r.removePrefix("http://")
                else -> r
            }
            return "$ws/v1/conversation"
        }

    /** Every request carries these; the bearer first. */
    fun requestHeaders(): List<Pair<String, String>> =
        listOf("Authorization" to "Bearer ${token.trim()}") +
            headers.filter { it.first.isNotBlank() && it.second.isNotBlank() }

    private fun root(): String = baseUrl.trim().trimEnd('/')

    /** Never print the secrets: the host only, for logs and the settings row. */
    override fun toString(): String = "LaptopVoiceConfig(${hostOf(baseUrl)}, token=${if (token.isBlank()) "none" else "set"}, headers=${headers.size})"

    companion object {
        fun isValidBaseUrl(url: String): Boolean {
            val t = url.trim()
            val rest = when {
                t.startsWith("http://") -> t.removePrefix("http://")
                t.startsWith("https://") -> t.removePrefix("https://")
                else -> return false
            }
            return rest.substringBefore("/").isNotBlank()
        }

        fun hostOf(url: String): String =
            url.trim().substringAfter("://").substringBefore("/").ifBlank { "none" }
    }
}

/**
 * The Settings > Assistant "Voice engine" switch and the laptop connection, app-private.
 *
 * The engine is read ONCE when a conversation opens ([com.kevin.legion.service.LiveSessionController]
 * through [CascadeHost]); a change applies to the next conversation, the same rule
 * [com.kevin.legion.service.turn.VoiceTurnSettings] follows. The token and any header values are
 * encrypted with [KeyVault] (Keystore AES/GCM) where the Keystore works, plain app-private
 * otherwise, which is how the engine's device token is kept today.
 */
object VoiceEngineSettings {
    private const val PREFS = "voice_engine"
    private const val KEY_ENGINE = "engine"
    private const val KEY_URL = "laptop_url"
    private const val KEY_TOKEN = "laptop_token"
    private const val KEY_HEADER_NAME_1 = "laptop_header_1_name"
    private const val KEY_HEADER_VALUE_1 = "laptop_header_1_value"
    private const val KEY_HEADER_NAME_2 = "laptop_header_2_name"
    private const val KEY_HEADER_VALUE_2 = "laptop_header_2_value"
    private const val ENC_PREFIX = "enc:"
    private const val KEY_STATUS = "last_status"
    private const val KEY_STATUS_AT = "last_status_at"

    /** Live stays the default (Kevin, 2026-11-10: the laptop pipeline is the one to compare). */
    val DEFAULT_ENGINE = VoiceEngine.LIVE

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun engine(context: Context): VoiceEngine =
        parseEngine(prefs(context).getString(KEY_ENGINE, null))

    fun setEngine(context: Context, engine: VoiceEngine) =
        prefs(context).edit().putString(KEY_ENGINE, engine.name).apply()

    fun laptop(context: Context): LaptopVoiceConfig {
        val p = prefs(context)
        val headers = listOfNotNull(
            header(p.getString(KEY_HEADER_NAME_1, null), secret(p.getString(KEY_HEADER_VALUE_1, null))),
            header(p.getString(KEY_HEADER_NAME_2, null), secret(p.getString(KEY_HEADER_VALUE_2, null))),
        )
        return LaptopVoiceConfig(
            baseUrl = p.getString(KEY_URL, null).orEmpty(),
            token = secret(p.getString(KEY_TOKEN, null)),
            headers = headers,
        )
    }

    /**
     * Saves the connection. A blank [token] or header value keeps what is stored (the settings
     * row never shows a saved secret back, so an untouched field must not wipe it); [clearSecrets]
     * wipes them on purpose.
     */
    fun setLaptop(
        context: Context,
        baseUrl: String,
        token: String,
        headers: List<Pair<String, String>>,
        clearSecrets: Boolean = false,
    ) {
        val e = prefs(context).edit()
        e.putString(KEY_URL, baseUrl.trim())
        if (clearSecrets) {
            e.remove(KEY_TOKEN).remove(KEY_HEADER_NAME_1).remove(KEY_HEADER_VALUE_1)
                .remove(KEY_HEADER_NAME_2).remove(KEY_HEADER_VALUE_2)
        }
        if (token.isNotBlank()) e.putString(KEY_TOKEN, seal(token.trim()))
        headers.getOrNull(0)?.let { (n, v) ->
            if (n.isNotBlank()) e.putString(KEY_HEADER_NAME_1, n.trim())
            if (v.isNotBlank()) e.putString(KEY_HEADER_VALUE_1, seal(v.trim()))
        }
        headers.getOrNull(1)?.let { (n, v) ->
            if (n.isNotBlank()) e.putString(KEY_HEADER_NAME_2, n.trim())
            if (v.isNotBlank()) e.putString(KEY_HEADER_VALUE_2, seal(v.trim()))
        }
        e.apply()
    }

    /** The saved header names (shown back; their values never are). */
    fun headerNames(context: Context): List<String> {
        val p = prefs(context)
        return listOf(p.getString(KEY_HEADER_NAME_1, null).orEmpty(), p.getString(KEY_HEADER_NAME_2, null).orEmpty())
    }

    /**
     * The last laptop attempt in words, with its time, for the settings row (Kevin: when Tailscale
     * is off he must be able to see why it used Live). Written at every open and every hand-over.
     */
    fun noteStatus(context: Context, words: String, atMs: Long = System.currentTimeMillis()) {
        prefs(context).edit().putString(KEY_STATUS, words).putLong(KEY_STATUS_AT, atMs).apply()
    }

    fun lastStatus(context: Context): Pair<String, Long>? {
        val p = prefs(context)
        val words = p.getString(KEY_STATUS, null) ?: return null
        return words to p.getLong(KEY_STATUS_AT, 0L)
    }

    /** A log token from the open or a hand-over, as Kevin reads it in Settings. Pure. */
    fun statusWords(token: String): String = when {
        token == "ok" -> "connected"
        token == "not_configured" -> "not set up: add the laptop address and token"
        token == "unauthorized" -> "token rejected by the laptop"
        token.startsWith("busy") -> "laptop busy, used Live"
        token.startsWith("protocol") -> "laptop service is a different version"
        token == "turn_models_unavailable" -> "phone turn detection unavailable, used Live"
        token.startsWith("unreachable") || token.startsWith("connect_") ->
            "laptop not reachable (is Tailscale on, and the laptop awake?), used Live"
        else -> "laptop dropped ($token), switched to Live"
    }

    internal fun parseEngine(raw: String?): VoiceEngine =
        VoiceEngine.entries.firstOrNull { it.name == raw } ?: DEFAULT_ENGINE

    private fun header(name: String?, value: String): Pair<String, String>? =
        if (name.isNullOrBlank() || value.isBlank()) null else name to value

    private fun seal(plain: String): String = KeyVault.encrypt(plain)?.let { ENC_PREFIX + it } ?: plain

    private fun secret(stored: String?): String = when {
        stored.isNullOrBlank() -> ""
        stored.startsWith(ENC_PREFIX) -> KeyVault.decrypt(stored.removePrefix(ENC_PREFIX)).orEmpty()
        else -> stored
    }
}
