package com.kevin.legion.ui.help

import com.kevin.legion.service.LiveToolbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Reconciles the generated in-app "What can I do" data (`ui/help/VoiceGuideData.kt`, command-center
 * ticket 09) against the live tool source it must never silently drift from.
 *
 * **Why the count is checked two ways.** `VoiceGuideData` is generated from
 * `tools/voice_guide_copy.py`'s `COPY`/`GROUPS`, which document one entry per CAPABILITY as scraped
 * from every `name = "..."` in `LiveToolbox.kt` (104 as of this file, per `voice_guide.py`'s own
 * `declared_tools()`) - a finer grain than [LiveToolbox.declarations], which trims the dispatched
 * sub-tools (`log_meal`, `list_recent_meals`, ...) out of the live session's own advertised set and
 * replaces them with five `ask_*` dispatchers (69 names). Both counts are real and neither is wrong;
 * they answer different questions. This test pins the first (every tool count matches the source
 * file's own scrape, exactly - `assertEquals`) and checks the second as a coverage guarantee rather
 * than an exact match: every name the live model can actually reach, directly or through a
 * dispatcher, must have an entry in the generated data, so nothing spoken to on the socket is
 * missing from the in-app guide.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceGuideDataTest {

    private val nameRe = Regex("""name = "([a-z_]+)"""")

    /** Mirrors `tools/voice_guide.py`'s own `declared_tools()` - every distinct `name = "..."` in
     * the Kotlin source, independent of which JSONArray it ends up in. */
    private fun scrapedNamesFromSource(): Set<String> {
        val src = java.io.File("src/main/java/com/kevin/legion/service/LiveToolbox.kt")
            .takeIf { it.exists() }
            ?: java.io.File("app/src/main/java/com/kevin/legion/service/LiveToolbox.kt")
        return nameRe.findAll(src.readText()).map { it.groupValues[1] }.toSet()
    }

    @Test
    fun `every entry in VoiceGuideData is a real, distinct tool name`() {
        val allEntries = VoiceGuideData.GROUPS.flatMap { it.entries }
        val names = allEntries.map { it.name }
        assertEquals(
            "VoiceGuideData must carry no duplicate tool name across groups",
            names.size,
            names.toSet().size,
        )
    }

    @Test
    fun `TOOL_COUNT matches the actual number of generated entries`() {
        val actual = VoiceGuideData.GROUPS.sumOf { it.entries.size }
        assertEquals(
            "VoiceGuideData.TOOL_COUNT is stale relative to its own GROUPS - regenerate with " +
                "`python tools/voice_guide.py`",
            actual,
            VoiceGuideData.TOOL_COUNT,
        )
    }

    @Test
    fun `generated entry count reconciles with every name declared in LiveToolbox_kt`() {
        val scraped = scrapedNamesFromSource()
        val generated = VoiceGuideData.GROUPS.flatMap { it.entries }.map { it.name }.toSet()
        assertEquals(
            "VoiceGuideData is stale relative to LiveToolbox.kt's own declared tool names - " +
                "rerun `python tools/voice_guide.py` (it would have caught this itself via --check)",
            scraped,
            generated,
        )
    }

    /**
     * The ticket's own wording ("matches LiveToolbox.declarations()"): every tool name the LIVE
     * session can actually reach - declared directly, or behind one of the five `ask_*`
     * dispatchers - must appear in the generated guide. This is a coverage floor, not an exact
     * count match: [LiveToolbox.declarations] itself hides the dispatched names behind their
     * dispatcher, so its own 69 differs on purpose from the 104 capability-level entries above.
     */
    @Test
    fun `every tool the live session can reach has an entry in VoiceGuideData`() {
        val generated = VoiceGuideData.GROUPS.flatMap { it.entries }.map { it.name }.toSet()
        val live = (0 until LiveToolbox.declarations().length())
            .map { LiveToolbox.declarations().getJSONObject(it).getString("name") }
        for (name in live) {
            assertTrue(
                "\"$name\" is declared to the live Gemini session but has no entry in " +
                    "VoiceGuideData - the in-app guide would be missing something the assistant " +
                    "can actually do",
                name in generated,
            )
        }
        assertFalse("sanity: the live declaration set must not be empty", live.isEmpty())
    }

    @Test
    fun `every group referenced has at least one entry`() {
        for (group in VoiceGuideData.GROUPS) {
            assertTrue("Group \"${group.title}\" has no entries and should not render at all", group.entries.isNotEmpty())
        }
    }

    @Test
    fun `no hands field is blank`() {
        for (entry in VoiceGuideData.GROUPS.flatMap { it.entries }) {
            assertTrue(
                "\"${entry.name}\" has a blank hands field - ADR 0035 requires stating a path or " +
                    "\"Voice only.\", never silence",
                entry.hands.isNotBlank(),
            )
        }
    }
}
