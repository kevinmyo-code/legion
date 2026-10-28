package com.kevin.legion.service

import com.kevin.legion.ai.AgentResult
import com.kevin.legion.ai.LookupProbe
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The LIVE_LATENCY lookup lines (async-lookups ticket 03), pinned verbatim: ticket 04 greps for
 * them and `tools/voice_latency.py` parses them, so a change here is a change there.
 */
class LiveLatencyLookupLinesTest {

    @Test
    fun `route line`() {
        val probe = LookupProbe("ask_fleet").apply {
            totalMs = 2_400
            postMs = 1_100
            toolsMs = 1_250
            toolNames = listOf("get_codes", "get_health")
            model = "gemini-3.5-flash-lite"
        }
        assertEquals(
            "route_ms=2400 route_post_ms=1100 route_tools_ms=1250 route_tool_count=2 " +
                "route_tools=get_codes,get_health tool=ask_fleet model=gemini-3.5-flash-lite result=Success",
            LiveLatency.routeLine(probe, AgentResult.Success("x")),
        )
    }

    @Test
    fun `route line with no tool says none`() {
        val probe = LookupProbe("ask_goals").apply {
            totalMs = 900
            postMs = 880
            model = "gemini-3.8-flash"
        }
        assertEquals(
            "route_ms=900 route_post_ms=880 route_tools_ms=0 route_tool_count=0 route_tools=none " +
                "tool=ask_goals model=gemini-3.8-flash result=RateLimited",
            LiveLatency.routeLine(probe, AgentResult.RateLimited),
        )
    }

    @Test
    fun `investigate line`() {
        val probe = LookupProbe("ask_engine").apply {
            totalMs = 5_200
            rounds = 3
            model = "gemini-3.5-flash-lite"
        }
        assertEquals(
            "investigate_ms=5200 rounds=3 tool=ask_engine model=gemini-3.5-flash-lite result=Failed",
            LiveLatency.investigateLine(probe, AgentResult.Failed),
        )
    }

    @Test
    fun `fallback line, with and without a tool`() {
        assertEquals(
            "subagent_fallback from=gemini-3.5-flash-lite to=gemini-3.8-flash reason=Overloaded tool=ask_mail",
            LiveLatency.fallbackLine("gemini-3.5-flash-lite", "gemini-3.8-flash", "Overloaded", "ask_mail"),
        )
        assertEquals(
            "subagent_fallback from=gemini-3.5-flash-lite to=gemini-3.8-flash reason=RateLimited",
            LiveLatency.fallbackLine("gemini-3.5-flash-lite", "gemini-3.8-flash", "RateLimited", null),
        )
    }
}
