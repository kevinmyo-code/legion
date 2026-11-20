package com.kevin.legion.service.cascade

import android.content.Context
import com.kevin.legion.ai.KeyVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Which voice pipeline a conversation runs on (Kevin, 2026-11-10: "i want to be able to switch
 * between the two to compare, a settings in the UI on the phone perhaps").
 *
 * - [CLOUD], the default since 2026-11-15 (Kevin: "replace the sts live pipeline with it"): the
 *   cascade on Gemini's speech-to-text, Gemini text with tools on the phone, and Gemini TTS in the
 *   companion's voice ([CloudSpeech]).
 * - [LAPTOP]: the same cascade with speech on the home laptop's voice service; when the laptop
 *   cannot be used it opens (or carries on) on [CLOUD].
 * - [LIVE]: Gemini Live speech-to-speech. **Retired** ([VoiceEngineSettings.LIVE_RETIRED]): not
 *   offered, not a fallback, and a stored LIVE reads as [CLOUD]. Its code stays in the tree so a
 *   rollback is that one flag.
 */
enum class VoiceEngine { LIVE, LAPTOP, CLOUD }

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
    override fun toString(): String {
        val t = if (token.isBlank()) "none" else "set"
        return "LaptopVoiceConfig(${hostOf(baseUrl)}, token=$t, headers=${headers.size})"
    }

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
@Suppress("TooManyFunctions") // a getter and setter per stored value, as VoiceTurnSettings
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
    private const val KEY_SPECULATE = "speculate"
    private const val KEY_AUDIO_FIRST = "audio_first"
    private const val KEY_LAST_BACKCHANNEL = "last_backchannel"

    /**
     * Audio-first round 1 ([AudioFirstGate], Kevin 2026-11-19): what a conversation does when the
     * switch was never touched. ON at ship (Kevin, 2026-11-20: "audio first default at ship"); a
     * stored choice from the Settings toggle wins; turning it off for everyone is this one line.
     */
    const val AUDIO_FIRST_DEFAULT = true
    private const val KEY_SPEED = "speech_speed"
    private const val KEY_CLOUD_VOICE = "cloud_voice"

    /** Speaking speed (2026-11-17): 1.15x by default, 1.0x to 1.4x, pitch kept ([ReplyPlayer]). */
    const val SPEED_DEFAULT = 1.15f
    const val SPEED_MIN = 1.0f
    const val SPEED_MAX = 1.4f

    /**
     * Gemini Live is retired on the phone (Kevin, 2026-11-15: "replace the sts live pipeline with
     * it"): no conversation, proactive line or Brief me opens it, and nothing falls back to it. The
     * Live code is left in place, unreachable; flipping this (and [DEFAULT_ENGINE]) is the rollback.
     */
    const val LIVE_RETIRED = true

    /** The cloud voice (Kevin, 2026-11-15); was Live until then. */
    val DEFAULT_ENGINE = if (LIVE_RETIRED) VoiceEngine.CLOUD else VoiceEngine.LIVE

    /** The engines Settings offers, in order. */
    val OFFERED: List<VoiceEngine> =
        if (LIVE_RETIRED) listOf(VoiceEngine.CLOUD, VoiceEngine.LAPTOP) else VoiceEngine.entries.toList()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun engine(context: Context): VoiceEngine =
        parseEngine(prefs(context).getString(KEY_ENGINE, null))

    fun setEngine(context: Context, engine: VoiceEngine) =
        prefs(context).edit().putString(KEY_ENGINE, engine.name).apply()

    /**
     * Speculative answers (Kevin approved, 2026-11-15): the text model is asked at a likely end of his
     * turn and the draft is kept only if the turn ends with the same words. Nothing is spoken and no
     * tool runs before that. On by default; off answers only after the turn is confirmed. Read once
     * per conversation.
     */
    fun speculate(context: Context): Boolean = prefs(context).getBoolean(KEY_SPECULATE, true)

    fun setSpeculate(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_SPECULATE, on).apply()

    /** Audio-first round 1 on the cloud voice ([AUDIO_FIRST_DEFAULT] until set). Read once per conversation. */
    fun audioFirst(context: Context): Boolean = prefs(context).getBoolean(KEY_AUDIO_FIRST, AUDIO_FIRST_DEFAULT)

    fun setAudioFirst(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_AUDIO_FIRST, on).apply()

    /** The last backchannel word played, so the next conversation does not open on it ([Backchannel.pick]). */
    fun lastBackchannel(context: Context): String? = prefs(context).getString(KEY_LAST_BACKCHANNEL, null)

    fun setLastBackchannel(context: Context, word: String) =
        prefs(context).edit().putString(KEY_LAST_BACKCHANNEL, word).apply()

    /** The cloud voice's id: [CloudSpeechWire.VOICE] (en-gb-advisor-3) unless another passed [testVoice]. */
    fun cloudVoice(context: Context): String =
        prefs(context).getString(KEY_CLOUD_VOICE, null)?.trim()?.takeIf { it.isNotEmpty() } ?: CloudSpeechWire.VOICE

    fun setCloudVoice(context: Context, voice: String) =
        prefs(context).edit().putString(KEY_CLOUD_VOICE, voice.trim()).apply()

    /**
     * One short test line in [voice] on his key: true only if Google spoke it in that voice (a bad id
     * is a 400, and the fallback to Puck is off here). Settings saves a voice only after this.
     */
    suspend fun testVoice(voice: String, transport: GeminiStreamTransport = OkHttpGemini()): Boolean {
        if (!VOICE_ID.matches(voice.trim())) return false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return try {
            val tts = GeminiTts(transport, scope, voice = voice.trim(), allowFallback = false)
            val outcome = withTimeoutOrNull(VOICE_TEST_MS) { tts.speak("Testing this voice.").outcome() }
            outcome is TtsOutcome.Done
        } finally {
            scope.cancel()
        }
    }

    /** A Gemini voice id: a name ("Puck") or a library id ("en-gb-xxxx"); nothing else is sent. */
    private val VOICE_ID = Regex("[A-Za-z][A-Za-z0-9-]{1,40}")
    private const val VOICE_TEST_MS = 10_000L

    fun speechSpeed(context: Context): Float = clampSpeed(prefs(context).getFloat(KEY_SPEED, SPEED_DEFAULT))

    fun setSpeechSpeed(context: Context, speed: Float) =
        prefs(context).edit().putFloat(KEY_SPEED, clampSpeed(speed)).apply()

    /** Into [SPEED_MIN]..[SPEED_MAX], on 0.05 steps; anything not a number is the default. */
    @Suppress("MagicNumber") // 0.05 steps: 20 to the whole
    fun clampSpeed(speed: Float): Float {
        if (speed.isNaN()) return SPEED_DEFAULT
        val c = speed.coerceIn(SPEED_MIN, SPEED_MAX)
        return Math.round(c * 20f) / 20f
    }

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

    /**
     * A log token from the open or a mid-conversation failure, as Kevin reads it in Settings (Kevin,
     * 2026-11-15: he must see which path ran). A laptop fault goes to the cloud voice ([usedCloud]
     * true, the default); [usedCloud] false says Live took it instead. Pure.
     */
    @Suppress("ReturnCount") // one wording per kind of token
    fun statusWords(token: String, usedCloud: Boolean = true): String {
        if (token == "ok") return "connected"
        // The cloud voice needs the phone's turn detection too: nothing can take this one over.
        if (token == "turn_models_unavailable") return "${laptopWords(token)}, $NO_VOICE"
        val dropped = laptopWords(token).startsWith("laptop dropped")
        if (!usedCloud && LIVE_RETIRED) return "${laptopWords(token)}, $NO_VOICE"
        val to = if (usedCloud) CLOUD_WORDS else "Live"
        return laptopWords(token) + if (dropped) ", switched to $to" else ", used $to"
    }

    /** Where a conversation goes when no cascade voice can take it: nowhere since Live retired. */
    private val NO_VOICE = if (LIVE_RETIRED) "voice not started" else "used Live"

    /** Why the laptop was not used, without where the conversation went (Settings > Test connection). */
    fun laptopWords(token: String): String = when {
        token == "ok" -> "connected"
        token == "not_configured" -> "not set up: add the laptop address and token"
        token == "unauthorized" -> "token rejected by the laptop"
        token.startsWith("busy") -> "laptop busy"
        token.startsWith("protocol") -> "laptop service is a different version"
        token == "turn_models_unavailable" -> "phone turn detection unavailable"
        token.endsWith("http_429") -> "laptop locked out after bad tokens, try again in 30 seconds"
        token.startsWith("unreachable") || token.startsWith("connect_") ->
            "laptop not reachable (is Tailscale on, and the laptop awake?)"
        else -> "laptop dropped ($token)"
    }

    /**
     * The cloud voice failed too, or could not start: Live took the conversation. [laptopToken] is
     * why the laptop was not used (null when the failure was mid-conversation on the cloud voice).
     */
    fun cloudFailedWords(cloudToken: String, laptopToken: String? = null): String {
        val cloud = if (cloudToken == "no_key") {
            "$CLOUD_WORDS unavailable (no Gemini key)"
        } else {
            "$CLOUD_WORDS failed ($cloudToken)"
        }
        val end = if (LIVE_RETIRED) "conversation ended" else "switched to Live"
        return if (laptopToken == null) "$cloud, $end" else "${laptopWords(laptopToken)}; $cloud, $NO_VOICE"
    }

    /** Something on the phone itself failed (mic, turn detection, a crash), whatever voice ran. */
    fun phoneFailedWords(token: String): String =
        "phone voice failed ($token), " + if (LIVE_RETIRED) "conversation ended" else "switched to Live"

    /** How the cloud fallback is named wherever Kevin reads it. */
    const val CLOUD_WORDS = "cloud voice"

    /** A stored LIVE (every install from before 2026-11-15) reads as CLOUD while Live is retired. */
    internal fun parseEngine(raw: String?): VoiceEngine {
        val stored = VoiceEngine.entries.firstOrNull { it.name == raw } ?: DEFAULT_ENGINE
        return if (stored == VoiceEngine.LIVE && LIVE_RETIRED) VoiceEngine.CLOUD else stored
    }

    private fun header(name: String?, value: String): Pair<String, String>? =
        if (name.isNullOrBlank() || value.isBlank()) null else name to value

    private fun seal(plain: String): String = KeyVault.encrypt(plain)?.let { ENC_PREFIX + it } ?: plain

    private fun secret(stored: String?): String = when {
        stored.isNullOrBlank() -> ""
        stored.startsWith(ENC_PREFIX) -> KeyVault.decrypt(stored.removePrefix(ENC_PREFIX)).orEmpty()
        else -> stored
    }
}
