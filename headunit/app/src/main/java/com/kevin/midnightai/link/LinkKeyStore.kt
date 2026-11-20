package com.kevin.midnightai.link

import android.content.Context
import android.content.SharedPreferences
import com.kevin.midnightai.ai.CompanionProfile
import com.kevin.midnightai.ai.GeminiKeyProvider
import com.kevin.midnightai.ai.KeyVault
import com.kevin.midnightai.backend.engine.EngineConfig
import com.kevin.midnightai.diag.LogRedactor
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.json.JSONObject

/**
 * The car link's long-term state on this unit (HU-1 T3): the 32-byte link key and what the phone
 * handed over that has no other home (map token, hotspot). Every value is encrypted with the
 * Android Keystore ([KeyVault]); if the Keystore cannot encrypt, NOTHING is stored (unlike the
 * Gemini key's older plaintext fallback): a pairing that cannot be kept safely is not kept.
 */
@OptIn(ExperimentalEncodingApi::class)
class LinkKeyStore(
    context: Context,
    private val encrypt: (String) -> String? = KeyVault::encrypt,
    private val decrypt: (String) -> String? = KeyVault::decrypt,
) : LinkKeySource {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun linkKey(): ByteArray? =
        prefs.getString(KEY_LINK, null)?.let(decrypt)
            ?.let { runCatching { Base64.Default.decode(it) }.getOrNull() }
            ?.takeIf { it.size == LinkCrypto.KEY_BYTES }

    override fun isPaired(): Boolean = prefs.contains(KEY_LINK)

    fun phoneName(): String? = prefs.getString(KEY_PHONE_NAME, null)

    /** Encrypts and saves; false (and nothing written) if the Keystore cannot encrypt. */
    fun save(linkKey: ByteArray, phoneName: String?, mapboxToken: String?, wifiSsid: String?, wifiPassphrase: String?): Boolean {
        val link = encrypt(Base64.Default.encode(linkKey)) ?: return false
        val mapbox = mapboxToken?.let { encrypt(it) ?: return false }
        val wifi = wifiSsid?.let {
            encrypt(JSONObject().put("ssid", it).put("passphrase", wifiPassphrase ?: "").toString()) ?: return false
        }
        return prefs.edit()
            .putString(KEY_LINK, link)
            .putString(KEY_PHONE_NAME, phoneName)
            .putString(KEY_MAPBOX, mapbox)
            .putString(KEY_WIFI, wifi)
            .commit()
    }

    /** "Forget phone": the link key and everything handed over that lives here. */
    fun forget() {
        prefs.edit().clear().commit()
    }

    companion object {
        private const val PREFS = "car_link"
        private const val KEY_LINK = "link_key_enc"
        private const val KEY_PHONE_NAME = "phone_name"
        private const val KEY_MAPBOX = "mapbox_token_enc"
        private const val KEY_WIFI = "wifi_enc"
    }
}

/**
 * Stores a hand-over where each secret already lives on this unit: the engine sign-in in
 * [EngineConfig] (Keystore-encrypted), the Gemini key in [CompanionProfile] (transitional, Kevin's
 * plan: the phone hands it over until the engine brokers sessions), the rest in [LinkKeyStore].
 * All or nothing: on any failure the link is forgotten again, so a half-paired unit never exists.
 * Every stored secret is registered with [LogRedactor] so it can never reach the drive log.
 */
class AndroidHandoverSink(private val context: Context, private val store: LinkKeyStore) : HandoverSink {
    override fun store(handover: Handover, linkKey: ByteArray): String? {
        // The Gemini key's own store falls back to plaintext on a broken Keystore; refuse first.
        if (KeyVault.encrypt("probe") == null) return "this unit's keystore is not working"
        if (!store.save(linkKey, handover.phoneName, handover.mapboxToken, handover.wifiSsid, handover.wifiPassphrase)) {
            return "this unit's keystore is not working"
        }
        val engine = EngineConfig(context)
        handover.engineUrl?.let {
            if (!engine.saveBaseUrl(it)) return rollback("the engine address was not valid")
        }
        handover.engineToken?.let { token ->
            if (!engine.saveSession(token, handover.engineUserId.orEmpty())) return rollback("the engine sign-in could not be stored")
            LogRedactor.addLiteral(token)
        }
        handover.geminiKey?.let { key ->
            CompanionProfile.saveGeminiKey(context, key)
            GeminiKeyProvider.init(context)
            LogRedactor.addLiteral(key)
        }
        handover.mapboxToken?.let { LogRedactor.addLiteral(it) }
        handover.wifiPassphrase?.let { LogRedactor.addLiteral(it) }
        CarLink.keys = store
        return null
    }

    private fun rollback(why: String): String {
        store.forget()
        return why
    }
}
