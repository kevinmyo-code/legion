package com.kevin.legion.service

import com.kevin.legion.service.tools.LiveSurface
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The OFFLINE half of the tool-routing eval (live cost diet, 2026-11-10). `tools/live_tool_eval/
 * cases.jsonl` holds invented utterances, each naming the old tool it exercises (`covers`), and
 * optionally the one action that is right (`action`) and documented second right answers (`also`).
 * Here, with no key: every tool the setup carried before the diet is covered at least once, and
 * every accepted call exists on the surface. The accepted calls go to
 * `build/live-setup/eval_expected.jsonl` for `tools/live_tool_eval.py` and
 * `tools/live_setup_probe.py`.
 *
 * Accepted calls for a folded tool are EVERY action that resolves to it (the first-listed action
 * alone was wrong for `mute`, `last_bought`, `book_continue`, `watch_set`: the 2026-11-10 AUTO run
 * marked right answers wrong), narrowed by `action` when the sentence names one.
 */
@RunWith(RobolectricTestRunner::class)
class LiveToolEvalCasesTest {

    private fun casesFile(): File = listOf("../tools/live_tool_eval/cases.jsonl", "tools/live_tool_eval/cases.jsonl")
        .map(::File).first { it.isFile }

    private val declared: Map<String, JSONObject> by lazy {
        val decls = LiveToolbox.declarations()
        (0 until decls.length()).associate { decls.getJSONObject(it).let { d -> d.getString("name") to d } }
    }

    /** Every `tool` or `tool/action` that counts as right for [old], narrowed to [only] when given. */
    private fun acceptFor(old: String, only: String?): List<String> {
        val folded = LiveSurface.ACTION_TOOLS
            .flatMap { t -> t.actions.filter { it.target == old }.map { t.name to it.value } }
            .filter { (tool, _) -> tool in declared }
            .filter { (_, value) -> only == null || value == only }
            .map { (tool, value) -> "$tool/$value" }
        return when {
            folded.isNotEmpty() -> folded
            old in declared -> listOf(old)
            else -> error("$old has no home on the surface")
        }
    }

    private fun exists(call: String): Boolean {
        val (tool, action) = call.split("/").let { it[0] to it.getOrNull(1) }
        val decl = declared[tool] ?: return false
        if (action == null) return true
        val disc = LiveSurface.ACTION_TOOLS.firstOrNull { it.name == tool }?.discriminator ?: "action"
        val enum = decl.getJSONObject("parameters").getJSONObject("properties").getJSONObject(disc).getJSONArray("enum")
        return (0 until enum.length()).any { enum.getString(it) == action }
    }

    @Test
    fun `every old tool is covered and every accepted call exists on the surface`() {
        val cases = casesFile().readLines().filter { it.isNotBlank() }.map(::JSONObject)
        val covered = cases.map { it.getString("covers") }.toSet()
        val missing = LiveSurfaceTest.BEFORE_DIET.filter { it !in covered }
        assertTrue("no eval case covers: $missing", missing.isEmpty())

        val out = StringBuilder()
        for (c in cases) {
            val old = c.getString("covers")
            val accept = acceptFor(old, c.optString("action").ifBlank { null })
            val also = c.optJSONArray("also")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            for (call in accept + also) {
                assertTrue("\"${c.getString("say")}\" accepts $call, which is not on the surface", exists(call))
            }
            val wire = accept.first().substringBefore("/")
            val key = LiveSurface.ACTION_TOOLS.firstOrNull { it.name == wire }?.discriminator ?: "action"
            out.append(
                JSONObject().put("say", c.getString("say")).put("covers", old).put("key", key)
                    .put("accept", JSONArray(accept + also)).toString(),
            ).append('\n')
        }
        File("build/live-setup").mkdirs()
        File("build/live-setup/eval_expected.jsonl").writeText(out.toString())
    }

    @Test
    fun `a sentence that names an action narrows the answer to it`() {
        assertTrue(acceptFor("control_volume", "mute") == listOf("control_music/mute"))
        assertTrue("control_music/volume_up" in acceptFor("control_volume", null))
        assertTrue(acceptFor("bought_log", "last_bought") == listOf("money/last_bought"))
    }
}
