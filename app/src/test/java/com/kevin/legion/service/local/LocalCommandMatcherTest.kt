package com.kevin.legion.service.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 of the voice cost work (Kevin 2026-11-10: "we offload as much as we can to the phones
 * own speech recognizer"): a closed set of simple commands matched on the phone, no model.
 *
 * The matcher's contract, pinned here before it was written:
 * - It understands phrasing, not one template: fillers ("please", "can you", the companion's name)
 *   are ignored and number words are numbers.
 * - Every word must be accounted for. A sentence that carries anything the matcher does not
 *   understand is a MISS, never half-done: "pause and tell me the weather" goes to Alfred whole.
 * - Low recogniser confidence or an unknown word is a MISS.
 * - Ambiguity is a MISS, never a guess: "wake me at six thirty" could be morning or evening.
 */
class LocalCommandMatcherTest {

    private val m = LocalCommandMatcher(companionName = "Alfred")

    private fun hit(text: String): LocalCommand {
        val r = m.match(Utterance(text))
        assertTrue("expected a hit for \"$text\", got $r", r is LocalMatch.Hit)
        return (r as LocalMatch.Hit).command
    }

    private fun miss(text: String, conf: List<Float>? = null) {
        val r = m.match(Utterance(text, conf))
        assertTrue("expected a miss for \"$text\", got $r", r is LocalMatch.Miss)
    }

    // --- timers --------------------------------------------------------------------------------

    @Test
    fun `timers in several phrasings`() {
        assertEquals(LocalCommand.Timer(600), hit("set a timer for ten minutes"))
        assertEquals(LocalCommand.Timer(600), hit("Alfred, 10 minute timer please"))
        assertEquals(LocalCommand.Timer(90), hit("start a timer for a minute and a half"))
        assertEquals(LocalCommand.Timer(1800), hit("timer half an hour"))
        assertEquals(LocalCommand.Timer(45), hit("can you set a 45 second timer"))
        assertEquals(LocalCommand.Timer(5400), hit("set a timer for an hour and thirty minutes"))
        assertEquals(LocalCommand.Timer(120), hit("two minute timer"))
    }

    @Test
    fun `a timer with anything extra goes to Alfred`() {
        miss("set a timer for ten minutes called pasta")
        miss("set a timer")
        miss("set a timer for ten minutes and remind me to call mum")
        miss("set a timer for zero minutes")
    }

    // --- alarms --------------------------------------------------------------------------------

    @Test
    fun `alarms with a clear time of day`() {
        assertEquals(LocalCommand.Alarm(6, 30), hit("wake me up at six thirty am"))
        assertEquals(LocalCommand.Alarm(7, 0), hit("set an alarm for 7 in the morning"))
        assertEquals(LocalCommand.Alarm(19, 15), hit("set an alarm for 7 15 pm"))
        assertEquals(LocalCommand.Alarm(21, 0), hit("alarm at 21 00"))
        assertEquals(LocalCommand.Alarm(6, 0), hit("wake me at six o'clock in the morning"))
    }

    @Test
    fun `an alarm whose half of the day is unclear goes to Alfred`() {
        miss("wake me at six thirty")
        miss("set an alarm for 7")
        miss("set an alarm for tomorrow at 7 am")
        miss("set an alarm for 25 00")
    }

    // --- media and volume ----------------------------------------------------------------------

    @Test
    fun `playback words`() {
        assertEquals(LocalCommand.Media(MediaAction.PAUSE), hit("pause"))
        assertEquals(LocalCommand.Media(MediaAction.PAUSE), hit("pause the music please"))
        assertEquals(LocalCommand.Media(MediaAction.PAUSE), hit("stop the music"))
        assertEquals(LocalCommand.Media(MediaAction.RESUME), hit("resume"))
        assertEquals(LocalCommand.Media(MediaAction.RESUME), hit("play the music again"))
        assertEquals(LocalCommand.Media(MediaAction.NEXT), hit("skip this song"))
        assertEquals(LocalCommand.Media(MediaAction.NEXT), hit("next track"))
        assertEquals(LocalCommand.Media(MediaAction.PREVIOUS), hit("go back a song"))
        assertEquals(LocalCommand.Media(MediaAction.PREVIOUS), hit("previous song"))
    }

    @Test
    fun `playing something named goes to Alfred`() {
        miss("play some jazz")
        miss("play the album Blue Harbor")
        miss("stop")
        miss("pause and tell me the weather")
    }

    @Test
    fun `volume words`() {
        assertEquals(LocalCommand.Volume(VolumeAction.UP, null), hit("turn it up"))
        assertEquals(LocalCommand.Volume(VolumeAction.UP, null), hit("louder please"))
        assertEquals(LocalCommand.Volume(VolumeAction.DOWN, null), hit("turn the volume down"))
        assertEquals(LocalCommand.Volume(VolumeAction.MUTE, null), hit("mute the music"))
        assertEquals(LocalCommand.Volume(VolumeAction.UNMUTE, null), hit("unmute"))
        assertEquals(LocalCommand.Volume(VolumeAction.SET, 40), hit("set the volume to forty"))
        assertEquals(LocalCommand.Volume(VolumeAction.SET, 70), hit("volume 70 percent"))
        miss("set the volume to 140")
    }

    // --- sleep, screens, lists -----------------------------------------------------------------

    @Test
    fun `dismissals`() {
        for (s in listOf("that's all", "that will be all", "go to sleep", "stand down", "never mind", "be quiet", "quiet")) {
            assertEquals(s, LocalCommand.Sleep, hit(s))
        }
        miss("that's all for the groceries")
    }

    @Test
    fun `open a named screen`() {
        assertEquals(LocalCommand.OpenScreen("settings"), hit("open settings"))
        assertEquals(LocalCommand.OpenScreen("calendar"), hit("show me my calendar"))
        assertEquals(LocalCommand.OpenScreen("checklists"), hit("go to my lists"))
        assertEquals(LocalCommand.OpenScreen("money"), hit("open the ledger"))
        miss("open the pod bay doors")
        miss("open spotify and play jazz")
    }

    @Test
    fun `clear ticked items on a named list`() {
        assertEquals(LocalCommand.ClearTicked("groceries"), hit("clear the ticked items on groceries"))
        assertEquals(LocalCommand.ClearTicked("groceries"), hit("clear ticked off things from the grocery list"))
        assertEquals(LocalCommand.ClearTicked("todo"), hit("clear the ticked ones from my to do list"))
        miss("clear the ticked items")
        miss("clear the groceries list")
    }

    // --- confidence and noise ------------------------------------------------------------------

    @Test
    fun `low confidence or an unknown word is never acted on`() {
        miss("pause", conf = listOf(0.4f))
        miss("set a timer for ten minutes", conf = listOf(0.95f, 0.95f, 0.95f, 0.95f, 0.5f, 0.95f))
        miss("[unk] pause")
        miss("")
        miss("   ")
    }

    @Test
    fun `high confidence on every word is a hit`() {
        val r = m.match(Utterance("pause the music", listOf(0.99f, 0.97f, 0.98f)))
        assertTrue(r is LocalMatch.Hit)
    }

    @Test
    fun `the matcher is fast on long noise`() {
        val noise = List(2_000) { "blah" }.joinToString(" ")
        val t0 = System.nanoTime()
        miss(noise)
        assertTrue("took ${(System.nanoTime() - t0) / 1_000_000} ms", System.nanoTime() - t0 < 50_000_000L)
    }
}
