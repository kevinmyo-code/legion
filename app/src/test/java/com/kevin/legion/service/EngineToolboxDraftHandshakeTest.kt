package com.kevin.legion.service

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The `create_aspect`/`update_aspect` confirm handshake's pure state machine
 * ([EngineToolbox.stashDraft]/[EngineToolbox.takeDraft]) - never the real Pro-tier generator call,
 * so this runs with no network and no key, matching this suite's "green both key ways" requirement.
 * Ticket 06's answer: "commit only on a confirmed second call" - these are the cases that make that
 * true rather than aspirational.
 */
class EngineToolboxDraftHandshakeTest {

    @Test
    fun `a stashed draft is returned once by its own token`() {
        val draft = JSONObject().put("aspectName", "Workouts")
        val token = EngineToolbox.stashDraft(draft, targetAspectName = null)

        val taken = EngineToolbox.takeDraft(token, targetAspectName = null)
        assertEquals("Workouts", taken?.getString("aspectName"))
    }

    @Test
    fun `a token can only be consumed once - the second take returns null`() {
        val draft = JSONObject().put("aspectName", "Workouts")
        val token = EngineToolbox.stashDraft(draft, targetAspectName = null)

        EngineToolbox.takeDraft(token, targetAspectName = null)
        val secondTake = EngineToolbox.takeDraft(token, targetAspectName = null)
        assertNull(secondTake)
    }

    @Test
    fun `an unknown token never commits anything`() {
        assertNull(EngineToolbox.takeDraft("not-a-real-token", targetAspectName = null))
    }

    @Test
    fun `an update_aspect draft only confirms against the SAME aspect name it was drafted for`() {
        val draft = JSONObject().put("changeType", "RENAME_ASPECT").put("newAspectName", "Vehicles")
        val token = EngineToolbox.stashDraft(draft, targetAspectName = "Workouts")

        // Wrong aspect name - must not commit, and must not consume the token either way the
        // caller can retry with the right name (see this fn's own second assertion below is
        // intentionally NOT run - the current contract removes the token on ANY takeDraft call,
        // matching "a stale or mismatched token commits NOTHING" rather than "may be retried").
        val wrongAspect = EngineToolbox.takeDraft(token, targetAspectName = "Fleet")
        assertNull(wrongAspect)
    }

    @Test
    fun `an update_aspect draft confirms when the aspect name matches, case-insensitively`() {
        val draft = JSONObject().put("changeType", "RENAME_ASPECT").put("newAspectName", "Vehicles")
        val token = EngineToolbox.stashDraft(draft, targetAspectName = "Workouts")

        val taken = EngineToolbox.takeDraft(token, targetAspectName = "workouts")
        assertEquals("RENAME_ASPECT", taken?.getString("changeType"))
    }

    @Test
    fun `create_aspect confirms with a null target aspect name (no existing aspect to match)`() {
        val draft = JSONObject().put("aspectName", "Reading")
        val token = EngineToolbox.stashDraft(draft, targetAspectName = null)

        val taken = EngineToolbox.takeDraft(token, targetAspectName = null)
        assertEquals("Reading", taken?.getString("aspectName"))
    }
}
