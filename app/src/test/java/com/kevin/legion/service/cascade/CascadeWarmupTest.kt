@file:Suppress("MaxLineLength") // test fixtures: invented JSON and wire frames read best on one line

package com.kevin.legion.service.cascade

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.LocalDate
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Prepare-ahead (voice-fast step 2): the prepared prompt's freshness, the context cache's state for
 * the open line, kept scripted lines on the laptop voice, and how the warm-up is wired (from source:
 * the session needs a phone to construct). Invented content only.
 */
@RunWith(RobolectricTestRunner::class)
class CascadeWarmupTest {

    private val prefix = CascadePrefix("model-x", "Invented instruction.", JSONArray())
    private val split = CascadePrompt.Split("Invented instruction.", "Today's date is invented.")

    @Test
    fun `a prepared prompt is used only while fresh and only on the day it was built`() {
        val day = LocalDate.of(2026, 1, 2)
        val p = CascadePrep.Prepared(split, prefix, builtAtMs = 1_000_000, day = day)
        assertTrue(CascadePrep.usable(p, 1_000_000 + CascadePrep.FRESH_MS - 1, day))
        assertFalse(CascadePrep.usable(p, 1_000_000 + CascadePrep.FRESH_MS, day))
        assertFalse("the date paragraph would be yesterday's", CascadePrep.usable(p, 1_000_001, day.plusDays(1)))
        assertFalse(CascadePrep.usable(null, 0, day))
    }

    @Test
    fun `the open line says whether this prompt's cache is reused`() = runBlocking<Unit> {
        var now = 0L
        val store = ContextCacheStore({ CacheCreateOutcome.Created("cachedContents/c1", 12_000) }, CoroutineScope(Dispatchers.Unconfined), now = { now })
        assertEquals("none", store.state(prefix))
        store.warm(prefix)
        assertEquals("live left_s=${CascadeRequests.CACHE_TTL_S}", store.state(prefix))
        assertEquals("other_prompt", store.state(prefix.copy(systemInstruction = "Another invented instruction.")))
        now = CascadeRequests.CACHE_TTL_S * 1_000 - ContextCacheStore.MARGIN_MS
        assertEquals("expiring", store.state(prefix))
    }

    @Test
    fun `the cache name survives a restart through its memo`() {
        val memo = ContextCacheStore.Memo.InMemory()
        ContextCacheStore({ CacheCreateOutcome.Created("cachedContents/c9") }, CoroutineScope(Dispatchers.Unconfined), now = { 0L }, memo = memo).warm(prefix)
        val after = ContextCacheStore({ error("must not create again") }, CoroutineScope(Dispatchers.Unconfined), now = { 1_000L }, memo = memo)
        assertEquals("cachedContents/c9", after.peek(prefix))
    }

    @Test
    fun `the cache lives twenty minutes, not an idle hour`() {
        assertEquals(1_200L, CascadeRequests.CACHE_TTL_S)
        assertEquals("1200s", CascadeRequests.cacheCreate(prefix).getString("ttl"))
    }

    @Test
    fun `a scripted line the laptop has said once plays from memory, per voice`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sent = Collections.synchronizedList(mutableListOf<JSONObject>())
        lateinit var phone: WireListener
        val wire = WireConnector { _, _, listener ->
            phone = listener
            object : WireSocket {
                override fun sendText(text: String): Boolean {
                    val m = JSONObject(text)
                    sent.add(m)
                    when (m.getString("type")) {
                        "hello" -> listener.onText(JSONObject().put("type", "ready").put("protocol", 1).put("tts", JSONObject().put("model", "voice-x").put("default_voice", "v1")).toString())
                        "tts.speak" -> {
                            val id = m.getInt("id")
                            listener.onBinary(LaptopVoiceConnection.frame(LaptopVoiceConnection.KIND_TTS, id, byteArrayOf(1, 2, 3, 4)))
                            listener.onText(JSONObject().put("type", "tts.done").put("id", id).put("audio_ms", 1).toString())
                        }
                    }
                    return true
                }
                override fun sendBinary(bytes: ByteArray) = true
                override fun close(code: Int, reason: String) = Unit
            }
        }
        val kept = ConcurrentHashMap<String, ByteArray>()
        val c = LaptopVoiceConnection(wire, LaptopVoiceConfig("http://127.0.0.1:8765", "invented"), "test", scope, keepLines = setOf("Here."), kept = kept)
        c.open(1_000)
        val first = c.speak("Here.")
        assertEquals(listOf(1, 2, 3, 4), withTimeout(1_000) { first.frames.toList() }.flatMap { it.toList() }.map { it.toInt() })
        assertEquals(1, sent.count { it.getString("type") == "tts.speak" })
        val second = c.speak("Here.")
        assertEquals(listOf(1, 2, 3, 4), withTimeout(1_000) { second.frames.toList() }.flatMap { it.toList() }.map { it.toInt() })
        assertTrue(second.outcome() is TtsOutcome.Done)
        assertEquals("nothing went to the laptop the second time", 1, sent.count { it.getString("type") == "tts.speak" })
        c.speak("Not a scripted line.").frames.toList()
        assertEquals(2, sent.count { it.getString("type") == "tts.speak" })
        assertTrue(kept.keys.single().contains("voice-x|v1"))
    }

    @Test
    fun `a wake near miss cools the laptop connection for a fixed time`() {
        CascadeWarmup.lastMissAtMs = 0L
        assertFalse("no miss yet", CascadeWarmup.missCooling(1_000_000))
        CascadeWarmup.onWakeMissed()
        val at = CascadeWarmup.lastMissAtMs
        assertTrue(CascadeWarmup.missCooling(at + 1))
        assertTrue(CascadeWarmup.missCooling(at + CascadeWarmup.MISS_COOLDOWN_MS - 1))
        assertFalse(CascadeWarmup.missCooling(at + CascadeWarmup.MISS_COOLDOWN_MS))
        CascadeWarmup.lastMissAtMs = 0L
    }

    @Test
    fun `the session drops a failed draft at confirmation and the cloud loop is capped`() {
        val s = source("service/cascade/CascadeSession.kt")
        assertTrue(s.contains("answer.unusableReason != null"))
        assertTrue(s.contains("u.cloudDraftBudgetLeft"))
        assertTrue(s.contains("u.spendCloudDraft()"))
    }

    // --- wiring, read from source --------------------------------------------------------------

    @Test
    fun `the laptop connects while the mic and turn models get ready, and an abandoned open lets it go`() {
        val s = source("service/cascade/CascadeSession.kt")
        val open = s.substring(s.indexOf("private suspend fun openNow"))
        val prewarm = open.indexOf("LaptopPrewarm.start(ctx, \"open\")")
        assertTrue(prewarm in 0 until open.indexOf("micHold.take"))
        assertTrue(open.indexOf("LaptopPrewarm.cancel(\"open_aborted\")") in 0 until open.indexOf("openBackend("))
        val openBackend = s.substring(s.indexOf("private suspend fun openBackend"))
        assertTrue(openBackend.indexOf("takePrewarmed() ?: tryLaptop(") in 0 until openBackend.indexOf("CascadeRules.nextAfter("))
        val release = s.substring(s.indexOf("private suspend fun releaseNow"))
        assertTrue(release.contains("CascadeWarmup.conversationOpen = false"))
        assertTrue(release.contains("CascadePrep.prepare(ctx, \"close\")"))
        assertTrue(s.contains("CascadePrep.fresh()"))
    }

    @Test
    fun `no idle socket - a prewarmed connection nobody takes is closed`() {
        val w = source("service/cascade/CascadeWarmup.kt")
        assertTrue(w.contains("if (pending === a) cancel(\"not_taken\")"))
        assertTrue(LaptopPrewarm.HOLD_MS <= 5_000L)
    }

    @Test
    fun `the wake word's onset warms, a near miss lets go`() {
        val d = source("service/OpenWakeWordDetector.kt")
        assertTrue(d.contains("CascadeWarmup.onWakeOnset()"))
        val nearMiss = d.substring(d.indexOf("is OwwDecider.Decision.NearMiss"))
        assertTrue(nearMiss.substring(0, 200).contains("CascadeWarmup.onWakeMissed()"))
        assertTrue(source("ai/AriaBrain.kt").contains("CascadePrep.invalidate()"))
        assertTrue(source("service/LiveSessionController.kt").contains("CascadeWarmup.install(appContext)"))
    }

    private fun source(path: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("app/src/main/java/com/kevin/legion", "src/main/java/com/kevin/legion").forEach {
                val f = File(File(dir, it), path)
                if (f.isFile) return f.readText()
            }
            dir = dir.parentFile
        }
        error("source $path not found")
    }
}
