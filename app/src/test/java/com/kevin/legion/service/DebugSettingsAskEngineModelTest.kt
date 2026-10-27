package com.kevin.legion.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kevin.legion.ai.SubAgent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DebugSettingsAskEngineModelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `defaults to Flash-Lite`() {
        assertEquals(SubAgent.DEFAULT_MODEL, DebugSettings.askEngineModel(context))
    }

    @Test
    fun `stores the alternate model`() {
        DebugSettings.setAskEngineModel(context, DebugSettings.ASK_ENGINE_MODEL_ALT)
        assertEquals("gemini-3.8-flash", DebugSettings.askEngineModel(context))
    }

    @Test
    fun `an unknown stored value reads as the default`() {
        DebugSettings.setAskEngineModel(context, "gemini-0-made-up")
        assertEquals(SubAgent.DEFAULT_MODEL, DebugSettings.askEngineModel(context))
    }
}
