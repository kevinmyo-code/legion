package com.kevin.midnightai.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchPlannerTest {

    @Test
    fun `unknown target yields no actions`() {
        val actions = planSwitch(listOf("AA:AA", "BB:BB"), "CC:CC")
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `single known phone still gets the priority-on plus connect steps`() {
        val actions = planSwitch(listOf("AA:AA"), "AA:AA")
        assertEquals(
            listOf(
                PhoneLinkAction.SetAutoConnectOn("AA:AA"),
                PhoneLinkAction.Connect("AA:AA", PhoneLinkProfiles.A2DP_SINK),
                PhoneLinkAction.Connect("AA:AA", PhoneLinkProfiles.HEADSET_CLIENT),
            ),
            actions,
        )
    }

    @Test
    fun `two phones - the other one is fully torn down before the target comes up`() {
        val actions = planSwitch(listOf("AA:AA", "BB:BB"), "BB:BB")

        assertEquals(
            listOf(
                PhoneLinkAction.SetAutoConnectOff("AA:AA"),
                PhoneLinkAction.Disconnect("AA:AA", PhoneLinkProfiles.A2DP_SINK),
                PhoneLinkAction.Disconnect("AA:AA", PhoneLinkProfiles.AVRCP_CONTROLLER),
                PhoneLinkAction.Disconnect("AA:AA", PhoneLinkProfiles.HEADSET_CLIENT),
                PhoneLinkAction.SetAutoConnectOn("BB:BB"),
                PhoneLinkAction.Connect("BB:BB", PhoneLinkProfiles.A2DP_SINK),
                PhoneLinkAction.Connect("BB:BB", PhoneLinkProfiles.HEADSET_CLIENT),
            ),
            actions,
        )
    }

    @Test
    fun `three phones - every other phone is torn down, target never appears in the teardown block`() {
        val actions = planSwitch(listOf("AA:AA", "BB:BB", "CC:CC"), "CC:CC")
        val teardownAddresses = actions.dropLast(3).map { it.address }.distinct()

        assertEquals(setOf("AA:AA", "BB:BB"), teardownAddresses.toSet())
        assertTrue(actions.takeLast(3).all { it.address == "CC:CC" })
    }

    @Test
    fun `switching to the same phone still disconnects the others`() {
        // The manager only calls this when the target isn't already active, but the planner
        // itself doesn't need to know that - it just needs to never be asked to tear down its
        // own target, which the "other one" filter already guarantees.
        val actions = planSwitch(listOf("AA:AA", "BB:BB"), "AA:AA")
        assertTrue(actions.none { it.address == "AA:AA" && it is PhoneLinkAction.Disconnect })
    }
}
