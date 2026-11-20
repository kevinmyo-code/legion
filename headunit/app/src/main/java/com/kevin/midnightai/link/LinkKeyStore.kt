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
    fun saveStrict(baseUrl: String, token: String, userId: String, vehicleId: String?): Boolean
    /** The stored token's ciphertext, to recognise "still the one the link handed over". */
    fun rawToken(): String?

    /** A member is signed in on the unit (the car's token then waits in reserve, review F1). */
    fun memberSignedIn(): Boolean = false
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
    private val ownerPinPrefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(OwnerPin.PREFS, Context.MODE_PRIVATE)

    /** What the last [stage] could not take, in words (shown on the pairing screen). */
    @Volatile var lastNote: String? = null
        private set

    override fun hasRecord(): Boolean = prefs.contains(KEY_COMMITTED) || prefs.contains(KEY_PENDING)

    override fun phoneName(): String? = prefs.getString(KEY_PHONE_NAME, null)

    /**
     * Decrypts one slot, retrying a few times: cheap keymaster HALs fail transiently (review N4),
     * and a read failure must never be mistaken for "no pairing".
     */
    private fun open(key: String): JSONObject? {
        val raw = prefs.getString(key, null) ?: return null
        repeat(READ_TRIES) {
            raw.let(decrypt)?.let { plain -> return runCatching { JSONObject(plain) }.getOrNull() }
        }
        return null
    }

    private fun linkOf(json: JSONObject?): ByteArray? =
        json?.optString("link")?.let { runCatching { Base64.Default.decode(it) }.getOrNull() }
            ?.takeIf { it.size == LinkCrypto.KEY_BYTES }

    override fun keys(): LinkKeys {
        // An unreadable PENDING is never deleted here (review N4): it stays on disk for the next
        // read, and the COMMITTED pairing (if any) stays in charge meanwhile.
        val pending = linkOf(open(KEY_PENDING))
        val hasCommitted = prefs.contains(KEY_COMMITTED)
        val committed = if (hasCommitted) linkOf(open(KEY_COMMITTED)) else null
        // Review P2: a PENDING record that did not open beside a readable COMMITTED is "waiting" (the
        // true state), and the receiver reads it again with backoff; COMMITTED stays in charge.
        val pendingUnreadable = pending == null && prefs.contains(KEY_PENDING) && committed != null
        val status = when {
            pending != null || pendingUnreadable -> LinkStatus.PENDING
            prefs.contains(KEY_PENDING) && committed == null -> LinkStatus.BROKEN
            hasCommitted && committed == null -> LinkStatus.BROKEN
            committed != null -> LinkStatus.PAIRED
            else -> LinkStatus.NOT_PAIRED
        }
        return LinkKeys(committed, pending, status, pendingUnreadable)
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
            .put("engine_vehicle_id", handover.engineVehicleId)
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
        if (!prefs.contains(KEY_PENDING)) return HandoverSink.NOTHING_PENDING
        // Unreadable right now: keep it and say so; the caller retries or fails visibly (N4).
        val p = open(KEY_PENDING) ?: return PENDING_UNREADABLE
        val link = linkOf(p) ?: return PENDING_UNREADABLE
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
            if (engine.memberSignedIn()) lastNote = MEMBER_KEPT
            val vehicle = p.optString("engine_vehicle_id").takeIf { it.isNotBlank() }
            if (!engine.saveStrict(url, token, p.optString("engine_user_id"), vehicle)) return undo(KEYSTORE)
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

    /** True while the engine sign-in in use is the one the car link handed over. Off-main. */
    fun holdsLinkEngineToken(): Boolean {
        val fromLink = prefs.getString(KEY_FROM_LINK, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return false
        return fromLink.optString("engine").let { it.isNotBlank() && it == engine.rawToken() }
    }

    /**
     * Forget, and the full reset for a forgotten owner PIN: the pairing, every value the link
     * handed over and the owner PIN go together (2026-11-20), so nothing secret survives it.
     */
    override fun forget() {
        val fromLink = prefs.getString(KEY_FROM_LINK, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (fromLink != null) {
            fromLink.optString("engine").takeIf { it.isNotBlank() && it == engine.rawToken() }?.let { engine.clear() }
            fromLink.optString("gemini").takeIf { it.isNotBlank() && it == gemini.raw() }?.let { gemini.clear() }
        }
        // The PIN goes LAST, and only once the pairing is really gone (review L-a): a disk failure
        // part way must never leave a pairing with no PIN in front of it.
        if (prefs.edit().clear().commit()) ownerPinPrefs.edit().clear().commit()
    }

    /** The Mapbox public token the phone handed over (HU-1 T5), or null. Off-main (Keystore). */
    fun mapboxToken(): String? = open(KEY_COMMITTED)?.optString("mapbox_token")?.takeIf { it.isNotBlank() }

    /**
     * The same read, telling a real absence (no committed pairing, or no map token in it) from a
     * bundle that could not be read right now (review C-1). Read only. Off-main (Keystore).
     */
    fun mapboxTokenRead(): com.kevin.midnightai.navigation.TokenRead {
        if (!prefs.contains(KEY_COMMITTED)) return com.kevin.midnightai.navigation.TokenRead.Absent
        val bundle = open(KEY_COMMITTED) ?: return com.kevin.midnightai.navigation.TokenRead.Unreadable
        val t = bundle.optString("mapbox_token").takeIf { it.isNotBlank() }
        return if (t != null) com.kevin.midnightai.navigation.TokenRead.Present(t) else com.kevin.midnightai.navigation.TokenRead.Absent
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
        private const val PENDING_UNREADABLE = "the new pairing could not be read right now"
        private const val READ_TRIES = 3
        const val MEMBER_KEPT =
            "Your member sign-in on this unit stays in use. The car's own sign-in from the phone is kept in reserve " +
                "and is used only if you sign out under Settings, Engine."

        /** The Gemini slot the car link writes and Forget clears. */
        fun geminiLinkAccess(context: Context): GeminiAccess {
            val app = context.applicationContext
            // Its OWN slot (2026-11-20): the phone's key never overwrites a key typed on the unit,
            // wins over it while paired, and Forget removes only this slot.
            return object : GeminiAccess {
                override fun snapshot() = CompanionProfile.geminiLinkSnapshot(app)
                override fun restore(snapshot: Map<String, String?>) = CompanionProfile.restoreGemini(app, snapshot)
                    .also { GeminiKeyProvider.init(app) }
                override fun saveStrict(key: String) = CompanionProfile.saveGeminiLinkKeyStrict(app, key)
                    .also { GeminiKeyProvider.init(app) }
                override fun raw() = CompanionProfile.geminiLinkSnapshot(app).values.firstOrNull()
                override fun clear() = CompanionProfile.restoreGemini(app, CompanionProfile.geminiLinkSnapshot(app).mapValues { null })
                    .also { GeminiKeyProvider.init(app) }
            }
        }

        /** The engine sign-in as the car link writes and clears it. */
        fun engineLinkAccess(engineConfig: EngineConfig): EngineAccess = object : EngineAccess {
            override fun snapshot() = engineConfig.snapshot()
            override fun restore(snapshot: Map<String, String?>) = engineConfig.restore(snapshot)
            // A token that came over the link is the car's own sign-in (CarLinkScope).
            override fun saveStrict(baseUrl: String, token: String, userId: String, vehicleId: String?) =
                engineConfig.saveSessionStrict(baseUrl, token, userId, carLink = true, vehicleId = vehicleId)
            // The car's token wherever it is kept (in use, or in reserve behind a member sign-in).
            override fun rawToken() = engineConfig.carLinkRawToken()
            override fun clear() = engineConfig.clearCarLink()
            override fun memberSignedIn() = engineConfig.signIn() == com.kevin.midnightai.backend.engine.EngineSignIn.MEMBER
        }

        /** The app's store over the real EngineConfig, CompanionProfile and build constant. */
        fun forApp(context: Context): LinkKeyStore {
            val app = context.applicationContext
            val engineConfig = EngineConfig(app)
            return LinkKeyStore(
                app,
                engine = engineLinkAccess(engineConfig),
                gemini = geminiLinkAccess(app),
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
