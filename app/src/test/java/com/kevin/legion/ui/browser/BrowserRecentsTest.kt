package com.kevin.legion.ui.browser

import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import com.kevin.legion.service.BrowserToolbox
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 2026-11-03 audit: the browser's own task sat in Recents beside LEGION, so an app-switch swipe
 * brought back the last image search with no tool call. It stays out of Recents, and the tool's
 * description keeps questions out of the browser.
 */
@RunWith(RobolectricTestRunner::class)
class BrowserRecentsTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun info(): ActivityInfo = context.packageManager.getActivityInfo(
        ComponentName(context, BrowserActivity::class.java),
        PackageManager.GET_META_DATA,
    )

    @Test
    fun `the browser never shows in Recents`() {
        assertTrue(info().flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0)
    }

    @Test
    fun `the browser task is removed when it closes`() {
        assertTrue(info().flags and ActivityInfo.FLAG_AUTO_REMOVE_FROM_RECENTS != 0)
    }

    @Test
    fun `the tool description keeps questions out of the browser`() {
        val d = BrowserToolbox.declarations().getJSONObject(0).getString("description")
        assertTrue(d.contains("ONLY when the user asks to see something"))
        assertTrue(d.contains("NEVER open it to answer a question"))
    }
}
