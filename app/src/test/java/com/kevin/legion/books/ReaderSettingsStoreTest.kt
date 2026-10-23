package com.kevin.legion.books

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReaderSettingsStoreTest {
    private val store = ReaderSettingsStore(ApplicationProvider.getApplicationContext<Context>())

    @Test fun `a fresh store reads full screen on and the warm filter off`() {
        val s = store.read()
        assertTrue(s.fullScreen)
        assertEquals(false, s.warmOn)
        assertEquals(WarmFilter.DEFAULT_STRENGTH, s.warmStrength)
    }

    @Test fun `every comfort setting survives a write and a read`() {
        val saved = ReaderSettings(
            textSize = 20, theme = ReaderTheme.SEPIA, fullScreen = false,
            warmOn = true, warmStrength = 72, warmAuto = true,
        )
        store.write(saved)
        assertEquals(saved, store.read())
    }
}
