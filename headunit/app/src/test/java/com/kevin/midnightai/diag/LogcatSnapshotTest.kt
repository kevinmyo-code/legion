package com.kevin.midnightai.diag

import org.junit.Assert.assertEquals
import org.junit.Test

class LogcatSnapshotTest {
    private fun line(pid: Int, msg: String, ms: Int = 0) =
        "10-15 08:00:00.%03d  %d  %d I Tag: %s".format(ms, pid, pid + 1, msg)

    @Test
    fun `only lines from our pids with a threadtime header are kept`() {
        val raw = listOf(
            "--------- beginning of main",
            line(100, "mine"),
            line(200, "someone else"),
            line(50, "mine before a crash"),
            "garbage with no header",
        )
        assertEquals(
            listOf(line(100, "mine"), line(50, "mine before a crash")),
            LogcatSnapshot.ours(raw, setOf("100", "50")),
        )
    }

    /**
     * Review NAV-3: Mapbox's own log lines (maps, navigation, native) never enter the drive log,
     * which is uploaded to the engine: they can carry route URLs and positions.
     */
    @Test
    fun `mapbox lines never enter the drive log`() {
        val ours = "11-20 09:30:01.120  1234  1300 I BeaconClient: phone fix accepted"
        val raw = listOf(
            "11-20 09:30:01.123  1234  1300 I Mapbox  : [maps-core\\Map]: Style loaded in 812 ms",
            "11-20 09:30:01.124  1234  1301 W MapboxNavigation: [nav-sdk\\RouterWrapper] https://api.mapbox.com/directions/v5/mapbox/driving-traffic/-95.37,29.76;-95.40,29.70?access_token=pk.x",
            "11-20 09:30:01.125  1234  1302 D MbxNavigator: route progress 29.7604,-95.3698",
            "11-20 09:30:01.126  1234  1303 I nav-native: map matched",
            ours,
            "11-20 09:30:01.127  1234  1304 E OkHttp  : --> GET https://api.mapbox.com/styles/v1/mapbox/traffic-night-v2",
        )
        assertEquals(listOf(ours), LogcatSnapshot.ours(raw, setOf("1234")))
    }

    @Test
    fun `new lines are those after the last occurrence of the anchor`() {
        val ours = listOf(line(1, "a", 1), line(1, "b", 2), line(1, "c", 3))
        assertEquals(listOf(line(1, "c", 3)), LogcatSnapshot.newSince(ours, line(1, "b", 2)))
        assertEquals(emptyList<String>(), LogcatSnapshot.newSince(ours, line(1, "c", 3)))
    }

    @Test
    fun `a missing anchor means the buffer wrapped so everything is new`() {
        val ours = listOf(line(1, "x", 1), line(1, "y", 2))
        assertEquals(ours, LogcatSnapshot.newSince(ours, line(1, "gone", 9)))
        assertEquals(ours, LogcatSnapshot.newSince(ours, null))
    }

    @Test
    fun `a repeated line is anchored on its last occurrence not its first`() {
        val dup = line(1, "same", 5)
        val ours = listOf(dup, line(1, "mid", 6), dup, line(1, "tail", 7))
        assertEquals(listOf(line(1, "tail", 7)), LogcatSnapshot.newSince(ours, dup))
    }
}
