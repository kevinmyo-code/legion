package com.kevin.legion.ai

import android.content.Context
import com.kevin.legion.backend.engine.ConditionalGet
import com.kevin.legion.backend.engine.EngineConfig
import com.kevin.legion.backend.engine.EngineHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * One Alfred pack: versioned wording the engine serves at `GET /api/assistant/pack` (alfred-ota
 * tickets 01 and 02, ADR 0072). [sha256] is the engine's ETag; the bundled pack has none.
 * [flags] hold a bool as 0 or 1 and a whole number as itself.
 */
data class AlfredPackData(
    val version: Int,
    val sha256: String?,
    val clauses: Map<String, String>,
    val toolText: Map<String, Map<String, String>>,
    val flags: Map<String, Long>,
    val extraRules: List<String>,
)

/** Where the pack the phone runs came from. [label] is what LIVE_AUDIT's `pack=` says. */
enum class AlfredPackSource(val label: String) { REMOTE("remote"), CACHE("cache"), BUNDLED("bundled") }

/** The pack in use and where it came from. */
data class LoadedPack(val pack: AlfredPackData, val source: AlfredPackSource) {
    /** `<version>/<source>`, the LIVE_AUDIT `session_start` token. */
    val auditToken: String get() = "${pack.version}/${source.label}"
}

/**
 * The pack's rules on the phone, pure so a plain JVM test covers them.
 *
 * **The locked set (map decision 2, Kevin 2026-11-05)** is the engine's `pack.LOCKED_NAMES`,
 * compared upper-cased: a compromised engine must not talk Alfred out of the crisis route, the
 * safety rules, the honesty clause, the ask-before rule or the never-type-passwords rule. The
 * engine refuses a pack that names one; the phone drops them anyway, as a clause, a tool override
 * or a flag, so those always come from the app.
 */
object AlfredPackRules {

    val LOCKED_NAMES: Set<String> =
        setOf("CRISIS", "SAFETY_INSTRUCTIONS", "CANNOT_CLAUSE", "ASK_BEFORE", "NO_PASSWORDS")

    /** Flags the app has code for. A remote flag not named here or in the bundle is ignored. */
    val KNOWN_FLAGS: Set<String> = emptySet()

    /** The pack version this APK's constants are (pinned to `server/assistant/packs/alfred.lock`). */
    const val BUNDLED_VERSION = 1

    fun isLocked(name: String): Boolean = name.uppercase() in LOCKED_NAMES

    /**
     * The pack this APK was built with: today's constants in AriaBrain.kt, the four clauses the
     * phone's prompt has a slot for. The engine's pack also carries web-only clauses
     * (`UNREADABLE_IS_NOT_EMPTY`); the phone has no slot for them, so they are unknown names here.
     */
    val BUNDLED: AlfredPackData = AlfredPackData(
        version = BUNDLED_VERSION,
        sha256 = null,
        clauses = mapOf(
            "ASSISTANT_FRAME" to ASSISTANT_FRAME,
            "PROACTIVE_CLAUSE" to PROACTIVE_CLAUSE,
            "RECORD_FACTS" to RECORD_FACTS,
            "CURRENCY" to CURRENCY_CLAUSE,
        ),
        toolText = emptyMap(),
        flags = emptyMap(),
        extraRules = emptyList(),
    )

    /**
     * [remote] laid over [bundled] by name, or null when the remote pack is to be ignored whole
     * (a LOWER version than the bundle, so an old engine never reverts a newer app).
     *
     * - A clause replaces the bundled one only when the bundle has that name and it is not locked.
     * - A tool override is kept unless it is locked (ticket 03 applies them).
     * - A flag is kept when the bundle or [KNOWN_FLAGS] names it and it is not locked.
     * - The extra rules are the remote pack's.
     */
    fun merge(bundled: AlfredPackData, remote: AlfredPackData): AlfredPackData? {
        if (remote.version < bundled.version) return null
        val clauses = bundled.clauses + remote.clauses.filter { (name, text) ->
            name in bundled.clauses && !isLocked(name) && text.isNotBlank()
        }
        val flags = bundled.flags + remote.flags.filterKeys {
            (it in bundled.flags || it in KNOWN_FLAGS) && !isLocked(it)
        }
        return AlfredPackData(
            version = remote.version,
            sha256 = remote.sha256,
            clauses = clauses,
            toolText = remote.toolText.filterKeys { !isLocked(it) },
            flags = flags,
            extraRules = remote.extraRules.filter { it.isNotBlank() },
        )
    }

    /** The engine's JSON payload, or null when it is not the shape `pack_views.py` serves. */
    @Suppress("ReturnCount") // one early refusal per malformed part
    fun parse(text: String): AlfredPackData? {
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val version = (root["version"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
            ?.takeIf { it in 1..Int.MAX_VALUE }?.toInt() ?: return null
        val sha = (root["sha256"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val clauses = stringMap(root["clauses"]) ?: return null
        val toolObj = root["tool_text"] as? JsonObject ?: return null
        val toolText = toolObj.mapValues { (_, v) -> stringMap(v) ?: return null }
        val flagObj = root["flags"] as? JsonObject ?: return null
        val flags = flagObj.mapValues { (_, v) ->
            val p = v as? JsonPrimitive ?: return null
            if (p.isString) return null
            p.booleanOrNull?.let { if (it) 1L else 0L } ?: p.longOrNull ?: return null
        }
        val rules = (root["extra_rules"] as? JsonArray ?: return null).map { el ->
            (el as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        }
        return AlfredPackData(version, sha, clauses, toolText, flags, rules)
    }

    private fun stringMap(el: JsonElement?): Map<String, String>? {
        val obj = el as? JsonObject ?: return null
        return obj.mapValues { (_, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null }
    }
}

/**
 * The shared rules with [pack]'s clauses in their slots. The layer order stays in code; only the
 * slot text comes from the pack. Each slot is trimmed and given one trailing space, as the
 * constants are, and the last ([CURRENCY_CLAUSE]'s) none, so the bundled pack renders
 * [SHARED_INSTRUCTIONS] byte for byte (`AlfredPackTest`). The pack's extra rules follow, before the
 * safety text [composeIdentityAndRules] appends last.
 */
internal fun renderSharedInstructions(pack: AlfredPackData): String {
    fun slot(name: String): String =
        (pack.clauses[name] ?: AlfredPackRules.BUNDLED.clauses.getValue(name)).trim()
    val base = slot("ASSISTANT_FRAME") + " " +
        ENGLISH_CLAUSE +
        SHARED_TOOL_RULES +
        slot("PROACTIVE_CLAUSE") + " " +
        slot("RECORD_FACTS") + " " +
        SHARED_RECORD_RULES +
        slot("CURRENCY")
    val extras = pack.extraRules.map { it.trim() }.filter { it.isNotEmpty() }
    return if (extras.isEmpty()) base else base + " " + extras.joinToString(" ")
}

/** What one fetch of the pack came back as. */
sealed interface PackFetch {
    data class Body(val text: String) : PackFetch
    data object NotModified : PackFetch
    data class Failed(val why: String) : PackFetch
}

/** Where the last good remote pack is kept between runs. */
interface PackCache {
    fun read(): String?
    fun write(text: String)
}

/** The cache as one file in the app's private storage. */
class FilePackCache(private val file: File) : PackCache {
    override fun read(): String? = runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull()
    override fun write(text: String) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
        }
    }
}

/**
 * The pack the phone runs, and the one fetch that may change it (ticket 02).
 *
 * Starts from the cached pack if one is on disk and still merges over the bundle, else the
 * bundled pack. [refresh] asks the engine with the cached ETag inside [timeoutMs]: a new body is
 * merged, cached and used; a 304 confirms the cache; anything else (engine down, a 4xx or 5xx, a
 * malformed body, a lower version, the timeout) keeps what it had. Nothing is spoken about it;
 * the audit line records the source (§7's graceful degradation).
 */
class AlfredPackStore(
    private val bundled: AlfredPackData,
    private val cache: PackCache,
    private val fetch: suspend (etag: String?) -> PackFetch,
    private val timeoutMs: Long = FETCH_TIMEOUT_MS,
) {
    @Volatile private var loaded: LoadedPack = fromCache() ?: LoadedPack(bundled, AlfredPackSource.BUNDLED)

    fun current(): LoadedPack = loaded

    /** Returns true when the pack's wording changed, so the caller drops any prompt built from the old one. */
    suspend fun refresh(): Boolean {
        val before = loaded.pack
        val cachedEtag = cachedRemote()?.sha256
        val result = withTimeoutOrNull(timeoutMs) { fetch(cachedEtag) } ?: PackFetch.Failed("timed out")
        loaded = when (result) {
            is PackFetch.Body -> {
                val merged = AlfredPackRules.parse(result.text)?.let { AlfredPackRules.merge(bundled, it) }
                if (merged != null) {
                    cache.write(result.text)
                    LoadedPack(merged, AlfredPackSource.REMOTE)
                } else {
                    fallback()
                }
            }
            PackFetch.NotModified -> fromCache()?.copy(source = AlfredPackSource.REMOTE) ?: fallback()
            is PackFetch.Failed -> fallback()
        }
        return loaded.pack != before
    }

    private fun fallback(): LoadedPack = fromCache() ?: LoadedPack(bundled, AlfredPackSource.BUNDLED)

    private fun cachedRemote(): AlfredPackData? = cache.read()?.let { AlfredPackRules.parse(it) }

    private fun fromCache(): LoadedPack? =
        cachedRemote()?.let { AlfredPackRules.merge(bundled, it) }?.let { LoadedPack(it, AlfredPackSource.CACHE) }

    companion object {
        const val FETCH_TIMEOUT_MS = 2_000L
        const val PATH = "/api/assistant/pack"
    }
}

/**
 * The process's one [AlfredPackStore], until Hilt lands. [install] wires the engine fetch and the
 * on-disk cache at app start; before it (or under a test) [current] is the bundled pack.
 */
object AlfredPack {
    @Volatile private var store: AlfredPackStore? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun current(): LoadedPack = store?.current() ?: LoadedPack(AlfredPackRules.BUNDLED, AlfredPackSource.BUNDLED)

    fun install(context: Context) {
        if (store != null) return
        val app = context.applicationContext
        val http = EngineHttp(EngineConfig(app))
        store = AlfredPackStore(
            bundled = AlfredPackRules.BUNDLED,
            cache = FilePackCache(File(app.filesDir, "alfred_pack.json")),
            fetch = { etag -> fetchFrom(http, etag) },
        )
    }

    /** Fetch in the background; [onChanged] runs only when the wording actually changed. */
    fun refreshAsync(onChanged: () -> Unit = {}) {
        val s = store ?: return
        scope.launch { if (runCatching { s.refresh() }.getOrDefault(false)) onChanged() }
    }

    private suspend fun fetchFrom(http: EngineHttp, etag: String?): PackFetch {
        if (!http.isUsable()) return PackFetch.Failed("no engine")
        return http.getConditional(AlfredPackStore.PATH, mapOf("client" to "phone"), etag).fold(
            onSuccess = {
                when (it) {
                    is ConditionalGet.Fresh -> PackFetch.Body(it.body)
                    ConditionalGet.NotModified -> PackFetch.NotModified
                }
            },
            onFailure = { PackFetch.Failed(it.message ?: "failed") },
        )
    }
}
