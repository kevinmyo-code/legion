package com.kevin.legion.service.turn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kevin, 2026-11-10: on car Bluetooth the conversation listened through the phone's mic. When to
 * switch to the hands-free mic, and which device. Made-up device addresses.
 */
class BluetoothMicTest {

    @Test
    fun `on Bluetooth with a hands-free device and the setting on, listen through it`() {
        assertTrue(
            BluetoothMic.wanted(
                settingOn = true, route = AudioRoute.BLUETOOTH, inCall = false, handsFreeAvailable = true,
            ),
        )
    }

    @Test
    fun `everything else keeps the phone mic`() {
        assertFalse(BluetoothMic.wanted(false, AudioRoute.BLUETOOTH, inCall = false, handsFreeAvailable = true))
        assertFalse(BluetoothMic.wanted(true, AudioRoute.PHONE_SPEAKER, inCall = false, handsFreeAvailable = true))
        assertFalse(BluetoothMic.wanted(true, AudioRoute.WIRED_HEADSET, inCall = false, handsFreeAvailable = true))
        // A phone call owns the route (Telecom).
        assertFalse(BluetoothMic.wanted(true, AudioRoute.BLUETOOTH, inCall = true, handsFreeAvailable = true))
        // An A2DP-only speaker has no mic.
        assertFalse(BluetoothMic.wanted(true, AudioRoute.BLUETOOTH, inCall = false, handsFreeAvailable = false))
    }

    @Test
    fun `the hands-free device playing the media wins, else the first one`() {
        val phoneMic = BluetoothMic.Candidate(1, isHandsFree = false, address = null)
        val headset = BluetoothMic.Candidate(2, isHandsFree = true, address = "00:11:22:33:44:55")
        val car = BluetoothMic.Candidate(3, isHandsFree = true, address = "AA:BB:CC:DD:EE:FF")
        assertEquals(3, BluetoothMic.pick(listOf(phoneMic, headset, car), setOf("AA:BB:CC:DD:EE:FF"))?.id)
        assertEquals(2, BluetoothMic.pick(listOf(phoneMic, headset, car), emptySet())?.id)
        assertNull(BluetoothMic.pick(listOf(phoneMic), setOf("AA:BB:CC:DD:EE:FF")))
    }

    /**
     * Kevin, 2026-11-24, in the car: with the head unit's Bluetooth connected, Alfred did not hear him
     * at all, while the phone's own log read `bluetooth mic routed=true` on every turn. A routed link
     * whose microphone sends digital silence is given up on and the phone's own mic takes over.
     */
    @Test
    fun `a routed hands-free mic that sends digital silence loses the conversation`() {
        assertTrue(
            BluetoothMic.fallBack(
                onBluetoothMic = true,
                alreadyFellBack = false,
                elapsedMs = BluetoothMic.DEAF_MS,
                peakSinceOpen = 0,
            ),
        )
    }

    @Test
    fun `a hands-free mic that hears the room keeps the conversation`() {
        // Room noise alone clears the silence floor: a man saying nothing is not a dead microphone.
        assertFalse(
            BluetoothMic.fallBack(
                onBluetoothMic = true,
                alreadyFellBack = false,
                elapsedMs = BluetoothMic.DEAF_MS * 4,
                peakSinceOpen = 240,
            ),
        )
    }

    @Test
    fun `nothing falls back before the window, on the phone's own mic, or twice`() {
        // Short of the window, a silent capture is a link still finding its feet.
        assertFalse(BluetoothMic.fallBack(true, alreadyFellBack = false, elapsedMs = 400L, peakSinceOpen = 0))
        // On the phone's own mic there is nowhere to fall: a silent window is a quiet room.
        assertFalse(
            BluetoothMic.fallBack(
                onBluetoothMic = false,
                alreadyFellBack = false,
                elapsedMs = BluetoothMic.DEAF_MS * 4,
                peakSinceOpen = 0,
            ),
        )
        // Once per conversation, so a deaf phone mic after the switch cannot bounce the route.
        assertFalse(
            BluetoothMic.fallBack(
                onBluetoothMic = true,
                alreadyFellBack = true,
                elapsedMs = BluetoothMic.DEAF_MS * 4,
                peakSinceOpen = 0,
            ),
        )
    }

    @Test
    fun `the fallback waits longer than the link's own settle`() {
        assertTrue("a link still coming up is never called deaf", BluetoothMic.DEAF_MS > BluetoothMic.SCO_SETTLE_MS)
    }

    @Test
    fun `the switch defaults on and the link gets time to come up`() {
        assertTrue(VoiceTurnSettings.BLUETOOTH_MIC_DEFAULT)
        assertTrue(BluetoothMic.SCO_SETTLE_MS in 1_000L..3_000L)
        // Kevin kept the 700 ms Bluetooth reopen delay with this change.
        assertEquals(700L, MicReopen.settleMs(AudioRoute.BLUETOOTH))
    }
}
