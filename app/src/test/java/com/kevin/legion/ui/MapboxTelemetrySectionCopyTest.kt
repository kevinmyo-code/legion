package com.kevin.legion.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/** The Setup row's words, pinned: the SDK's own answer decides the sentence, and unreadable is not "off". */
class MapboxTelemetrySectionCopyTest {
    @Test fun anUnreadableSettingIsSaidAsUnreadableNotAsOff() {
        val text = MapboxTelemetrySectionCopy.explainer(null)
        assertTrue(text, text.contains("could not be read"))
    }

    @Test fun onAndOffBothSayTheLocationStillGoesToMapboxToFindARoute() {
        for (state in listOf(true, false)) {
            assertTrue(MapboxTelemetrySectionCopy.explainer(state).contains("sends your location to Mapbox"))
        }
    }

    @Test fun onNamesWhatIsReportedAndOffSaysItIsNot() {
        assertTrue(MapboxTelemetrySectionCopy.explainer(true).contains("report anonymous usage"))
        assertTrue(MapboxTelemetrySectionCopy.explainer(false).startsWith("Off."))
    }
}
