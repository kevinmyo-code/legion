package com.kevin.midnightai.link

import android.content.Context
import android.os.Build
import android.view.KeyEvent
import com.kevin.midnightai.diag.InputProbe
import com.kevin.midnightai.ui.KeyDispatch
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** HU-1 T3: the link key's storage on the unit, and the key log while pairing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class LinkKeyStoreTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    // A reversible stand-in for the Keystore (Robolectric has no AndroidKeyStore).
    private fun store(works: Boolean = true) = LinkKeyStore(
        context,
        encrypt = { if (works) "enc:" + it.reversed() else null },
        decrypt = { it.removePrefix("enc:").reversed() },
    )

    @Test fun aSavedKeyComesBackAndNothingIsStoredInTheClear() {
        val key = ByteArray(32) { (it + 1).toByte() }
        assertTrue(store().save(key, "Test phone", "pk.test", "Test hotspot", "test-pass"))
        assertArrayEquals(key, store().linkKey())
        assertEquals("Test phone", store().phoneName())
        val raw = context.getSharedPreferences("car_link", Context.MODE_PRIVATE).all.values.joinToString()
        assertFalse(raw, raw.contains("pk.test") || raw.contains("test-pass"))
    }

    @Test fun aBrokenKeystoreStoresNothing() {
        store().forget()
        assertFalse(store(works = false).save(ByteArray(32), null, null, null, null))
        assertNull(store().linkKey())
    }

    @Test fun forgetRemovesTheLink() {
        store().save(ByteArray(32) { 1 }, null, null, null, null)
        store().forget()
        assertNull(store().linkKey())
    }

    @Test fun theCheckDigitsTypedOnAKeyboardDuringPairingAreNeverLogged() {
        // The pairing screen takes no typed input; a keyboard on the unit must still log nothing
        // of a check code, with or without a focused editor.
        InputProbe.clearForTest()
        listOf(false, true).forEach { editor ->
            (KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9).forEach { code ->
                KeyDispatch.dispatch(KeyEvent(KeyEvent.ACTION_DOWN, code), editor, { _, _ -> }, {}, { false })
                KeyDispatch.dispatch(KeyEvent(KeyEvent.ACTION_UP, code), editor, { _, _ -> }, {}, { false })
            }
        }
        assertTrue(InputProbe.wheel.value.isEmpty())
    }
}
