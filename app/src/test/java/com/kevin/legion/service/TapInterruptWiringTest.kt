package com.kevin.legion.service

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * A tap while Alfred speaks interrupts him (Kevin, 2026-11-10, ADR 0081) through ADR 0064's own
 * interrupt, not a second one. `GeminiLiveSession` has no unit seam (it owns a socket, an
 * AudioRecord and an AudioTrack), so this reads its source, the same posture as
 * [LiveSessionControllerRefusalTest]: brittle on a rename, never silently green.
 */
class TapInterruptWiringTest {

    @Test
    fun `server, voice barge-in and tap all take the one interrupt`() {
        val src = session()
        assertTrue("onBargeIn skips interruptReply", body(src, "onBargeIn").contains("interruptReply(\"barge_in\")"))
        assertTrue("the tap skips interruptReply", body(src, "interruptByTap").contains("interruptReply(\"tap\")"))
        assertTrue("the server's interrupt skips interruptReply", src.contains("interruptReply(\"server\")"))
        val flushes = body(src, "interruptReply").split("flushAudio()").size - 1
        assertTrue("interruptReply must flush the queued speech", flushes == 1)
    }

    @Test
    fun `a tap only interrupts while Alfred is speaking, and drops the rest of that reply`() {
        val tap = body(session(), "interruptByTap")
        assertTrue(tap.contains("!speakingThisTurn) return false"))
        assertTrue("the rest of the reply must be dropped", tap.contains("tapCutReply = true"))
    }

    @Test
    fun `the dropped-reply latch clears when the server ends or interrupts that turn`() {
        val src = session()
        val latchClears = Regex("tapCutReply = false").findAll(src).count()
        assertTrue("tapCutReply must clear on interrupted and turnComplete: $latchClears", latchClears >= 2)
        assertTrue("model audio must honour the latch", src.contains("|| tapCutReply"))
    }

    private fun session(): String = File(sourceRoot(), "service/GeminiLiveSession.kt").readText()

    private fun body(source: String, name: String): String {
        val signature = source.indexOf("fun $name(")
        if (signature < 0) fail("GeminiLiveSession no longer declares fun $name(")
        val open = source.indexOf('{', signature)
        var depth = 0
        var i = open
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open + 1, i)
            }
            i++
        }
        fail("unbalanced braces reading fun $name(")
        error("unreachable")
    }

    private fun sourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/src/main/java/com/kevin/legion")
            if (candidate.isDirectory) return candidate
            val here = File(dir, "src/main/java/com/kevin/legion")
            if (here.isDirectory) return here
            dir = dir.parentFile
        }
        fail("Could not locate the main source tree from ${System.getProperty("user.dir")}")
        error("unreachable")
    }
}
