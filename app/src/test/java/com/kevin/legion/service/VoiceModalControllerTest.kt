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
        VoiceModalController.show(VoiceModalTarget.AGENDA)
        assertEquals(VoiceModalTarget.AGENDA, VoiceModalController.current.value?.target)
    }

    @Test
    fun `dismiss clears the current payload`() {
        VoiceModalController.show(VoiceModalTarget.AGENDA)
        VoiceModalController.dismiss()
        assertNull(VoiceModalController.current.value)
    }

    // "a second show replaces the first rather than queueing" (originally proved by showing
    // AGENDA then WHOLE_LIST and asserting the target was WHOLE_LIST) deleted one-today ticket 10
    // slice C, 2026-09-05: `VoiceModalTarget` is down to one value ([WHOLE_LIST] retired alongside
    // `show_list_modal` - see that enum's own doc comment), so there is no second target left to
    // demonstrate an overwrite with. `repeat show of the same target is still a distinct payload`
    // below already proves the identical "no queue, newest wins" behaviour via [VoiceModalPayload]'s
    // own `shownAt`, which is what actually makes the StateFlow re-emit either way.

    @Test
    fun `repeat show of the same target is still a distinct payload`() {
        // VoiceModalPayload's shownAt (see its own doc comment) is what makes a StateFlow actually
        // re-emit on a same-target repeat call, rather than being swallowed as an unchanged value.
        VoiceModalController.show(VoiceModalTarget.AGENDA)
        val first = VoiceModalController.current.value
        Thread.sleep(2) // guarantees a distinct shownAt millisecond - see VoiceModalPayload's doc
        VoiceModalController.show(VoiceModalTarget.AGENDA)
        val second = VoiceModalController.current.value
        assertNotEquals(first, second)
    }
}
