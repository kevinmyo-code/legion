package com.kevin.midnightai.media

import android.content.Context
import android.media.AudioManager
import android.os.Build
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * HU-1 T2 Volume check, review 2026-11-20 (L3): every TRY ends on the index it started from, at
 * the bottom, the middle and the top, and none of them touches a muted stream.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class VolumeReapplyAndroidTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val am get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val max get() = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    @Before fun unmute() = shadowOf(am).setIsStreamMute(AudioManager.STREAM_MUSIC, false)

    @Test fun everyStrategyEndsOnTheIndexItStartedFrom() {
        listOf(0, max / 2, max).forEach { start ->
            VolumeController.Reapply.entries.forEach { strategy ->
                am.setStreamVolume(AudioManager.STREAM_MUSIC, start, 0)
                assertTrue(VolumeController.reapply(context, strategy) != null)
                assertEquals("$strategy from $start", start, am.getStreamVolume(AudioManager.STREAM_MUSIC))
            }
        }
    }

    @Test fun aMutedStreamIsLeftAloneByEveryStrategy() {
        am.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)
        shadowOf(am).setIsStreamMute(AudioManager.STREAM_MUSIC, true)
        VolumeController.Reapply.entries.forEach { strategy ->
            assertNull(strategy.name, VolumeController.reapply(context, strategy))
            assertTrue(am.isStreamMute(AudioManager.STREAM_MUSIC))
            assertEquals(5, am.getStreamVolume(AudioManager.STREAM_MUSIC))
        }
    }
}
