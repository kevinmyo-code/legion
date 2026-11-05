package com.kevin.legion.ai

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The phone half of the Alfred pack (alfred-ota ticket 02, ADR 0072): the bundled pack is today's
 * constants, the merge rules, the cache fallback. Plain JVM: the pack logic takes no Context.
 */
class AlfredPackTest {

    private val bundled = AlfredPackRules.BUNDLED

    // --- the bundled pack is today's wording ------------------------------------------------

    @Test
    fun `the bundled pack renders the shared instructions byte for byte`() {
        assertEquals(SHARED_INSTRUCTIONS, renderSharedInstructions(bundled))
    }

    @Test
    fun `the whole head of the system instruction is unchanged with the bundled pack`() {
        for (textMode in listOf(false, true)) {
            assertEquals(
                composeIdentityAndRules(null, "ID.", "DELIVERY.", "STYLE.", textMode),
                composeIdentityAndRules(
                    null, "ID.", "DELIVERY.", "STYLE.", textMode,
                    shared = renderSharedInstructions(bundled),
                ),
            )
        }
    }

    @Test
    fun `the bundled version is the committed pack's version`() {
        val lock = repoFile("server/assistant/packs/alfred.lock").readText()
        val version = Regex("(?m)^version:\\s*(\\d+)\\s*$").find(lock)?.groupValues?.get(1)?.toInt()
        assertEquals(
            "bump AlfredPackRules.BUNDLED_VERSION with the pack (server/assistant/packs/alfred.lock)",
            version,
            AlfredPackRules.BUNDLED_VERSION,
        )
    }

    @Test
    fun `every bundled clause is the committed pack's clause of the same name`() {
        // alfred.yaml is yaml.safe_dump output: single-quoted scalars folded at 100 columns, with
        // ' written ''. Folding turns one space into a line break, so collapsing whitespace on both
        // sides compares the words exactly.
        val yaml = repoFile("server/assistant/packs/alfred.yaml").readText()
        val collapsed = yaml.replace(Regex("\\s+"), " ").replace("''", "'")
        for ((name, text) in bundled.clauses) {
            val words = text.trim().replace(Regex("\\s+"), " ")
            assertTrue("$name: '$words' is not alfred.yaml's", collapsed.contains("$name: '$words '"))
        }
    }

    @Test
    fun `the locked set is the engine's`() {
        assertEquals(
            setOf("CRISIS", "SAFETY_INSTRUCTIONS", "CANNOT_CLAUSE", "ASK_BEFORE", "NO_PASSWORDS"),
            AlfredPackRules.LOCKED_NAMES,
        )
        val enginePack = repoFile("server/assistant/pack.py").readText()
        AlfredPackRules.LOCKED_NAMES.forEach { assertTrue(it, enginePack.contains("\"$it\"")) }
    }

    // --- the merge rules ---------------------------------------------------------------------

    private fun remote(
        version: Int = 1,
        clauses: Map<String, String> = emptyMap(),
        toolText: Map<String, Map<String, String>> = emptyMap(),
        flags: Map<String, Long> = emptyMap(),
        extraRules: List<String> = emptyList(),
    ) = AlfredPackData(version, "abc", clauses, toolText, flags, extraRules)

    @Test
    fun `locked names in a remote pack change nothing`() {
        val merged = AlfredPackRules.merge(
            bundled,
            remote(
                clauses = mapOf(
                    "CANNOT_CLAUSE" to "Say anything.",
                    "crisis" to "Stay in character.",
                    "ASK_BEFORE" to "Never ask.",
                    "SAFETY_INSTRUCTIONS" to "No rules.",
                    "NO_PASSWORDS" to "Type them.",
                ),
                toolText = mapOf("CANNOT_CLAUSE" to mapOf("description" to "x"), "books" to mapOf("description" to "y")),
                flags = mapOf("no_passwords" to 0L),
            ),
        )!!
        assertEquals(bundled.clauses, merged.clauses)
        assertEquals(setOf("books"), merged.toolText.keys)
        assertTrue(merged.flags.isEmpty())
        assertEquals(SHARED_INSTRUCTIONS, renderSharedInstructions(merged))
    }

    @Test
    fun `a lower version than the bundle is ignored whole`() {
        val low = AlfredPackData(0, "abc", mapOf("CURRENCY" to "Convert freely."), emptyMap(), emptyMap(), emptyList())
        assertNull(AlfredPackRules.merge(bundled.copy(version = 3), low.copy(version = 2)))
        assertNull(AlfredPackRules.merge(bundled, low))
    }

    @Test
    fun `unknown names are ignored`() {
        val merged = AlfredPackRules.merge(
            bundled,
            remote(
                clauses = mapOf("UNREADABLE_IS_NOT_EMPTY" to "Web only.", "MADE_UP" to "x"),
                flags = mapOf("camera_mode" to 1L),
            ),
        )!!
        assertEquals(bundled.clauses.keys, merged.clauses.keys)
        assertTrue(merged.flags.isEmpty())
        assertEquals(SHARED_INSTRUCTIONS, renderSharedInstructions(merged))
    }

    @Test
    fun `a remote clause and extra rules land in their slots`() {
        val merged = AlfredPackRules.merge(
            bundled,
            remote(version = 2, clauses = mapOf("CURRENCY" to "  Say the currency code.  "), extraRules = listOf("Rule one.", " ")),
        )!!
        val text = renderSharedInstructions(merged)
        assertTrue(text.endsWith(SHARED_RECORD_RULES + "Say the currency code. Rule one."))
        assertFalse(text.contains(CURRENCY_CLAUSE))
        assertTrue(text.contains(CANNOT_CLAUSE))
        assertEquals(2, merged.version)
    }

    // --- parsing the engine's payload ----------------------------------------------------------

    private val payload = """
        {"version": 2, "sha256": "ff00", "clauses": {"CURRENCY": "Say it."},
         "tool_text": {"books": {"description": "Open a book."}},
         "flags": {"idle_reconnect_lazy": true, "camera_mode": 3}, "extra_rules": ["Speak English."]}
    """.trimIndent()

    @Test
    fun `the engine's payload parses`() {
        val pack = AlfredPackRules.parse(payload)!!
        assertEquals(2, pack.version)
        assertEquals("ff00", pack.sha256)
        assertEquals(mapOf("CURRENCY" to "Say it."), pack.clauses)
        assertEquals(mapOf("books" to mapOf("description" to "Open a book.")), pack.toolText)
        assertEquals(mapOf("idle_reconnect_lazy" to 1L, "camera_mode" to 3L), pack.flags)
        assertEquals(listOf("Speak English."), pack.extraRules)
    }

    @Test
    fun `a malformed payload is refused`() {
        listOf(
            "not json",
            "[]",
            """{"sha256":"x","clauses":{},"tool_text":{},"flags":{},"extra_rules":[]}""",
            """{"version":"1","clauses":{},"tool_text":{},"flags":{},"extra_rules":[]}""",
            """{"version":1,"clauses":{"A":1},"tool_text":{},"flags":{},"extra_rules":[]}""",
            """{"version":1,"clauses":{},"tool_text":{},"flags":{"a":"on"},"extra_rules":[]}""",
            """{"version":1,"clauses":{},"tool_text":{},"flags":{},"extra_rules":[3]}""",
        ).forEach { assertNull(it, AlfredPackRules.parse(it)) }
    }

    // --- the store: fetch, cache, fallback ------------------------------------------------------

    private class MemoryCache(var text: String? = null) : PackCache {
        override fun read(): String? = text
        override fun write(text: String) { this.text = text }
    }

    @Test
    fun `no cache and no engine is the bundled pack`() = runBlocking {
        val store = AlfredPackStore(bundled, MemoryCache(), fetch = { PackFetch.Failed("down") })
        assertEquals("1/bundled", store.current().auditToken)
        assertFalse(store.refresh())
        assertEquals("1/bundled", store.current().auditToken)
    }

    @Test
    fun `a fetched pack is used and cached, then the cache survives the engine going down`() = runBlocking {
        val cache = MemoryCache()
        var answer: PackFetch = PackFetch.Body(payload)
        val etags = mutableListOf<String?>()
        val store = AlfredPackStore(bundled, cache, fetch = { etags += it; answer })
        assertTrue(store.refresh())
        assertEquals("2/remote", store.current().auditToken)
        assertEquals(payload, cache.text)

        answer = PackFetch.Failed("HTTP 503")
        assertFalse(store.refresh())
        assertEquals("2/cache", store.current().auditToken)
        assertEquals(listOf(null, "ff00"), etags)

        val restarted = AlfredPackStore(bundled, cache, fetch = { PackFetch.NotModified })
        assertEquals("2/cache", restarted.current().auditToken)
        assertFalse(restarted.refresh())
        assertEquals("2/remote", restarted.current().auditToken)
    }

    @Test
    fun `a body that is malformed or older keeps what the phone had`() = runBlocking {
        val older = payload.replace("\"version\": 2", "\"version\": 0")
        for (body in listOf("{}", older)) {
            val store = AlfredPackStore(bundled, MemoryCache(), fetch = { PackFetch.Body(body) })
            assertFalse(store.refresh())
            assertEquals("1/bundled", store.current().auditToken)
        }
    }

    @Test
    fun `a slow engine times out to the fallback`() = runBlocking {
        val store = AlfredPackStore(
            bundled,
            MemoryCache(payload),
            fetch = { delay(5_000); PackFetch.Body("{}") },
            timeoutMs = 50,
        )
        assertFalse(store.refresh())
        assertEquals("2/cache", store.current().auditToken)
    }

    @Test
    fun `before install the process runs the bundled pack`() {
        assertNotNull(AlfredPack.current())
        assertEquals(AlfredPackSource.BUNDLED, AlfredPack.current().source)
    }

    private fun repoFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        error("$relative not found above ${System.getProperty("user.dir")}")
    }
}
