package com.kevin.midnightai.ai

import android.content.Context
import android.os.Build
import com.kevin.midnightai.link.LinkKeyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Kevin, 2026-11-20: the unit uses the SAME Gemini key as the phone, handed over the car link. The
 * handed-over key wins over one typed on the unit, Settings says in words where the key came from
 * (never the key), and Forget removes only the link's key: a typed key comes back. Made-up keys.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class GeminiKeySourceTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun theHandedOverKeyWinsOverATypedOne() {
        assertEquals(GeminiKeyChoice("link-key", GeminiKeySource.CAR_LINK), GeminiKeyChoice.resolve("link-key", "typed-key"))
        assertEquals(GeminiKeyChoice("typed-key", GeminiKeySource.TYPED), GeminiKeyChoice.resolve(null, "typed-key"))
        assertEquals(GeminiKeyChoice("", GeminiKeySource.NONE), GeminiKeyChoice.resolve(" ", ""))
    }

    @Test fun settingsSaysWhereTheKeyCameFromNeverTheKey() {
        val link = GeminiKeyChoice.words(GeminiKeySource.CAR_LINK, builtIn = false)
        assertTrue(link, link.contains("phone") && link.contains("car link"))
        assertTrue(GeminiKeyChoice.words(GeminiKeySource.TYPED, builtIn = false).contains("typed on this unit"))
        assertTrue(GeminiKeyChoice.words(GeminiKeySource.NONE, builtIn = false).contains("No Gemini key"))
    }

    @Test fun forgettingTheLinkKeyLeavesTheTypedKey() {
        CompanionProfile.saveGeminiKey(context, "typed-key-made-up")
        val linkSlot = LinkKeyStore.geminiLinkAccess(context)
        // Whatever the link slot holds, clearing it must not touch the key typed on the unit.
        linkSlot.clear()
        assertEquals("typed-key-made-up", CompanionProfile.typedGeminiKey(context))
        assertTrue(linkSlot.snapshot().keys.none { it == "gemini_api_key_enc" || it == "gemini_api_key" })
    }
}
