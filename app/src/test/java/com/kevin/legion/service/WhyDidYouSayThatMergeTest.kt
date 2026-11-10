package com.kevin.legion.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Integration 2026-11-10: `feat/watches` trimmed `why_did_you_say_that`'s description to pay for
 * `watch`, and `feat/proactive-voice` (ADR 0082) gave it a `stop` argument, the voice half of
 * "stop the morning brief" / "stop the home brief". The merged declaration must still tell the
 * model about `stop`, in the description AND on the parameter; the empty-result sentence the
 * watches trim cut stays cut (the tool result already says it). Robolectric for `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
class WhyDidYouSayThatMergeTest {

    private val decl = LiveToolbox.declarations().let { fns ->
        (0 until fns.length()).map { fns.getJSONObject(it) }.single { it.getString("name") == "why_did_you_say_that" }
    }

    @Test
    fun `the merged description and its stop parameter both name stop`() {
        val description = decl.getString("description")
        assertTrue(description, description.contains("With stop, turns that spoken line off for good"))
        val stop = decl.getJSONObject("parameters").getJSONObject("properties").getJSONObject("stop")
        assertTrue(stop.getString("description").contains("morning brief"))
        assertTrue(stop.getString("description").contains("home brief"))
    }

    @Test
    fun `the sentence the result already says stays out of the setup payload`() {
        assertFalse(decl.getString("description").contains("Nothing returned"))
    }
}
