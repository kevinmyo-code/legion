package com.kevin.midnightai.link

import android.content.Context
import android.content.SharedPreferences
import com.kevin.midnightai.BuildConfig
import com.kevin.midnightai.ai.CompanionProfile
import com.kevin.midnightai.ai.GeminiKeyProvider
import com.kevin.midnightai.ai.KeyVault
import com.kevin.midnightai.backend.engine.EngineConfig
import com.kevin.midnightai.diag.LogRedactor
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.json.JSONObject

/** The engine sign-in store as the car link needs it (Android: [EngineConfig]); a fake in tests. */
interface EngineAccess {
    fun snapshot(): Map<String, String?>
    fun restore(snapshot: Map<String, String?>): Boolean
    /** Encrypts and writes with commit(); false (nothing written) if the Keystore or disk fails. */
    fun saveStrict(baseUrl: String, token: String, userId: String): Boolean
    /** The stored token's ciphertext, to recognise "still the one the link handed over". */
    fun rawToken(): String?
    fun clear(): Boolean
}

/** The Gemini key store as the car link needs it (Android: [CompanionProfile]); a fake in tests. */
interface GeminiAccess {
    fun snapshot(): Map<String, String?>
    fun restore(snapshot: Map<String, String?>): Boolean
    /** No plaintext fallback: false if the Keystore cannot encrypt. */
    fun saveStrict(key: String): Boolean
    fun raw(): String?
    fun clear(): Boolean
}

/** Review H4: the only engine address a handed-over token may ever go to. */
object EnginePolicy {
    /** [constant] if it is a non-blank https URL with a host, else null (no token is accepted). */
    fun handoverEngineUrl(constant: String): String? {
        val c = constant.trim()
        if (!c.startsWith("https://")) return null
        return c.takeIf { it.removePrefix("https://").substringBefore('/').isNotBlank() }
    }
}

/**
 * The car link on this unit (spec 3.7, revision 2): a COMMITTED pairing and, while the phone has
 * not yet spoken under a new key, a PENDING one. Each slot is ONE Keystore-encrypted JSON bundle;
 * a value that came over the link is remembered (by its stored ciphertext) so Forget removes
 * exactly those and never a value typed by hand. No plaintext fallback for anything. Every write
 * that must be atomic uses commit(), and a failed commit restores what was there before, so a
 * failed re-pair leaves the previous pairing exactly as it was.
 *
 * Decrypting calls (keys, commitPending, forget) belong off the main thread.
 */
@OptIn(ExperimentalEncodingApi::class)
class LinkKeyStore(
    context: Context,
    private val engine: EngineAccess,
    private val gemini: GeminiAccess,
    private val engineConstant: String,
    private val encrypt: (String) -> String? = KeyVault::encrypt,
    private val decrypt: (String) -> String? = KeyVault::decrypt,
) : LinkKeySource {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** What the last [stage] could not take, in words (shown on the pairing screen). */
    @Volatile var lastNote: String? = null
        private set

    override fun hasRecord(): Boolean = prefs.contains(KEY_COMMITTED) || prefs.contains(KEY_PENDING)

    override fun phoneName(): String? = prefs.getString(KEY_PHONE_NAME, null)

    private fun open(key: String): JSONObject? =
        prefs.getString(key, null)?.let(decrypt)?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun linkOf(json: JSONObject?): ByteArray? =
        json?.optString("link")?.let { runCatching { Base64.Default.decode(it) }.getOrNull() }
            ?.takeIf { it.size == LinkCrypto.KEY_BYTES }

    override fun keys(): LinkKeys {
        var pending = linkOf(open(KEY_PENDING))
        if (pending == null && prefs.contains(KEY_PENDING)) {
            // An unreadable PENDING is dropped: the COMMITTED pairing (if any) stays in charge.
            prefs.edit().remove(KEY_PENDING).commit()
            pending = null
        }
        val hasCommitted = prefs.contains(KEY_COMMITTED)
        val committed = if (hasCommitted) linkOf(open(KEY_COMMITTED)) else null
        val status = when {
            pending != null -> LinkStatus.PENDING
            hasCommitted && committed == null -> LinkStatus.BROKEN
            committed != null -> LinkStatus.PAIRED
            else -> LinkStatus.NOT_PAIRED
        }
        return LinkKeys(committed, pending, status)
    }

    /** Writes a new pairing as PENDING. Null on success. COMMITTED is never touched here. */
    fun stage(handover: Handover, linkKey: ByteArray): String? {
        val url = EnginePolicy.handoverEngineUrl(engineConstant)
        lastNote = if (handover.engineToken != null && url == null) {
            "The engine sign-in was not kept: this build has no https engine address."
        } else {
            null
        }
        val bundle = JSONObject()
            .put("link", Base64.Default.encode(linkKey))
            .put("engine_token", if (url != null) handover.engineToken else null)
            .put("engine_user_id", handover.engineUserId)
            .put("gemini_key", handover.geminiKey)
            .put("mapbox_token", handover.mapboxToken)
            .put("phone_name", handover.phoneName)
        val enc = encrypt(bundle.toString()) ?: return KEYSTORE
        // Every value the commit will encrypt must be encryptable now, or nothing is kept.
        for (v in listOfNotNull(handover.engineToken, handover.geminiKey, handover.mapboxToken)) {
            if (encrypt(v) == null) return KEYSTORE
        }
        return if (prefs.edit().putString(KEY_PENDING, enc).commit()) null else DISK
    }

    override fun commitPending(): String? {
        val p = open(KEY_PENDING) ?: return HandoverSink.NOTHING_PENDING.also { prefs.edit().remove(KEY_PENDING).commit() }
        val link = linkOf(p) ?: return "pending unreadable".also { prefs.edit().remove(KEY_PENDING).commit() }
        val engineBefore = engine.snapshot()
        val geminiBefore = gemini.snapshot()
        val fromLink = prefs.getString(KEY_FROM_LINK, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        fun undo(why: String): String {
            engine.restore(engineBefore)
            gemini.restore(geminiBefore)
            return why
        }
        val token = p.optString("engine_token").takeIf { it.isNotBlank() }
        val url = EnginePolicy.handoverEngineUrl(engineConstant)
        if (token != null && url != null) {
            if (!engine.saveStrict(url, token, p.optString("engine_user_id"))) return undo(KEYSTORE)
        } else if (fromLink.optString("engine").let { it.isNotBlank() && it == engine.rawToken() }) {
            // The previous pairing's token, not replaced by this one: it goes with the old pairing.
            if (!engine.clear()) return undo(DISK)
        }
        val key = p.optString("gemini_key").takeIf { it.isNotBlank() }
        if (key != null) {
            if (!gemini.saveStrict(key)) return undo(KEYSTORE)
        } else if (fromLink.optString("gemini").let { it.isNotBlank() && it == gemini.raw() }) {
            if (!gemini.clear()) return undo(DISK)
        }
        val committed = JSONObject()
            .put("link", Base64.Default.encode(link))
            .put("mapbox_token", p.optString("mapbox_token").takeIf { it.isNotBlank() })
        val cenc = encrypt(committed.toString()) ?: return undo(KEYSTORE)
        val provenance = JSONObject()
            .put("engine", if (token != null && url != null) engine.rawToken() else null)
            .put("gemini", if (key != null) gemini.raw() else null)
        val ok = prefs.edit()
            .putString(KEY_PHONE_NAME, p.optString("phone_name").takeIf { it.isNotBlank() })
            .putString(KEY_FROM_LINK, provenance.toString())
            .putString(KEY_COMMITTED, cenc)
            .remove(KEY_PENDING)
            .commit()
        link.fill(0)
        if (!ok) return undo(DISK)
        token?.let(LogRedactor::addLiteral)
        key?.let(LogRedactor::addLiteral)
        p.optString("mapbox_token").takeIf { it.isNotBlank() }?.let(LogRedactor::addLiteral)
        return null
    }

    override fun dropPending() {
        prefs.edit().remove(KEY_PENDING).commit()
    }

    override fun forget() {
        val fromLink = prefs.getString(KEY_FROM_LINK, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (fromLink != null) {
            fromLink.optString("engine").takeIf { it.isNotBlank() && it == engine.rawToken() }?.let { engine.clear() }
            fromLink.optString("gemini").takeIf { it.isNotBlank() && it == gemini.raw() }?.let { gemini.clear() }
        }
        prefs.edit().clear().commit()
    }

    /** Secrets that came over the link, for the log redactor at app start (review L5). Off-main. */
    fun secretsForRedactor(): List<String> =
        listOfNotNull(open(KEY_COMMITTED)?.optString("mapbox_token")?.takeIf { it.isNotBlank() })

    companion object {
        private const val PREFS = "car_link"
        private const val KEY_COMMITTED = "committed_enc"
        private const val KEY_PENDING = "pending_enc"
        private const val KEY_PHONE_NAME = "phone_name"
        private const val KEY_FROM_LINK = "from_link"
        private const val KEYSTORE = "this unit's keystore is not working"
        private const val DISK = "this unit could not write its settings"

        /** The app's store over the real EngineConfig, CompanionProfile and build constant. */
        fun forApp(context: Context): LinkKeyStore {
            val app = context.applicationContext
            val engineConfig = EngineConfig(app)
            return LinkKeyStore(
                app,
                engine = object : EngineAccess {
                    override fun snapshot() = engineConfig.snapshot()
                    override fun restore(snapshot: Map<String, String?>) = engineConfig.restore(snapshot)
                    override fun saveStrict(baseUrl: String, token: String, userId: String) =
                        engineConfig.saveSessionStrict(baseUrl, token, userId)
                    override fun rawToken() = engineConfig.rawToken()
                    override fun clear() = engineConfig.restore(engineConfig.snapshot().mapValues { (k, v) -> if (k == "base_url") v else null })
                },
                gemini = object : GeminiAccess {
                    override fun snapshot() = CompanionProfile.geminiSnapshot(app)
                    override fun restore(snapshot: Map<String, String?>) = CompanionProfile.restoreGemini(app, snapshot)
                        .also { GeminiKeyProvider.init(app) }
                    override fun saveStrict(key: String) = CompanionProfile.saveGeminiKeyStrict(app, key)
                        .also { GeminiKeyProvider.init(app) }
                    override fun raw() = CompanionProfile.geminiSnapshot(app)["gemini_api_key_enc"]
                    override fun clear() = CompanionProfile.restoreGemini(app, CompanionProfile.geminiSnapshot(app).mapValues { null })
                        .also { GeminiKeyProvider.init(app) }
                },
                engineConstant = BuildConfig.ENGINE_BASE_URL,
            )
        }
    }
}

/** The pairing session's sink over [LinkKeyStore]; commits rebuild the feed through [CarLink]. */
class AndroidHandoverSink(private val context: Context, private val store: LinkKeyStore) : HandoverSink {
    override fun stage(handover: Handover, linkKey: ByteArray): String? =
        store.stage(handover, linkKey).also { if (it == null) CarLink.changed(context) }

    override fun commit(): String? = store.commitPending().also { CarLink.changed(context) }

    override fun dropPending() {
        store.dropPending()
        CarLink.changed(context)
    }
}
