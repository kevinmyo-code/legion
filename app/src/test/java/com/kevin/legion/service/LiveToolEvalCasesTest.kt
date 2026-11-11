package com.kevin.legion.service

import com.kevin.legion.service.tools.LiveSurface
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The OFFLINE half of the tool-routing eval (live cost diet, 2026-11-10). `tools/live_tool_eval/
 * cases.jsonl` holds invented utterances, each naming the old tool it exercises. Here, with no
 * key: every tool the setup carried before the diet is covered at least once, and every case's
 * expected call (direct name, action tool plus action value, or router) exists on the surface.
 * The expected calls are written to `build/live-setup/eval_expected.jsonl` for the LIVE half,
 * `tools/live_tool_eval.py`, which needs a Gemini key and asks a model to pick.
 */
@RunWith(RobolectricTestRunner::class)
class LiveToolEvalCasesTest {

    private fun casesFile(): File = listOf("../tools/live_tool_eval/cases.jsonl", "tools/live_tool_eval/cases.jsonl")
        .map(::File).first { it.isFile }

    @Test
    fun `every old tool is covered and every expected call exists on the surface`() {
        val cases = casesFile().readLines().filter { it.isNotBlank() }.map(::JSONObject)
        val covered = cases.map { it.getString("covers") }.toSet()
        val missing = LiveSurfaceTest.BEFORE_DIET.filter { it !in covered }
        assertTrue("no eval case covers: $missing", missing.isEmpty())

        val decls = LiveToolbox.declarations()
        val declared = (0 until decls.length())
            .associate { decls.getJSONObject(it).let { d -> d.getString("name") to d } }
        val routerOf = LiveToolbox.DISPATCHED.flatMap { (domain, names) -> names.map { it to "ask_$domain" } }.toMap()
        val out = StringBuilder()
        for (c in cases) {
            val old = c.getString("covers")
            val (wire, action) = when {
                old in LiveSurface.ACTION_HOME && LiveSurface.isFolded(old) -> LiveSurface.ACTION_HOME.getValue(old)
                old in LiveSurface.ACTION_HOME -> LiveSurface.ACTION_HOME.getValue(old)
                old in declared -> old to null
                else -> (routerOf[old] ?: error("$old has no home")) to null
            }
            val decl = declared[wire]
            assertTrue("\"${c.getString("say")}\" expects $wire, which is not declared", decl != null)
            var key = "action"
            if (action != null) {
                val tool = LiveSurface.ACTION_TOOLS.first { it.name == wire }
                key = tool.discriminator
                val enum = decl!!.getJSONObject("parameters").getJSONObject("properties")
                    .getJSONObject(key).getJSONArray("enum")
                assertTrue("$wire has no action $action", (0 until enum.length()).any { enum.getString(it) == action })
            }
            out.append(
                JSONObject().put("say", c.getString("say")).put("covers", old).put("tool", wire)
                    .put("key", key).put("action", action ?: JSONObject.NULL).toString(),
            ).append('\n')
        }
        File("build/live-setup").mkdirs()
        File("build/live-setup/eval_expected.jsonl").writeText(out.toString())
    }
}
