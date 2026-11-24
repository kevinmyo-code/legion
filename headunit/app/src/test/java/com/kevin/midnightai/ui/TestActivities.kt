package com.kevin.midnightai.ui

import androidx.activity.ComponentActivity
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowLooper

/**
 * Compose activities for Robolectric UI tests, torn down after each test. A composition left alive
 * (a 1 s driving tick, a fix-age clock) keeps posting work to Compose's process-wide UI dispatcher;
 * when Robolectric clears the main looper between tests, that dispatcher can be left believing a
 * dispatch is already scheduled, and the NEXT test's state changes then never recompose. Destroying
 * every activity (which disposes its composition) and draining the looper avoids that.
 */
internal object TestActivities {
    private val open = mutableListOf<ActivityController<ComponentActivity>>()

    fun start(): ComponentActivity =
        Robolectric.buildActivity(ComponentActivity::class.java).setup().also { open += it }.get()

    /** Let background work that resumes on the UI thread land, then destroy and drain. */
    fun closeAll() {
        drain()
        open.forEach { runCatching { it.pause().stop().destroy() } }
        open.clear()
        drain()
    }

    private fun drain() = repeat(10) {
        ShadowLooper.idleMainLooper()
        Thread.sleep(20)
    }
}
