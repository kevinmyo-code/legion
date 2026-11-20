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

    @Test fun outOfRangeRequestsAreClamped() {
        assertEquals(VolumeRules.VoiceSet(0, capped = false), VolumeRules.voiceSet(30, -10))
    }
}
