package com.kevin.legion.quiet

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quiet's media and System volume rules (Kevin, 2026-10-11), against a fake [QuietAudio] so no
 * Robolectric and no real AudioManager is involved.
 */
class QuietVolumesTest {

    private val music = AudioManager.STREAM_MUSIC
    private val system = AudioManager.STREAM_SYSTEM

    private class FakeAudio : QuietAudio {
        val levels = mutableMapOf(
            AudioManager.STREAM_MUSIC to 11,
            AudioManager.STREAM_SYSTEM to 6,
        )
        val maxes = mutableMapOf(
            AudioManager.STREAM_MUSIC to 15,
            AudioManager.STREAM_SYSTEM to 15,
        )
        val muted = mutableSetOf<Int>()
        val throwOn = mutableSetOf<Int>()
        val writes = mutableListOf<Pair<Int, Int>>()

        /** Set to simulate a phone where writing the system stream drags the ringer to vibrate. */
        var systemWriteMovesRinger = false
        override var ringerMode: Int = AudioManager.RINGER_MODE_NORMAL

        override fun volume(stream: Int): Int = if (stream in muted) 0 else levels.getValue(stream)
        override fun maxVolume(stream: Int): Int = maxes.getValue(stream)
        override fun isMuted(stream: Int): Boolean = stream in muted
        override fun setVolume(stream: Int, index: Int) {
            if (stream in throwOn) throw SecurityException("Not allowed to change Do Not Disturb state")
            writes += stream to index
            levels[stream] = index
            if (stream == AudioManager.STREAM_SYSTEM && index == 0 && systemWriteMovesRinger) {
                ringerMode = AudioManager.RINGER_MODE_VIBRATE
            }
        }
    }

    private class FakeMemory : QuietMediaMemory {
        var held: QuietMedia? = null
        override fun load(): QuietMedia? = held
        override fun save(media: QuietMedia) {
            held = media
        }

        override fun clear() {
            held = null
        }
    }

    private val audio = FakeAudio()
    private val memory = FakeMemory()
    private val logged = mutableListOf<Pair<String, Throwable?>>()
    private val volumes = QuietVolumes(audio, memory) { message, error -> logged += message to error }

    @Test
    fun `quiet on sets media to half and remembers the old value`() {
        audio.levels[music] = 11
        volumes.onQuietOn()
        assertEquals("half of 15, rounded up", 8, audio.levels[music])
        assertEquals(QuietMedia(before = 11, applied = 8), memory.held)
    }

    @Test
    fun `half rounds on a tie and never writes a range it cannot read`() {
        assertEquals(8, QuietState.halfOf(15))
        assertEquals(5, QuietState.halfOf(10))
        assertEquals(1, QuietState.halfOf(1))
        audio.maxes[music] = 0
        volumes.onQuietOn()
        assertTrue("a zero range gets no media write", audio.writes.none { it.first == music })
        assertNull(memory.held)
    }

    @Test
    fun `quiet off restores the old media volume when the user left it alone`() {
        audio.levels[music] = 11
        volumes.onQuietOn()
        volumes.onQuietOff()
        assertEquals(11, audio.levels[music])
        assertNull("the memory is spent", memory.held)
    }

    @Test
    fun `quiet off leaves media alone when the user changed it`() {
        audio.levels[music] = 11
        volumes.onQuietOn()
        audio.levels[music] = 3 // the user turned it down while Quiet was on
        volumes.onQuietOff()
        assertEquals("the user's value is not ours to overwrite", 3, audio.levels[music])
        assertNull(memory.held)
    }

    @Test
    fun `a pre-quiet media volume of zero comes back as zero`() {
        audio.levels[music] = 0
        volumes.onQuietOn()
        assertEquals(8, audio.levels[music])
        volumes.onQuietOff()
        assertEquals(0, audio.levels[music])
    }

    @Test
    fun `quiet off with nothing remembered leaves media alone`() {
        // Quiet was already on when this build was installed: no memory exists.
        audio.levels[music] = 4
        volumes.onQuietOff()
        assertEquals(4, audio.levels[music])
        assertTrue(audio.writes.none { it.first == music })
    }

    @Test
    fun `media the user has muted is neither halved nor restored over`() {
        // A muted stream reads 0 whatever its level, so remembering that 0 would destroy the real one.
        audio.muted += music
        volumes.onQuietOn()
        assertTrue(audio.writes.none { it.first == music })
        assertNull(memory.held)

        audio.muted.clear()
        audio.levels[music] = 11
        volumes.onQuietOn()
        assertEquals(QuietMedia(11, 8), memory.held)
        audio.muted += music // the user muted media by hand while Quiet was on
        volumes.onQuietOff()
        assertEquals("a muted media stream reads 0, which is not what we left", 8, audio.levels[music])
    }

    @Test
    fun `a phone that caps the media write is compared against what stuck`() {
        audio.levels[music] = 14
        val capped = object : QuietAudio by audio {
            // Only the half-way write is capped; the restore to 14 must go through whole.
            override fun setVolume(stream: Int, index: Int) = audio.setVolume(stream, if (index == 8) 6 else index)
        }
        val capping = QuietVolumes(capped, memory) { message, error -> logged += message to error }
        capping.onQuietOn()
        assertEquals(QuietMedia(before = 14, applied = 6), memory.held)
        capping.onQuietOff()
        assertEquals(14, audio.levels[music])
    }

    @Test
    fun `not quiet mutes system`() {
        audio.levels[system] = 6
        volumes.muteSystem()
        assertEquals(0, audio.levels[system])
        assertEquals(listOf(system to 0), audio.writes)
        assertTrue("start-up touches nothing but system", audio.writes.none { it.first == music })
    }

    @Test
    fun `system is already silent means no write`() {
        audio.levels[system] = 0
        volumes.muteSystem()
        assertTrue(audio.writes.isEmpty())
    }

    @Test
    fun `system that reads zero only because it is muted is still written`() {
        // Do Not Disturb mutes it, so it reads 0, while the stored level may be anything.
        audio.levels[system] = 6
        audio.muted += system
        volumes.muteSystem()
        assertEquals(listOf(system to 0), audio.writes)
    }

    @Test
    fun `system is muted in both quiet states and never raised`() {
        volumes.onQuietOn()
        assertEquals(0, audio.levels[system])
        volumes.onQuietOff()
        assertEquals(0, audio.levels[system])
        val systemWrites = audio.writes.filter { it.first == system }
        assertTrue("no write to system ever goes above zero", systemWrites.all { it.second == 0 })
    }

    @Test
    fun `muting system puts the ringer back if the write moved it`() {
        audio.levels[system] = 6
        audio.systemWriteMovesRinger = true
        volumes.muteSystem()
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audio.ringerMode)
        assertTrue(logged.any { it.first.contains("ringer") })
    }

    @Test
    fun `a security exception is swallowed and logged`() {
        audio.throwOn += system
        volumes.muteSystem() // must not throw
        assertEquals(6, audio.levels[system])
        val entry = logged.single()
        assertTrue(entry.second is SecurityException)
        assertTrue(entry.first.contains("mute system volume"))
    }

    @Test
    fun `one stream failing does not stop the other`() {
        audio.throwOn += system
        volumes.onQuietOn()
        assertEquals("media still halved", 8, audio.levels[music])
        assertEquals(QuietMedia(11, 8), memory.held)

        audio.throwOn.clear()
        audio.throwOn += music
        audio.levels[music] = 8
        volumes.onQuietOff() // media restore throws; system must still be muted
        assertEquals(0, audio.levels[system])
        assertNull(memory.held)
    }

    @Test
    fun `a failed media write remembers nothing`() {
        audio.throwOn += music
        volumes.onQuietOn()
        assertNull("nothing changed, so nothing to put back", memory.held)
        assertEquals(11, audio.levels[music])
    }

    @Test
    fun `quiet on twice keeps the first remembered value and the user's change`() {
        audio.levels[music] = 11
        volumes.onQuietOn()
        audio.levels[music] = 5 // user moves it between the two "on"s
        val writesAfterFirst = audio.writes.size
        volumes.onQuietOn()
        assertEquals(QuietMedia(11, 8), memory.held)
        assertEquals(5, audio.levels[music])
        assertEquals("the second on writes nothing", writesAfterFirst, audio.writes.size)
    }

    @Test
    fun `quiet off twice restores once`() {
        audio.levels[music] = 11
        volumes.onQuietOn()
        volumes.onQuietOff()
        audio.levels[music] = 2
        volumes.onQuietOff()
        assertEquals("the second off has nothing to restore", 2, audio.levels[music])
    }

    @Test
    fun `memory survives the object that wrote it`() {
        audio.levels[music] = 11
        volumes.onQuietOn()
        // A process death between on and off: a new controller over the same memory.
        QuietVolumes(audio, memory).onQuietOff()
        assertEquals(11, audio.levels[music])
    }
}
