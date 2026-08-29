package com.kevin.legion.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [VoiceModalController]'s state transitions - the sibling controller's own version of the
 * shape [GlanceCardController] would have had a test for. Not Robolectric: pure Kotlin state, no
 * Android type touched.
 */
class VoiceModalControllerTest {

    @Test
    fun `show sets the current payload to the requested target`() {
        VoiceModalController.dismiss()
        VoiceModalController.show(VoiceModalTarget.GROCERIES)
        assertEquals(VoiceModalTarget.GROCERIES, VoiceModalController.current.value?.target)
    }

    @Test
    fun `dismiss clears the current payload`() {
        VoiceModalController.show(VoiceModalTarget.AGENDA)
        VoiceModalController.dismiss()
        assertNull(VoiceModalController.current.value)
    }

    @Test
    fun `a second show replaces the first rather than queueing`() {
        VoiceModalController.show(VoiceModalTarget.AGENDA)
        VoiceModalController.show(VoiceModalTarget.WHOLE_LIST)
        // Only ever one payload live at a time - the second call overwrote the first outright,
        // there is no queue anywhere in this controller to have preserved it.
        assertEquals(VoiceModalTarget.WHOLE_LIST, VoiceModalController.current.value?.target)
    }

    @Test
    fun `repeat show of the same target is still a distinct payload`() {
        // VoiceModalPayload's shownAt (see its own doc comment) is what makes a StateFlow actually
        // re-emit on a same-target repeat call, rather than being swallowed as an unchanged value.
        VoiceModalController.show(VoiceModalTarget.GROCERIES)
        val first = VoiceModalController.current.value
        Thread.sleep(2) // guarantees a distinct shownAt millisecond - see VoiceModalPayload's doc
        VoiceModalController.show(VoiceModalTarget.GROCERIES)
        val second = VoiceModalController.current.value
        assertNotEquals(first, second)
    }
}
