package com.kevin.midnightai.media

import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeRulesTest {

    @Test fun lowerIsNeverLimited() {
        assertEquals(VolumeRules.VoiceSet(5, capped = false), VolumeRules.voiceSet(currentPercent = 90, requestedPercent = 5))
    }

    @Test fun aSmallRaiseGoesThrough() {
        assertEquals(VolumeRules.VoiceSet(55, capped = false), VolumeRules.voiceSet(40, 55))
    }

    @Test fun oneCallNeverJumpsToMax() {
        assertEquals(VolumeRules.VoiceSet(50, capped = true), VolumeRules.voiceSet(20, 100))
    }

    @Test fun neverAboveTheVoiceCeiling() {
        assertEquals(VolumeRules.VoiceSet(80, capped = true), VolumeRules.voiceSet(70, 100))
        assertEquals(VolumeRules.VoiceSet(80, capped = true), VolumeRules.voiceSet(80, 95))
    }

    @Test fun alreadyAboveTheCeilingIsNotPulledDownByARaise() {
        assertEquals(VolumeRules.VoiceSet(90, capped = true), VolumeRules.voiceSet(90, 95))
    }

    @Test fun aSetWhileMutedNeverLandsLouderThanBeforeTheMute() {
        assertEquals(VolumeRules.VoiceSet(40, capped = true), VolumeRules.voiceSet(40, 75, mutedAtPercent = 40))
        assertEquals(VolumeRules.VoiceSet(20, capped = false), VolumeRules.voiceSet(40, 20, mutedAtPercent = 40))
        assertEquals(VolumeRules.VoiceSet(80, capped = true), VolumeRules.voiceSet(95, 100, mutedAtPercent = 95))
        // A platform that reports 0 while muted: one raise step from silence at most.
        assertEquals(VolumeRules.VoiceSet(30, capped = true), VolumeRules.voiceSet(0, 90, mutedAtPercent = 0))
    }

    @Test fun louderByVoiceStopsAtTheCeiling() {
        // 15-step stream: index 11 -> 12 is 80%, allowed; 12 -> 13 is 87%, refused.
        assertEquals(true, VolumeRules.voiceUpAllowed(11, 15))
        assertEquals(false, VolumeRules.voiceUpAllowed(12, 15))
        assertEquals(false, VolumeRules.voiceUpAllowed(15, 15))
        assertEquals(false, VolumeRules.voiceUpAllowed(0, 0))
    }

    @Test fun outOfRangeRequestsAreClamped() {
        assertEquals(VolumeRules.VoiceSet(0, capped = false), VolumeRules.voiceSet(30, -10))
    }
}
