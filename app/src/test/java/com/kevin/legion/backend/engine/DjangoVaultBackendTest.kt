package com.kevin.legion.backend.engine

import com.kevin.legion.backend.engine.EngineTestSupport.json
import com.kevin.legion.vault.BiometricUnlock
import com.kevin.legion.vault.VaultController
import com.kevin.legion.vault.VaultOutcome
import com.kevin.legion.vault.VaultSecret
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * memory-and-vault ticket 12: `/api/vault/items` as `server/id_vault/views.py` serves it. The values
 * here are made up and shaped like no real identifier.
 */
@RunWith(RobolectricTestRunner::class)
class DjangoVaultBackendTest {

    private val context = RuntimeEnvironment.getApplication()
    private val fakeValue = "TEST-VALUE-0000"

    private fun backend(engine: EngineTestSupport.RecordingEngine) =
        DjangoVaultBackend(EngineHttp(EngineTestSupport.signedInConfig(context), engine.client()))

    @Test
    fun `the list is labels and kinds only`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine {
            json(
                """{"results": [{"id": "i1", "label": "Passport", "kind": "passport",
                "created_at": "2026-11-05T14:00:00Z", "updated_at": "2026-11-05T14:00:00Z",
                "last_revealed_at": null}]}""",
            )
        }
        val items = (backend(engine).list() as VaultOutcome.Ok).value
        assertEquals("Passport", items.single().label)
        assertEquals("/api/vault/items", engine.requests.single().url.encodedPath)
    }

    @Test
    fun `reveal sends the fingerprint header and wraps the value`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine {
            json("""{"id": "i1", "label": "Passport", "kind": "passport", "value": "$fakeValue"}""")
        }
        val revealed = (backend(engine).reveal("i1", BiometricUnlock()) as VaultOutcome.Ok).value
        val req = engine.requests.single()
        assertEquals("POST", req.method.value)
        assertEquals("/api/vault/items/i1/reveal", req.url.encodedPath)
        assertEquals("biometric", req.headers["X-Legion-Unlocked"])
        assertEquals(fakeValue, revealed.secret.reveal())
        assertFalse(revealed.toString().contains(fakeValue))
        assertFalse(revealed.secret.toString().contains(fakeValue))
    }

    @Test
    fun `an unreadable reveal reply never quotes the body`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { json("""{"value": "$fakeValue"}""") }
        val outcome = backend(engine).reveal("i1", BiometricUnlock()) as VaultOutcome.Unconfirmed
        assertFalse(outcome.sentence.contains(fakeValue))
    }

    @Test
    fun `the reveal limit is said in words with the engine's sentence`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine {
            json(
                """{"detail": "Request was throttled. Expected available in 3540 seconds."}""",
                HttpStatusCode.TooManyRequests,
            )
        }
        val refused = backend(engine).reveal("i1", BiometricUnlock()) as VaultOutcome.Refused
        assertEquals(429, refused.status)
        val words = VaultController.failed(refused, "revealed")
        assertTrue(words.contains("10 reveals an hour"))
        assertTrue(words.contains("Expected available in 3540 seconds"))
    }

    @Test
    fun `a missing fingerprint header refusal is the engine's sentence`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine {
            json(
                """{"detail": "Nothing was revealed. A vault item opens only after the fingerprint prompt."}""",
                HttpStatusCode.Forbidden,
            )
        }
        val refused = backend(engine).reveal("i1", BiometricUnlock()) as VaultOutcome.Refused
        assertTrue(refused.sentence.startsWith("Nothing was revealed."))
    }

    @Test
    fun `create sends the value once and the reply carries only the label`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine {
            json("""{"id": "i2", "label": "SSN", "kind": "ssn", "created_at": "2026-11-05T14:00:00Z",
                "updated_at": "2026-11-05T14:00:00Z", "last_revealed_at": null}""", HttpStatusCode.Created)
        }
        val item = (backend(engine).create("SSN", "ssn", VaultSecret(fakeValue)) as VaultOutcome.Ok).value
        assertEquals("SSN", item.label)
        val body = (engine.requests.single().body as TextContent).text
        assertTrue(body.contains(fakeValue))
        assertFalse(item.toString().contains(fakeValue))
    }

    @Test
    fun `an edit with no new value sends no value`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine {
            json("""{"id": "i2", "label": "Social", "kind": "ssn", "created_at": "2026-11-05T14:00:00Z",
                "updated_at": "2026-11-05T14:00:00Z", "last_revealed_at": null}""")
        }
        backend(engine).update("i2", label = "Social", kind = null, value = null)
        val req = engine.requests.single()
        assertEquals("PATCH", req.method.value)
        assertEquals("/api/vault/items/i2", req.url.encodedPath)
        assertEquals("""{"label":"Social"}""", (req.body as TextContent).text)
    }

    @Test
    fun `delete is a DELETE on the item`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { respond("", HttpStatusCode.NoContent) }
        assertEquals(VaultOutcome.Ok(Unit), backend(engine).delete("i2"))
        assertEquals("DELETE", engine.requests.single().method.value)
    }
}
