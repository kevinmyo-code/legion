package com.kevin.legion.service

import android.app.NotificationManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [CallNotificationReceiver] - the notification's ANSWER/DECLINE buttons (command-center ticket
 * 05, ADR 0035's founding case).
 *
 * **What this file can and cannot reach.** [CallNotificationReceiver.onReceive] itself calls
 * `goAsync()`, which needs a real system-delivered `PendingResult` that invoking `onReceive`
 * directly does not set up - the same reason [ReminderActionReceiver] has no test in this
 * codebase. [CallNotificationReceiver.handlerFor] exists precisely so the ROUTING - which action
 * calls which of [CallActions.answer]/[reject] - is reachable without going through that. Running
 * the returned handler resolves to [CallActions.Outcome.NoPermission] here (Robolectric grants
 * nothing by default), which is itself a genuine, distinct outcome, not a stub - and confirms the
 * handler reaches [CallActions] rather than throwing or doing nothing.
 */
@RunWith(RobolectricTestRunner::class)
class CallNotificationReceiverTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `the two action constants are distinct and namespaced to this app`() {
        assertTrue(CallNotificationReceiver.ACTION_ANSWER != CallNotificationReceiver.ACTION_DECLINE)
        assertTrue(CallNotificationReceiver.ACTION_ANSWER.startsWith("com.kevin.legion."))
        assertTrue(CallNotificationReceiver.ACTION_DECLINE.startsWith("com.kevin.legion."))
    }

    @Test
    fun `handlerFor returns null for anything but the two declared actions`() {
        assertNull(CallNotificationReceiver.handlerFor(null))
        assertNull(CallNotificationReceiver.handlerFor("com.kevin.legion.action.SOMETHING_ELSE"))
    }

    @Test
    fun `ANSWER routes to CallActions_answer, observable via its NoPermission outcome`() = runBlocking {
        val handler = CallNotificationReceiver.handlerFor(CallNotificationReceiver.ACTION_ANSWER)
        assertNotNull(handler)
        val outcome = handler!!(context)
        // Robolectric grants nothing by default, so a real call into CallActions.answer resolves
        // here - this is what proves the ANSWER action reaches CallActions at all, rather than
        // silently doing nothing.
        assertEquals(CallActions.Outcome.NoPermission, outcome)
    }

    @Test
    fun `DECLINE routes to CallActions_reject, observable via its NoPermission outcome`() = runBlocking {
        val handler = CallNotificationReceiver.handlerFor(CallNotificationReceiver.ACTION_DECLINE)
        assertNotNull(handler)
        val outcome = handler!!(context)
        assertEquals(CallActions.Outcome.NoPermission, outcome)
    }

    @Test
    fun `cancel reaches the real NotificationManager without throwing when nothing is posted`() {
        // No notification was ever posted in this test, so this only asserts cancel() is safe to
        // call unconditionally - exactly how TelephonyController.handleState calls it, on every
        // ring-ending transition, whether or not a notification is actually up.
        CallNotificationReceiver.cancel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        assertNotNull(manager)
    }
}
