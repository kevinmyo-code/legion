package com.kevin.midnightai.service

import android.content.Context
import android.media.AudioManager
import android.os.Build
import com.kevin.midnightai.media.VolumeRules
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The control_volume voice tool through LiveToolbox.dispatch (review L4 + recheck 2026-11-20):
 * the voice limits are wired, and the reply says the level really set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class ControlVolumeToolTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val am get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Before fun fifteenSteps() {
        shadowOf(am).setStreamMaxVolume(15)
        shadowOf(am).setIsStreamMute(AudioManager.STREAM_MUSIC, false)
    }

    private fun call(args: JSONObject): JSONObject = runBlocking {
        LiveToolbox.dispatch(context, "control_volume", args)!!
    }

    private fun index() = am.getStreamVolume(AudioManager.STREAM_MUSIC)

    @Test fun setIsCappedAndTheReplySaysWhy() {
        am.setStreamVolume(AudioManager.STREAM_MUSIC, 3, 0) // 20%
        val r = call(JSONObject().put("action", "set").put("level", 100))
        assertTrue(r.getBoolean("success"))
        assertTrue(r.getString("message"), r.getString("message").startsWith("Set to "))
        // 20% + 30 points = 50%, which a 15-step stream rounds to index 8 (53%): never higher.
        assertTrue("index ${index()}", index() <= 8)
    }

    @Test fun louderStopsAtTheVoiceCeiling() {
        am.setStreamVolume(AudioManager.STREAM_MUSIC, 12, 0) // 80%
        val r = call(JSONObject().put("action", "up"))
        assertFalse(r.getBoolean("success"))
        assertEquals(VolumeRules.UP_REFUSED, r.getString("message"))
        assertEquals(12, index())
    }

    @Test fun aSetWhileMutedLandsNoLouderThanBeforeTheMute() {
        am.setStreamVolume(AudioManager.STREAM_MUSIC, 6, 0) // 40%
        shadowOf(am).setIsStreamMute(AudioManager.STREAM_MUSIC, true)
        call(JSONObject().put("action", "set").put("level", 75))
        assertTrue("index ${index()}", index() <= 6)
    }
}
