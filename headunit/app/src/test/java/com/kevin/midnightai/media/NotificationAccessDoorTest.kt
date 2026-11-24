package com.kevin.midnightai.media

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import com.kevin.midnightai.util.ExternalScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * The GRANT ACCESS chip on Cruise (the car's home screen) and the two buttons in Settings and
 * media diagnostics all open `ACTION_NOTIFICATION_LISTENER_SETTINGS`, an OPTIONAL Settings
 * activity. This gutted AOSP unit may not have it, and the launch sat bare in a click handler in
 * two separate places, so the tap threw and took the launcher down. `checkActivities` is what makes
 * Robolectric refuse an unresolvable intent the way a device does.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationAccessDoorTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun `no notification access screen on this unit gives words, not a throw`() {
        shadowOf(app).checkActivities(true)
        assertEquals(
            ExternalScreen.NOTIFICATION_ACCESS.missingWords(),
            openNotificationAccessSettings(app),
        )
    }

    @Test fun `a unit that refuses the screen says that instead`() {
        assertEquals(
            ExternalScreen.NOTIFICATION_ACCESS.refusedWords(),
            openNotificationAccessSettings(Refusing(app)),
        )
    }

    @Test fun `a unit that has the screen says nothing`() {
        assertNull(openNotificationAccessSettings(app))
    }

    private class Refusing(base: Context) : ContextWrapper(base) {
        override fun startActivity(intent: Intent?) =
            throw SecurityException("not exported: $intent")
    }
}
