package com.kevin.legion.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 2026-11-22: a ring opened a conversation as RING_LISTENING, its close released LIVE_TURN (a no-op),
 * and the wake word was refused until a force-stop. [MicArbiter.Hold] releases what it asked for.
 */
class MicArbiterHoldTest {

    @Before
    fun setUp() = releaseAll()

    @After
    fun tearDown() = releaseAll()

    private fun releaseAll() = MicArbiter.Claimant.entries.forEach(MicArbiter::release)

    @Test
    fun `a ring's hold, dropped, frees the mic for the wake word`() {
        val ring = MicArbiter.Hold(MicArbiter.Claimant.RING_LISTENING)
        assertTrue(ring.take())
        assertFalse(MicArbiter.request(MicArbiter.Claimant.WAKE_WORD))

        ring.drop()

        assertNull(MicArbiter.current())
        assertTrue(MicArbiter.request(MicArbiter.Claimant.WAKE_WORD))
    }

    @Test
    fun `a hold that lost the mic does not release the claimant that took it`() {
        val ring = MicArbiter.Hold(MicArbiter.Claimant.RING_LISTENING)
        var told = false
        assertTrue(ring.take { told = true; ring.lost() })
        assertTrue(MicArbiter.request(MicArbiter.Claimant.LIVE_TURN))
        assertTrue(told)

        ring.drop()

        assertEquals(MicArbiter.Claimant.LIVE_TURN, MicArbiter.current())
    }

    @Test
    fun `a refused hold releases nothing and dropping it twice is safe`() {
        assertTrue(MicArbiter.request(MicArbiter.Claimant.LIVE_TURN))
        val ring = MicArbiter.Hold(MicArbiter.Claimant.RING_LISTENING)
        assertFalse(ring.take())
        ring.drop()
        ring.drop()
        assertEquals(MicArbiter.Claimant.LIVE_TURN, MicArbiter.current())
    }

    @Test
    fun `the cascade session claims and releases the mic only through its hold`() {
        val src = source("service/cascade/CascadeSession.kt")
        assertFalse("a raw request can be released as the wrong claimant", src.contains("MicArbiter.request("))
        assertFalse("a raw release can name the wrong claimant", src.contains("MicArbiter.release("))
        assertTrue(src.contains("MicArbiter.Hold(micClaimant)"))
    }

    private fun source(path: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("app/src/main/java/com/kevin/legion", "src/main/java/com/kevin/legion").forEach {
                val f = File(File(dir, it), path)
                if (f.isFile) return f.readText()
            }
            dir = dir.parentFile
        }
        error("source $path not found")
    }
}
