package com.kevin.midnightai.media

import android.content.Context
import android.media.AudioManager
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * WHICH call a volume press makes (Kevin in the car, 2026-11-23: the bar moves, the sound does
 * not). Every path moves the index, so an index assertion cannot tell them apart and the press is
 * watched through a fake of its one impure step instead. The real amp is car-only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class VolumeNudgePathTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val am get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val max get() = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    @Before fun middleOfTheRange() {
        shadowOf(am).setIsStreamMute(AudioManager.STREAM_MUSIC, false)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, max / 2, 0)
        VolumePathStore.clear(context)
    }

    @Test fun aPressGoesDownTheSystemLikePathAndNotTheCallThatMovedOnlyTheBar() {
        val fake = FakeNudger(am)
        VolumeController.nudgeWith(context, up = true, nudger = fake)
        assertEquals(listOf(VolumePath.SUGGESTED_STREAM to true), fake.sent)
        VolumeController.nudgeWith(context, up = false, nudger = fake)
        assertEquals(VolumePath.SUGGESTED_STREAM to false, fake.sent.last())
    }

    @Test fun aCallThisRomRefusesFallsBackToTheOlderOne() {
        val fake = FakeNudger(am, refuse = setOf(VolumePath.SUGGESTED_STREAM))
        val nudge = VolumeController.nudgeWith(context, up = true, nudger = fake)
        assertEquals(listOf(VolumePath.SUGGESTED_STREAM to true, VolumePath.STREAM to true), fake.sent)
        // The older call went through, so the press is not reported as refused.
        assertNull(nudge.words)
    }

    @Test fun aPressThatMovedNothingTriesTheOlderCallOnceAndStops() {
        val fake = FakeNudger(am, moves = false)
        val nudge = VolumeController.nudgeWith(context, up = true, nudger = fake)
        assertEquals(listOf(VolumePath.SUGGESTED_STREAM to true, VolumePath.STREAM to true), fake.sent)
        assertEquals(VolumeFeedback.NO_MOVEMENT, nudge.words)
    }

    @Test fun pushingPastTheTopIsNotRetried() {
        am.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
        val fake = FakeNudger(am, moves = false)
        VolumeController.nudgeWith(context, up = true, nudger = fake)
        assertEquals(listOf(VolumePath.SUGGESTED_STREAM to true), fake.sent)
    }

    @Test fun thePickTheDriverMadeIsTheOnlyCallMade() {
        VolumePathStore.choose(context, VolumePath.MEDIA_SESSION)
        val fake = FakeNudger(am, moves = false)
        val nudge = VolumeController.nudgeWith(context, up = true, nudger = fake)
        assertEquals(listOf(VolumePath.MEDIA_SESSION to true), fake.sent)
        // Nothing can be read back from a session's own volume, so the press claims nothing.
        assertNull(nudge.words)
    }

    @Test fun droppingThePickPutsPressesBackOnTheDefault() {
        VolumePathStore.choose(context, VolumePath.MASTER)
        VolumePathStore.clear(context)
        val fake = FakeNudger(am)
        VolumeController.nudgeWith(context, up = true, nudger = fake)
        assertEquals(listOf(VolumePathLogic.DEFAULT to true), fake.sent)
    }

    /** The Android calls, faked: it records the path and optionally moves the index like a real one. */
    private class FakeNudger(
        private val am: AudioManager,
        private val refuse: Set<VolumePath> = emptySet(),
        private val moves: Boolean = true,
    ) : VolumeNudger {
        val sent = mutableListOf<Pair<VolumePath, Boolean>>()

        override fun send(path: VolumePath, up: Boolean): String? {
            sent += path to up
            if (path in refuse) return "Refused"
            if (moves) {
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val next = am.getStreamVolume(AudioManager.STREAM_MUSIC) + if (up) 1 else -1
                am.setStreamVolume(AudioManager.STREAM_MUSIC, next.coerceIn(0, max), 0)
            }
            return null
        }
    }
}
