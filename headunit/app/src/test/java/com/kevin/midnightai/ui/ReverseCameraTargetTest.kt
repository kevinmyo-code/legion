package com.kevin.midnightai.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the two testable-off-device halves of the reverse-camera button: the
 * `pkg`/`pkg/Class` codec that round-trips through SharedPreferences, and the
 * not-configured / launch-attempted decision in [resolveReverseCameraLaunch].
 * The actual `context.startActivity` call and its real
 * ActivityNotFoundException/SecurityException classification
 * (`ui/ReverseCameraLauncher.kt`'s [launchReverseCameraTarget]) are NOT
 * exercised here - see this file's class kdoc on [resolveReverseCameraLaunch]
 * for why constructing those Android exception types under plain JUnit (no
 * Robolectric) is not something to rely on, and the assumptions ledger in the
 * session report for what that leaves untested.
 */
class ReverseCameraTargetTest {

    // --- ReverseCameraTargetCodec --------------------------------------

    @Test
    fun `encodes a package-only target with no slash`() {
        assertEquals("com.vendor.camera", ReverseCameraTargetCodec.encode(ReverseCameraTarget("com.vendor.camera")))
    }

    @Test
    fun `encodes a package plus class target as pkg slash class`() {
        assertEquals(
            "com.vendor.camera/com.vendor.camera.RearViewActivity",
            ReverseCameraTargetCodec.encode(
                ReverseCameraTarget("com.vendor.camera", "com.vendor.camera.RearViewActivity"),
            ),
        )
    }

    @Test
    fun `decodes null and blank as not configured`() {
        assertNull(ReverseCameraTargetCodec.decode(null))
        assertNull(ReverseCameraTargetCodec.decode(""))
        assertNull(ReverseCameraTargetCodec.decode("   "))
    }

    @Test
    fun `decodes a package-only string with no class`() {
        val target = ReverseCameraTargetCodec.decode("com.vendor.camera")
        assertEquals(ReverseCameraTarget("com.vendor.camera", null), target)
    }

    @Test
    fun `decodes a pkg slash class string into both fields`() {
        val target = ReverseCameraTargetCodec.decode("com.vendor.camera/com.vendor.camera.RearViewActivity")
        assertEquals(ReverseCameraTarget("com.vendor.camera", "com.vendor.camera.RearViewActivity"), target)
    }

    @Test
    fun `a lone slash with no package half decodes to null`() {
        assertNull(ReverseCameraTargetCodec.decode("/com.vendor.camera.RearViewActivity"))
    }

    @Test
    fun `a trailing slash with a blank class decodes to package-only`() {
        val target = ReverseCameraTargetCodec.decode("com.vendor.camera/")
        assertEquals(ReverseCameraTarget("com.vendor.camera", null), target)
    }

    @Test
    fun `encode then decode round-trips both shapes`() {
        val withClass = ReverseCameraTarget("com.vendor.camera", "com.vendor.camera.RearViewActivity")
        assertEquals(withClass, ReverseCameraTargetCodec.decode(ReverseCameraTargetCodec.encode(withClass)))

        val withoutClass = ReverseCameraTarget("com.vendor.camera")
        assertEquals(withoutClass, ReverseCameraTargetCodec.decode(ReverseCameraTargetCodec.encode(withoutClass)))
    }

    // --- resolveReverseCameraLaunch --------------------------------------

    @Test
    fun `a null target returns NotConfigured without ever calling attempt`() {
        var attempted = false
        val result = resolveReverseCameraLaunch(null) {
            attempted = true
            ReverseCameraLaunchResult.Launched
        }
        assertEquals(ReverseCameraLaunchResult.NotConfigured, result)
        assertFalse("attempt() must not run when nothing is configured", attempted)
    }

    @Test
    fun `a configured target invokes attempt exactly once and passes its result through`() {
        val target = ReverseCameraTarget("com.vendor.camera")
        var callCount = 0
        val result = resolveReverseCameraLaunch(target) {
            callCount++
            assertEquals(target, it)
            ReverseCameraLaunchResult.Launched
        }
        assertEquals(1, callCount)
        assertEquals(ReverseCameraLaunchResult.Launched, result)
    }

    @Test
    fun `attempt's NotFound and Denied outcomes pass through unchanged`() {
        val target = ReverseCameraTarget("com.vendor.camera")
        assertEquals(
            ReverseCameraLaunchResult.NotFound("no activity found"),
            resolveReverseCameraLaunch(target) { ReverseCameraLaunchResult.NotFound("no activity found") },
        )
        assertEquals(
            ReverseCameraLaunchResult.Denied("permission denied"),
            resolveReverseCameraLaunch(target) { ReverseCameraLaunchResult.Denied("permission denied") },
        )
    }

    // --- reverseCameraLaunchMessage ---------------------------------------

    @Test
    fun `NotConfigured tells the driver to pick one in settings, in words`() {
        val message = reverseCameraLaunchMessage(ReverseCameraLaunchResult.NotConfigured)
        assertTrue(message != null && message.contains("settings", ignoreCase = true))
    }

    @Test
    fun `Launched has no message - success needs no explanation`() {
        assertNull(reverseCameraLaunchMessage(ReverseCameraLaunchResult.Launched))
    }

    @Test
    fun `NotFound, Denied, and Failed each produce a distinct non-null message`() {
        val notFound = reverseCameraLaunchMessage(ReverseCameraLaunchResult.NotFound("x"))
        val denied = reverseCameraLaunchMessage(ReverseCameraLaunchResult.Denied("x"))
        val failed = reverseCameraLaunchMessage(ReverseCameraLaunchResult.Failed("x"))
        assertTrue(notFound != null)
        assertTrue(denied != null)
        assertTrue(failed != null)
        assertTrue(setOf(notFound, denied, failed).size == 3)
    }
}
