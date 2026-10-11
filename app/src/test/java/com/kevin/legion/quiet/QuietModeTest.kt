package com.kevin.legion.quiet

import android.app.Application
import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.service.notification.ZenPolicy
import com.kevin.legion.ui.MainActivity
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuietModeTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val audio get() = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val nm get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Before
    fun reset() {
        app.getSharedPreferences("quiet_mode", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `off puts back the ringer he had, not a default`() {
        shadowOf(nm).setNotificationPolicyAccessGranted(true)
        audio.ringerMode = AudioManager.RINGER_MODE_SILENT
        assertEquals(QuietMode.Result.On, QuietMode.enable(app))
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audio.ringerMode)
        assertEquals(QuietMode.Result.Off, QuietMode.disable(app))
        assertEquals(AudioManager.RINGER_MODE_SILENT, audio.ringerMode)
    }

    @Test
    fun `switching on twice does not strand the phone on vibrate`() {
        shadowOf(nm).setNotificationPolicyAccessGranted(true)
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        QuietMode.enable(app)
        QuietMode.enable(app) // a second "on" must not remember VIBRATE as the thing to go back to
        QuietMode.disable(app)
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audio.ringerMode)
    }

    @Test
    fun `without DND access nothing changes and it says why`() {
        shadowOf(nm).setNotificationPolicyAccessGranted(false)
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        val r = QuietMode.enable(app)
        assertTrue(r is QuietMode.Result.Refused)
        assertEquals(QuietMode.NEEDS_ACCESS, (r as QuietMode.Result.Refused).message)
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audio.ringerMode)
        assertFalse(QuietMode.isOn(app))
    }

    @Test
    fun `ringer bookkeeping`() {
        assertEquals(AudioManager.RINGER_MODE_SILENT,
            QuietState.ringerToRemember(isOn = false, current = AudioManager.RINGER_MODE_SILENT, alreadySaved = -1))
        assertEquals(AudioManager.RINGER_MODE_NORMAL,
            QuietState.ringerToRemember(isOn = true, current = AudioManager.RINGER_MODE_VIBRATE, alreadySaved = AudioManager.RINGER_MODE_NORMAL))
        assertEquals(AudioManager.RINGER_MODE_NORMAL, QuietState.ringerToRestore(-1))
    }

    @Test
    fun `off puts every volume back, and none stays at zero`() {
        // Kevin, 2026-09-27: "when i toggle it off, the phone stays quiet, like everything is at 0".
        shadowOf(nm).setNotificationPolicyAccessGranted(true)
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        audio.setStreamVolume(AudioManager.STREAM_RING, 5, 0)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 10, 0)
        audio.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0) // already silent before Quiet
        QuietMode.enable(app)
        // What vibrate does to the phone: ring and notification zeroed.
        audio.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
        audio.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0)
        QuietMode.disable(app)
        assertEquals("ring back to what it was", 5, audio.getStreamVolume(AudioManager.STREAM_RING))
        assertEquals("media back to what it was", 10, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        val notifMax = audio.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION)
        assertEquals("a stream at 0 comes back at half", QuietState.volumeToRestore(0, notifMax),
            audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION))
        assertTrue(audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION) > 0)
    }

    @Test
    fun `on puts media at half and off puts it back when untouched`() {
        // Kevin, 2026-10-11: "i want media volume to be half".
        shadowOf(nm).setNotificationPolicyAccessGranted(true)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 12, 0)
        QuietMode.enable(app)
        assertEquals(QuietState.halfOf(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)),
            audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        QuietMode.disable(app)
        assertEquals(12, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    @Test
    fun `off leaves media alone when the user moved it during quiet`() {
        shadowOf(nm).setNotificationPolicyAccessGranted(true)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 12, 0)
        QuietMode.enable(app)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 2, 0)
        QuietMode.disable(app)
        assertEquals(2, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    @Test
    fun `system volume ends up muted whichever way quiet goes and is never put back`() {
        // Kevin, 2026-10-11: "not quiet mode > i want system volume muted". The old Quiet remembered
        // System on and restored it on off, which is how the key-press sounds came back.
        shadowOf(nm).setNotificationPolicyAccessGranted(true)
        audio.setStreamVolume(AudioManager.STREAM_SYSTEM, 7, 0)
        QuietMode.enable(app)
        assertEquals("muted while Quiet is on", 0, audio.getStreamVolume(AudioManager.STREAM_SYSTEM))
        QuietMode.disable(app)
        assertEquals("not restored on off", 0, audio.getStreamVolume(AudioManager.STREAM_SYSTEM))
    }

    @Test
    fun `the policy lets media through and keeps system silenced`() {
        // Kevin, 2026-10-11: "Yes, music at half". Without allowMedia the rule muted music outright.
        val policy = QuietMode.quietPolicy()
        assertEquals(ZenPolicy.STATE_ALLOW, policy.priorityCategoryMedia)
        assertEquals(ZenPolicy.STATE_ALLOW, policy.priorityCategoryAlarms)
        assertEquals(ZenPolicy.STATE_ALLOW, policy.priorityCategoryCalls)
        assertEquals(ZenPolicy.STATE_DISALLOW, policy.priorityCategorySystem)
        assertEquals(ZenPolicy.STATE_DISALLOW, policy.priorityCategoryMessages)
    }

    @Test
    fun `a new rule is created with the media-allowing policy`() {
        shadowOf(nm).setNotificationPolicyAccessGranted(true)
        QuietMode.enable(app)
        val rule = nm.getAutomaticZenRule(savedRuleId())
        assertEquals(ZenPolicy.STATE_ALLOW, rule.zenPolicy?.priorityCategoryMedia)
    }

    @Test
    fun `a rule an older build installed is updated in place to allow media`() {
        shadowOf(nm).setNotificationPolicyAccessGranted(true)
        val old = AutomaticZenRule(
            "LEGION Quiet", null, ComponentName(app, MainActivity::class.java),
            Uri.parse("condition://com.kevin.legion/quiet"),
            ZenPolicy.Builder().disallowAllSounds().allowCalls(ZenPolicy.PEOPLE_TYPE_ANYONE).allowAlarms(true).build(),
            NotificationManager.INTERRUPTION_FILTER_PRIORITY, true,
        )
        val oldId = nm.addAutomaticZenRule(old)
        app.getSharedPreferences("quiet_mode", Context.MODE_PRIVATE).edit().putString("rule_id", oldId).commit()
        assertEquals(ZenPolicy.STATE_DISALLOW, nm.getAutomaticZenRule(oldId).zenPolicy?.priorityCategoryMedia)

        QuietMode.enable(app)

        assertEquals("the same rule, not a second one", oldId, savedRuleId())
        assertEquals(ZenPolicy.STATE_ALLOW, nm.getAutomaticZenRule(oldId).zenPolicy?.priorityCategoryMedia)
        assertEquals(ZenPolicy.STATE_DISALLOW, nm.getAutomaticZenRule(oldId).zenPolicy?.priorityCategorySystem)
    }

    private fun savedRuleId(): String =
        app.getSharedPreferences("quiet_mode", Context.MODE_PRIVATE).getString("rule_id", null)!!

    @Test
    fun `app and service start mute system volume without touching media`() {
        audio.setStreamVolume(AudioManager.STREAM_SYSTEM, 7, 0)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 12, 0)
        QuietMode.muteSystemSounds(app)
        assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_SYSTEM))
        assertEquals(12, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    @Test
    fun `volume rule - what it was, or half, never zero`() {
        assertEquals(6, QuietState.volumeToRestore(saved = 6, max = 15))
        assertEquals(8, QuietState.volumeToRestore(saved = 0, max = 15))
        assertEquals(8, QuietState.volumeToRestore(saved = -1, max = 15)) // never recorded
        assertEquals(15, QuietState.volumeToRestore(saved = 40, max = 15)) // clamped
        assertEquals(1, QuietState.volumeToRestore(saved = 0, max = 1))
    }
}
