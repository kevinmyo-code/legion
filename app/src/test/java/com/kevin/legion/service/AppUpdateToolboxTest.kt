package com.kevin.legion.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kevin.legion.ai.AlfredPackData
import com.kevin.legion.ai.AlfredPackSource
import com.kevin.legion.ai.LoadedPack
import com.kevin.legion.ui.settings.UpdatesViewModel
import com.kevin.legion.update.ApkDownloader
import com.kevin.legion.update.ApkFacts
import com.kevin.legion.update.DownloadLink
import com.kevin.legion.update.InstalledApp
import com.kevin.legion.update.PackageInstallerGateway
import com.kevin.legion.update.ReleaseInfo
import com.kevin.legion.update.UpdateBackend
import com.kevin.legion.update.UpdateController
import com.kevin.legion.update.UpdateNotifier
import com.kevin.legion.update.UpdatePlatform
import com.kevin.legion.update.UpdatePrefs
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The `app_update` voice tool's refusals (alfred-ota ticket 06): nothing installs without
 * confirmed=true, nothing installs on a yes that follows no offer, Matrix and the car refuse in
 * words, a mid-conversation yes waits, and `success` is true only when the install was handed to
 * PackageInstaller. Robolectric for `org.json`, which is stubbed in a plain JVM test.
 */
@RunWith(RobolectricTestRunner::class)
class AppUpdateToolboxTest {

    @get:Rule val tmp = TemporaryFolder()

    private val bytes = "an apk".toByteArray()
    private val signer = "a".repeat(64)
    private var clock = 1_000_000L
    private var busy = false
    private var matrix = false
    private var car = false
    private val installs = mutableListOf<File>()
    private var latest: ReleaseInfo? = null

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun controller(): UpdateController {
        val sha = UpdateController.sha256(tmp.newFile().apply { writeBytes(bytes) })
        latest = ReleaseInfo("r1", 263091200, "2026-11-05-ab12cd3", sha, bytes.size.toLong(), "fixes the double announce", signer)
        return UpdateController(
            backend = object : UpdateBackend {
                override suspend fun latest() = Result.success(latest)
                override suspend fun downloadLink(release: ReleaseInfo) =
                    Result.success(DownloadLink("https://storage.googleapis.com/x", release.sha256, release.sizeBytes, release.versionCode))
            },
            downloader = object : ApkDownloader {
                override suspend fun download(url: String, dest: File, expectedSize: Long, onProgress: (Long) -> Unit): Result<Unit> {
                    dest.writeBytes(bytes)
                    return Result.success(Unit)
                }
            },
            installer = object : PackageInstallerGateway {
                override fun install(apk: File, sizeBytes: Long): Result<Unit> {
                    installs += apk
                    return Result.success(Unit)
                }
            },
            platform = object : UpdatePlatform {
                override val ownPackage = "com.kevin.legion"
                override fun installed() = InstalledApp(263081200, "2026-11-04-0000000", setOf(signer))
                override fun inspect(apk: File) = ApkFacts(ownPackage, 263091200, setOf(signer))
                override fun online() = true
                override fun unmetered() = true
                override fun mayInstallPackages() = true
                override fun conversationBusy() = busy
                override fun matrixOn() = matrix
                override fun carConnected() = car
            },
            notifier = object : UpdateNotifier {
                override fun ready(release: ReleaseInfo) = Unit
                override fun refused(sentence: String) = Unit
                override fun clear() = Unit
                override suspend fun speak(release: ReleaseInfo) = true
            },
            prefs = object : UpdatePrefs {
                override var allowMobileData = false
                override var notifiedCode = 0L
                override var announcedCode = 0L
                override var offeredAtMs = 0L
                override var pendingInstallAtMs = 0L
            },
            downloadDir = tmp.newFolder(),
            now = { clock },
        )
    }

    private fun call(args: String): JSONObject = runBlocking {
        AppUpdateToolbox.dispatch(context, "app_update", JSONObject(args))!!
    }

    @After fun reset() {
        AppUpdateToolbox.controllerFactory = { com.kevin.legion.update.UpdateGraph.controller(it) }
    }

    @Test
    fun `other tools fall through`() = runBlocking {
        assertEquals(null, AppUpdateToolbox.dispatch(context, "books", JSONObject()))
    }

    @Test
    fun `check says an update is ready and installs nothing`() {
        val c = controller()
        AppUpdateToolbox.controllerFactory = { c }
        val result = call("""{"action":"check"}""")
        assertTrue(result.getBoolean("success"))
        assertTrue(result.getString("message"), result.getString("message").contains("Nothing is installed yet"))
        assertTrue(installs.isEmpty())
        assertTrue(call("""{"action":"whats_new"}""").getString("message").contains("fixes the double announce"))
    }

    @Test
    fun `install without confirmed installs nothing and says to ask`() {
        val c = controller()
        AppUpdateToolbox.controllerFactory = { c }
        call("""{"action":"check"}""")
        val result = call("""{"action":"install"}""")
        assertFalse(result.getBoolean("success"))
        assertTrue(result.getString("message").startsWith("Nothing was installed"))
        assertTrue(installs.isEmpty())
    }

    @Test
    fun `a confirmed install with no offer before it, or in the same breath, refuses`() {
        val c = controller()
        AppUpdateToolbox.controllerFactory = { c }
        runBlocking { c.check() }
        val cold = call("""{"action":"install","confirmed":true}""")
        assertFalse(cold.getBoolean("success"))
        assertTrue(cold.getString("message"), cold.getString("message").contains("has not been asked"))
        // Asked and "confirmed" in one breath, with no answer between: refused.
        call("""{"action":"install"}""")
        assertFalse(call("""{"action":"install","confirmed":true}""").getBoolean("success"))
        assertTrue(installs.isEmpty())
    }

    @Test
    fun `a yes after the ask installs, and success means PackageInstaller took it`() {
        val c = controller()
        AppUpdateToolbox.controllerFactory = { c }
        call("""{"action":"check"}""")
        clock += 6_000
        val result = call("""{"action":"install","confirmed":true}""")
        assertTrue(result.getString("message"), result.getBoolean("success"))
        assertTrue(result.getString("message").startsWith("Installing"))
        assertEquals(1, installs.size)
    }

    @Test
    fun `Matrix and the car refuse in words, and a conversation holds the yes`() {
        val c = controller()
        AppUpdateToolbox.controllerFactory = { c }
        call("""{"action":"check"}""")
        clock += 6_000
        matrix = true
        assertTrue(call("""{"action":"install","confirmed":true}""").getString("message").contains("Matrix mode is on"))
        matrix = false
        car = true
        assertTrue(call("""{"action":"install","confirmed":true}""").getString("message").contains("car is connected"))
        car = false
        busy = true
        val held = call("""{"action":"install","confirmed":true}""")
        assertFalse(held.getBoolean("success"))
        assertTrue(held.getBoolean("waiting_for_conversation_end"))
        assertTrue(held.getString("message").contains("as soon as this conversation ends"))
        assertTrue(installs.isEmpty())
    }

    @Test
    fun `an unknown action does nothing and says so`() {
        AppUpdateToolbox.controllerFactory = { controller() }
        val result = call("""{"action":"downgrade"}""")
        assertFalse(result.getBoolean("success"))
        assertTrue(result.getString("message").endsWith("Nothing was done."))
    }

    @Test
    fun `the screen names the pack in use`() {
        val pack = AlfredPackData(3, "x", emptyMap(), emptyMap(), emptyMap(), emptyList())
        assertEquals("Alfred's wording: pack v3 from engine.", UpdatesViewModel.packLine(LoadedPack(pack, AlfredPackSource.REMOTE)))
        assertEquals("Alfred's wording: pack v3 from built-in.", UpdatesViewModel.packLine(LoadedPack(pack, AlfredPackSource.BUNDLED)))
    }
}
