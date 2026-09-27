package com.kevin.legion.vehicle

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The OBD reconnect loop used to retry an absent dongle every ~15s forever (~5,700 BLE connect
 * attempts a day on the A25). These pin the backoff that replaced it: it grows, it caps so a car
 * you just got into still connects promptly, and it never overflows.
 */
@RunWith(RobolectricTestRunner::class)
class ObdReconnectBackoffTest {

    private fun delay(n: Int) = ObdBluetoothManager.reconnectDelayMs(n)

    @Test
    fun `first failure waits the base delay`() {
        assertEquals(ObdBluetoothManager.BASE_RECONNECT_DELAY_MS, delay(1))
    }

    @Test
    fun `it doubles per consecutive failure`() {
        assertEquals(10_000L, delay(2))
        assertEquals(20_000L, delay(3))
        assertEquals(40_000L, delay(4))
    }

    @Test
    fun `it caps, so getting into the car still connects within two minutes`() {
        assertEquals(ObdBluetoothManager.MAX_RECONNECT_DELAY_MS, delay(6))
        assertEquals(ObdBluetoothManager.MAX_RECONNECT_DELAY_MS, delay(50))
    }

    @Test
    fun `an absurd failure count neither overflows nor goes negative`() {
        assertEquals(ObdBluetoothManager.MAX_RECONNECT_DELAY_MS, delay(Int.MAX_VALUE))
        assertEquals(ObdBluetoothManager.BASE_RECONNECT_DELAY_MS, delay(0))
    }
}
