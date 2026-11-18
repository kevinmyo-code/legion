package com.kevin.legion.vehicle

import com.kevin.legion.vehicle.diag.DiagVoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * A clear-codes question dies with the connection it was asked on (2026-11-17 review): before this a
 * "yes" within three minutes could land on whatever car the adapter was plugged into next. Driven
 * through the Bluetooth manager's own disconnect, not the hook (2026-11-18 review).
 */
@RunWith(RobolectricTestRunner::class)
class ClearAskDisconnectTest {
    private class FakeLink : ObdTransport {
        override val inputStream = ByteArrayInputStream(ByteArray(0))
        override val outputStream = ByteArrayOutputStream()
        override val label = "00:00:00:00:00:01"
        override fun close() = Unit
    }

    /** The manager holds the link privately; the test plants one so its real disconnect runs. */
    private fun plantLink() {
        ObdBluetoothManager::class.java.getDeclaredField("transport").apply { isAccessible = true }.set(null, FakeLink())
    }

    @Test
    fun `a pending clear question is void once the adapter disconnects`() {
        plantLink()
        assertTrue(ObdBluetoothManager.isConnected)
        DtcClearController.voiceGate.asked("clear the codes on the invented truck")
        DiagVoice.gate.asked("clear the codes in every module")
        ObdBluetoothManager.forceReconnect()
        assertEquals(ClearCodesVoiceGate.NO_QUESTION, DtcClearController.voiceGate.checkConfirmed("yes"))
        assertEquals(ClearCodesVoiceGate.NO_QUESTION, DiagVoice.gate.checkConfirmed("yes"))
    }

    @Test
    fun `the question is armed before the connection is checked again, so a drop during the read leaves none`() {
        val src = File(listOf("src/main/java", "app/src/main/java").map { File(it) }.first { it.isDirectory },
            "com/kevin/legion/service/LiveToolbox.kt").readText()
        val body = src.substring(src.indexOf("private suspend fun clearCodes("))
        val asked = body.indexOf("gate.asked(userText)")
        val recheck = body.indexOf("if (!ObdBluetoothManager.isConnected)", asked)
        assertTrue(asked >= 0 && recheck > asked)
        assertTrue(body.substring(recheck, recheck + 200).contains("gate.reset()"))
    }

    @Test
    fun `a pending clear question is void once the conversation ends`() {
        val src = File(listOf("src/main/java", "app/src/main/java").map { File(it) }.first { it.isDirectory },
            "com/kevin/legion/service/cascade/CascadeSession.kt").readText()
        val release = src.substring(src.indexOf("private suspend fun releaseNow("))
        assertTrue(release.substring(0, 400).contains("DtcClearController.forgetClearQuestion()"))
        DtcClearController.voiceGate.asked("clear the codes on the invented truck")
        DtcClearController.forgetClearQuestion()
        assertEquals(ClearCodesVoiceGate.NO_QUESTION, DtcClearController.voiceGate.checkConfirmed("yes"))
    }
}
