package com.kevin.midnightai.update

import android.app.Application
import android.os.Build
import com.kevin.midnightai.backend.engine.EngineConfig
import com.kevin.midnightai.backend.engine.EngineHttp
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The thin Android edges of the head unit updater (alfred-ota ticket 08), mirroring the phone's
 * `AndroidPackageInstallerGatewayTest` and adding the two network doors: the engine call on this
 * unit's device token, and the APK download that must never carry it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class UpdateAndroidTest {

    @get:Rule val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Before
    fun start() = server.start()

    @After
    fun stop() {
        runCatching { server.shutdown() }
    }

    private fun backend(): DjangoUpdateBackend {
        val config = EngineConfig(
            context = app,
            encrypt = { plain -> "ENC($plain)" },
            decrypt = { blob -> blob.removePrefix("ENC(").removeSuffix(")") },
        )
        config.saveBaseUrl(server.url("/").toString().trimEnd('/'))
        config.saveSession("unit-token", "11111111-1111-1111-1111-111111111111")
        return DjangoUpdateBackend(EngineHttp(config, OkHttpClient()))
    }

    @Test
    fun `one session for this app, written and committed`() {
        val apk = tmp.newFile("headunit.apk").apply { writeBytes(ByteArray(4096) { it.toByte() }) }

        val result = AndroidPackageInstallerGateway(app).install(apk, apk.length())

        assertTrue(result.exceptionOrNull()?.toString() ?: "", result.isSuccess)
        val sessions = app.packageManager.packageInstaller.allSessions
        assertEquals(1, sessions.size)
        assertEquals(app.packageName, sessions.single().appPackageName)
    }

    @Test
    fun `latest asks for the headunit app on the device token, and 404 is no release`() = runBlocking {
        val sha = "b".repeat(64)
        server.enqueue(
            MockResponse().setBody(
                """{"id":"r1","app":"headunit","version_code":263101200,"version_name":"1.0+ab12cd3",
                "sha256":"$sha","size_bytes":5,"notes":"","signer_sha256":"${"a".repeat(64)}"}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"No release has been published."}"""))
        val backend = backend()

        val release = backend.latest().getOrThrow()!!
        val request = server.takeRequest()
        assertEquals("/api/releases/latest?app=headunit", request.path)
        assertEquals("Token unit-token", request.getHeader("Authorization"))
        assertEquals(263101200L, release.versionCode)

        assertNull(backend.latest().getOrThrow())
    }

    @Test
    fun `the APK download never sends the engine token, and resumes with Range`() = runBlocking {
        val bytes = ByteArray(10) { it.toByte() }
        server.enqueue(MockResponse().setResponseCode(206).setBody(okio.Buffer().write(bytes.copyOfRange(4, 10))))
        val part = tmp.newFile("headunit.apk.part").apply { writeBytes(bytes.copyOfRange(0, 4)) }

        val result = OkHttpApkDownloader(OkHttpClient()).download(server.url("/apk").toString(), part, 10) {}

        assertTrue(result.exceptionOrNull()?.toString() ?: "", result.isSuccess)
        val request = server.takeRequest()
        assertNull(request.getHeader("Authorization"))
        assertEquals("bytes=4-", request.getHeader("Range"))
        assertTrue(bytes.contentEquals(part.readBytes()))
    }
}
