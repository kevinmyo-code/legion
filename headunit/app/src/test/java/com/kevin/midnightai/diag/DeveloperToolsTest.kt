package com.kevin.midnightai.diag

import com.kevin.midnightai.diag.DeveloperTools.RootOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeveloperToolsTest {

    // --- Intents ---

    @Test
    fun `the three Settings rows use the documented actions`() {
        assertEquals("android.settings.SETTINGS", DeveloperTools.androidSettings.action)
        assertEquals("android.settings.APPLICATION_DEVELOPMENT_SETTINGS", DeveloperTools.developerOptions.action)
        assertEquals("android.settings.DEVICE_INFO_SETTINGS", DeveloperTools.aboutDevice.action)
        listOf(DeveloperTools.androidSettings, DeveloperTools.developerOptions, DeveloperTools.aboutDevice).forEach {
            assertNull(it.componentPackage)
            assertNull(it.fragmentArgKey)
        }
    }

    @Test
    fun `wireless debugging tries the component, then the action, then highlighted Developer options`() {
        val chain = DeveloperTools.wirelessDebuggingChain()
        assertEquals(3, chain.size)

        assertNull(chain[0].action)
        assertEquals("com.android.settings", chain[0].componentPackage)
        assertEquals("com.android.settings.Settings\$WirelessDebuggingActivity", chain[0].componentClass)

        assertEquals("android.settings.WIRELESS_DEBUGGING_SETTINGS", chain[1].action)
        assertNull(chain[1].componentPackage)

        assertEquals("android.settings.APPLICATION_DEVELOPMENT_SETTINGS", chain[2].action)
        assertEquals("toggle_adb_wireless", chain[2].fragmentArgKey)
        assertEquals(":settings:fragment_args_key", DeveloperTools.EXTRA_FRAGMENT_ARG_KEY)
        assertEquals(":settings:show_fragment_args", DeveloperTools.EXTRA_SHOW_FRAGMENT_ARGUMENTS)
    }

    @Test
    fun `the wireless debugging row is hidden below API 30`() {
        assertFalse(DeveloperTools.showWirelessDebuggingRow(26))
        assertFalse(DeveloperTools.showWirelessDebuggingRow(29))
        assertTrue(DeveloperTools.showWirelessDebuggingRow(30))
        assertTrue(DeveloperTools.showWirelessDebuggingRow(34))
    }

    @Test
    fun `the wireless note says where to look on the fallback and that nothing opened on none`() {
        assertTrue(DeveloperTools.wirelessDebuggingNote(2).contains("Find Wireless debugging under Debugging"))
        assertEquals(DeveloperTools.NO_SCREEN, DeveloperTools.wirelessDebuggingNote(null))
    }

    // --- Status ---

    private fun inputs(api: Int, adbWifi: Int = -1, ip: String? = "192.168.43.20", port: Boolean? = false) =
        DeveloperTools.StatusInputs(
            release = if (api >= 30) "11" else "9",
            apiLevel = api,
            manufacturer = "alps",
            model = "XY-AUTO",
            fingerprint = "alps/full_k61v1_64/k61v1_64:9/PPR1.180610.011/1590000000:user/release-keys",
            adbEnabled = 0,
            adbWifiEnabled = adbWifi,
            ipv4 = ip,
            port5555Open = port,
        )

    @Test
    fun `status below API 30 has no adb_wifi_enabled line`() {
        val lines = DeveloperTools.statusLines(inputs(api = 28, adbWifi = 1))
        assertEquals(
            listOf(
                "Android 9 (API 28)",
                "Device: alps XY-AUTO",
                "Build: alps/full_k61v1_64/k61v1_64 9/PPR1.180610.011",
                "adb_enabled: 0 (off)",
                "IPv4: 192.168.43.20",
                "Port 5555 on 127.0.0.1: closed",
            ),
            lines,
        )
    }

    @Test
    fun `status at API 30 shows adb_wifi_enabled, and -1 reads not present`() {
        val absent = DeveloperTools.statusLines(inputs(api = 30, adbWifi = -1))
        assertTrue(absent.contains("adb_wifi_enabled: not present"))
        val on = DeveloperTools.statusLines(inputs(api = 30, adbWifi = 1, port = true))
        assertTrue(on.contains("adb_wifi_enabled: 1 (on)"))
        assertTrue(on.contains("Port 5555 on 127.0.0.1: accepting connections"))
    }

    @Test
    fun `status says in words when there is no address or no probe yet`() {
        val lines = DeveloperTools.statusLines(inputs(api = 28, ip = null, port = null))
        assertTrue(lines.contains("IPv4: no Wi-Fi or Ethernet address"))
        assertTrue(lines.contains("Port 5555 on 127.0.0.1: checking"))
        assertEquals("adb connect <unit IP>:5555", DeveloperTools.connectCommand(null))
        assertEquals("adb connect 10.0.0.5:5555", DeveloperTools.connectCommand("10.0.0.5"))
    }

    @Test
    fun `short fingerprint tolerates odd shapes`() {
        assertEquals("weird", DeveloperTools.shortFingerprint("weird"))
        assertEquals("", DeveloperTools.shortFingerprint(""))
    }

    // --- Root attempt ---

    @Test
    fun `root commands add adb_wifi_enabled only on API 30 and up`() {
        assertEquals(
            listOf("setprop persist.adb.tcp.port 5555 && setprop service.adb.tcp.port 5555 && stop adbd && start adbd"),
            DeveloperTools.rootCommands(28),
        )
        assertEquals(
            listOf(
                "setprop persist.adb.tcp.port 5555 && setprop service.adb.tcp.port 5555 && stop adbd && start adbd",
                "settings put global adb_wifi_enabled 1",
            ),
            DeveloperTools.rootCommands(30),
        )
    }

    @Test
    fun `port open after a clean run is the only success`() {
        val outcome = DeveloperTools.rootOutcome(suExists = true, exitCode = 0, portOpenAfter = true)
        assertEquals(RootOutcome.PORT_OPEN, outcome)
        assertEquals(
            "Port 5555 is now open, connect with adb connect 192.168.43.20:5555",
            DeveloperTools.rootMessage(outcome, "192.168.43.20"),
        )
    }

    @Test
    fun `no su, a refusal or a timeout reads no root`() {
        assertEquals(RootOutcome.NO_ROOT, DeveloperTools.rootOutcome(false, null, false))
        assertEquals(RootOutcome.NO_ROOT, DeveloperTools.rootOutcome(true, 1, false))
        assertEquals(RootOutcome.NO_ROOT, DeveloperTools.rootOutcome(true, null, false))
        assertEquals(
            "No root on this unit (su not found or refused)",
            DeveloperTools.rootMessage(RootOutcome.NO_ROOT, "192.168.43.20"),
        )
    }

    @Test
    fun `exit 0 never claims success while the port is closed`() {
        val outcome = DeveloperTools.rootOutcome(suExists = true, exitCode = 0, portOpenAfter = false)
        assertEquals(RootOutcome.STILL_CLOSED, outcome)
        assertEquals("Ran, but port 5555 is still closed", DeveloperTools.rootMessage(outcome, null))
    }

    @Test
    fun `no su beats a port that happened to be open`() {
        assertEquals(RootOutcome.NO_ROOT, DeveloperTools.rootOutcome(false, null, true))
    }

    @Test
    fun `root detail names what su did`() {
        val props = " Props asked for: persist.adb.tcp.port=5555, service.adb.tcp.port=5555."
        assertEquals("su could not be started.$props", DeveloperTools.rootDetail(false, null))
        assertEquals("su did not finish within 5 s and was stopped.$props", DeveloperTools.rootDetail(true, null))
        assertEquals("su exit code: 1$props", DeveloperTools.rootDetail(true, 1))
    }
}
