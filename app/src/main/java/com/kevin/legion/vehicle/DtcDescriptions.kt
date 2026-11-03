package com.kevin.legion.vehicle

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Two-tier code -> (name, description) lookup: a bundled seed dictionary of
 * common generic codes (free, offline, ships with the app) plus a disk cache
 * of codes this install has separately learned from the sub-agent (rare
 * manufacturer-specific codes not in the seed). Seed entries never touch the
 * disk cache, so the file only grows with genuinely agent-answered codes.
 *
 * Extracted from [com.kevin.legion.ui.DtcSheet] (2026-07-23,
 * `.scratch/glance-cards/`) so a background voice tool (no Compose context)
 * can do the same fast, offline lookup [com.kevin.legion.ui.DtcSheet]
 * already does - neither [loadSeed] nor [loadLearned] ever calls the
 * diagnostics sub-agent; that escalation stays the caller's decision.
 */
object DtcDescriptions {
    private fun file(context: Context) = File(context.filesDir, "dtc_descriptions.json")

    @Volatile private var seedCache: Map<String, Pair<String, String>>? = null

    /** Bundled dictionary, assets/dtc_descriptions_seed.json. Loaded once, cached in memory. */
    fun loadSeed(context: Context): Map<String, Pair<String, String>> {
        seedCache?.let { return it }
        val loaded = runCatching {
            val text = context.assets.open("dtc_descriptions_seed.json").bufferedReader().use { it.readText() }
            parse(JSONObject(text))
        }.getOrElse {
            // Never silent again (2026-11-03): this asset was missing from the phone app for
            // months and every code read "Not yet described". DtcSeedAssetTest guards the file.
            Log.w(TAG, "trouble-code seed failed to load: $it")
            emptyMap()
        }
        seedCache = loaded
        return loaded
    }

    /** Codes this install has separately asked the sub-agent about. */
    fun loadLearned(context: Context): Map<String, Pair<String, String>> = runCatching {
        parse(JSONObject(file(context).readText()))
    }.getOrDefault(emptyMap())

    fun save(context: Context, map: Map<String, Pair<String, String>>) {
        runCatching {
            val root = JSONObject()
            for ((code, v) in map) {
                root.put(code, JSONObject().put("title", v.first).put("detail", v.second))
            }
            file(context).writeText(root.toString())
        }
    }

    private val learnLock = Mutex()

    /**
     * Merges [entries] into the learned cache and saves it. Read-merge-write under one lock, so the
     * codes card's background lookup and the Faults page's IDENTIFY cannot drop each other's codes.
     * Everything in the learned cache came from the AI sub-agent, never from the code standard.
     */
    suspend fun learn(context: Context, entries: Map<String, Pair<String, String>>) {
        if (entries.isEmpty()) return
        learnLock.withLock {
            withContext(Dispatchers.IO) { save(context, loadLearned(context) + entries) }
        }
    }

    private const val TAG = "DtcDescriptions"

    private fun parse(root: JSONObject): Map<String, Pair<String, String>> = buildMap {
        for (code in root.keys()) {
            val o = root.getJSONObject(code)
            put(code, o.getString("title") to o.getString("detail"))
        }
    }
}
