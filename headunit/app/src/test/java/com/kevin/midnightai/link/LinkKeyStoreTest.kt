package com.kevin.midnightai.link

import android.content.Context
import android.os.Build
import android.view.KeyEvent
import com.kevin.midnightai.diag.InputProbe
import com.kevin.midnightai.ui.KeyDispatch
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * HU-1 T3 storage, review H1, H4, M7, M8 (2026-11-20): two slots, all or nothing, no plaintext,
 * a failed re-pair keeps the old pairing, Forget removes exactly what came over the link.
 */
/** The fakes' "ciphertext": opaque enough that a plaintext search cannot find the value. */
private fun fakeEnc(v: String) = "enc:" + v.reversed()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class LinkKeyStoreTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private var keystoreWorks = true

    /** In-memory engine and Gemini stores with the same contract as the real ones. */
    private class FakeEngine : EngineAccess {
        var values = mutableMapOf<String, String?>("base_url" to null, "device_token" to null, "user_id" to null)
        var failSave = false
        override fun snapshot() = values.toMap()
        override fun restore(snapshot: Map<String, String?>): Boolean { values = snapshot.toMutableMap(); return true }
        override fun saveStrict(baseUrl: String, token: String, userId: String): Boolean {
            if (failSave) return false
            values = mutableMapOf("base_url" to baseUrl, "device_token" to fakeEnc(token), "user_id" to userId)
            return true
        }
        override fun rawToken() = values["device_token"]
        override fun clear(): Boolean { values["device_token"] = null; values["user_id"] = null; return true }
    }

    private class FakeGemini : GeminiAccess {
        var enc: String? = null
        var failSave = false
        override fun snapshot() = mapOf("gemini_api_key_enc" to enc)
        override fun restore(snapshot: Map<String, String?>): Boolean { enc = snapshot["gemini_api_key_enc"]; return true }
        override fun saveStrict(key: String): Boolean { if (failSave) return false; enc = fakeEnc(key); return true }
        override fun raw() = enc
        override fun clear(): Boolean { enc = null; return true }
    }

    private val engine = FakeEngine()
    private val gemini = FakeGemini()

    private fun store(constant: String = "https://engine.example") = LinkKeyStore(
        context, engine, gemini, constant,
        encrypt = { if (keystoreWorks) "e:" + it.reversed() else null },
        decrypt = { if (it.startsWith("e:")) it.removePrefix("e:").reversed() else null },
    )

    private val keyA = ByteArray(32) { 1 }
    private val keyB = ByteArray(32) { 2 }
    private fun handover(token: String? = "test-token", gem: String? = "AIza-test") =
        Handover(engineToken = token, engineUserId = "u-1", geminiKey = gem, mapboxToken = "pk.test", phoneName = "Test phone")

    @Before fun clean() {
        context.getSharedPreferences("car_link", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun pair(key: ByteArray, h: Handover = handover()) {
        assertNull(store().stage(h, key))
        assertNull(store().commitPending())
    }

    @Test fun stagedIsPendingAndOnlyACommitMakesItPaired() {
        assertNull(store().stage(handover(), keyA))
        val k = store().keys()
        assertEquals(LinkStatus.PENDING, k.status)
        assertArrayEquals(keyA, k.pending)
        assertNull(engine.rawToken())
        assertNull(store().commitPending())
        assertEquals(LinkStatus.PAIRED, store().keys().status)
        assertArrayEquals(keyA, store().keys().committed)
        assertEquals(fakeEnc("test-token"), engine.rawToken())
        assertEquals("https://engine.example", engine.values["base_url"])
        assertEquals(fakeEnc("AIza-test"), gemini.enc)
    }

    @Test fun nothingIsStoredInTheClear() {
        pair(keyA)
        val raw = context.getSharedPreferences("car_link", Context.MODE_PRIVATE).all.values.joinToString()
        assertFalse(raw, raw.contains("test-token") || raw.contains("AIza") || raw.contains("pk.test"))
    }

    @Test fun m7_aBrokenKeystoreStoresNothingAndTheOldPairingStays() {
        pair(keyA)
        keystoreWorks = false
        assertNotNull(store().stage(handover(token = "other"), keyB))
        keystoreWorks = true
        val k = store().keys()
        assertEquals(LinkStatus.PAIRED, k.status)
        assertArrayEquals(keyA, k.committed)
        assertEquals(fakeEnc("test-token"), engine.rawToken())
    }

    @Test fun m7_aCommitThatFailsHalfwayRestoresEverything() {
        pair(keyA)
        assertNull(store().stage(handover(token = "token-two", gem = "AIza-two"), keyB))
        gemini.failSave = true
        assertNotNull(store().commitPending())
        // The engine write that already happened is undone; the old pairing is intact.
        assertEquals(fakeEnc("test-token"), engine.rawToken())
        assertEquals(fakeEnc("AIza-test"), gemini.enc)
        assertArrayEquals(keyA, store().keys().committed)
        assertArrayEquals(keyB, store().keys().pending)
    }

    @Test fun h4_noHttpsConstantMeansNoTokenIsKept() {
        val noEngine = store(constant = "")
        assertNull(noEngine.stage(handover(), keyA))
        assertNotNull(noEngine.lastNote)
        assertNull(noEngine.commitPending())
        assertNull(engine.rawToken())
        assertNull(store(constant = "http://engine.example").stage(handover(), keyB))
        assertNull(store(constant = "http://engine.example").commitPending())
        assertNull(engine.rawToken())
    }

    @Test fun m8_forgetRemovesExactlyWhatCameOverTheLink() {
        pair(keyA, handover(token = "test-token", gem = null))
        gemini.enc = "enc:typed-by-hand"
        store().forget()
        assertNull(engine.rawToken())
        assertEquals("enc:typed-by-hand", gemini.enc)
        assertEquals(LinkStatus.NOT_PAIRED, store().keys().status)
    }

    @Test fun m7_aNewPairingWithoutATokenRemovesTheOldPairingsToken() {
        pair(keyA)
        pair(keyB, handover(token = null, gem = null))
        assertNull(engine.rawToken())
        assertNull(gemini.enc)
        assertArrayEquals(keyB, store().keys().committed)
    }

    @Test fun h1_aRecordWhoseKeyCannotBeReadIsBroken() {
        pair(keyA)
        context.getSharedPreferences("car_link", Context.MODE_PRIVATE).edit().putString("committed_enc", "garbage").commit()
        assertEquals(LinkStatus.BROKEN, store().keys().status)
        assertTrue(store().hasRecord())
    }

    @Test fun anUnreadablePendingIsKeptAndTheCommittedOneRulesMeanwhile() {
        pair(keyA)
        context.getSharedPreferences("car_link", Context.MODE_PRIVATE).edit().putString("pending_enc", "garbage").commit()
        val keys = store().keys()
        // Review P2: the true state is "waiting", not "Paired with <old name>", and the read is retried.
        assertEquals(LinkStatus.PENDING, keys.status)
        assertTrue(keys.pendingUnreadable)
        assertTrue(keys.committed != null && keys.pending == null)
        // Review N4: a read failure never deletes; commit says it could not read it, not "nothing pending".
        assertTrue(context.getSharedPreferences("car_link", Context.MODE_PRIVATE).contains("pending_enc"))
        assertTrue(store().commitPending() != HandoverSink.NOTHING_PENDING)
    }

    @Test fun n4_aTransientReadFailureNeverDeletesThePendingPairing() {
        pair(keyA)
        store().stage(handover(), keyB)
        var failOnce = true
        val flaky = LinkKeyStore(
            context, engine, gemini, "https://engine.example",
            encrypt = { "e:" + it.reversed() },
            decrypt = { if (failOnce && it.contains("e:")) { failOnce = false; null } else if (it.startsWith("e:")) it.removePrefix("e:").reversed() else null },
        )
        flaky.keys()
        assertTrue(context.getSharedPreferences("car_link", Context.MODE_PRIVATE).contains("pending_enc"))
        assertArrayEquals(keyB, store().keys().pending)
    }

    @Test fun dropPendingKeepsTheCommittedPairing() {
        pair(keyA)
        store().stage(handover(), keyB)
        store().dropPending()
        assertEquals(LinkStatus.PAIRED, store().keys().status)
        assertArrayEquals(keyA, store().keys().committed)
    }

    @Test fun theCheckDigitsTypedOnAKeyboardDuringPairingAreNeverLogged() {
        InputProbe.clearForTest()
        listOf(false, true).forEach { editor ->
            (KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9).forEach { code ->
                KeyDispatch.dispatch(KeyEvent(KeyEvent.ACTION_DOWN, code), editor, { _, _ -> }, {}, { false })
                KeyDispatch.dispatch(KeyEvent(KeyEvent.ACTION_UP, code), editor, { _, _ -> }, {}, { false })
            }
        }
        assertTrue(InputProbe.wheel.value.isEmpty())
    }

    @Test fun enginePolicyIsHttpsOnly() {
        assertEquals("https://engine.example", EnginePolicy.handoverEngineUrl("https://engine.example"))
        assertNull(EnginePolicy.handoverEngineUrl("http://engine.example"))
        assertNull(EnginePolicy.handoverEngineUrl(""))
        assertNull(EnginePolicy.handoverEngineUrl("https://"))
    }
}
