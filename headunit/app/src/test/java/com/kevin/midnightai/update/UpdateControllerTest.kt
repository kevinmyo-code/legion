package com.kevin.midnightai.update

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [UpdateController] end to end over fakes (alfred-ota ticket 08), mirroring the phone's
 * `UpdateControllerTest`: check, the network gate, download, verify, the tap, the moving-car gate,
 * and the install handed to PackageInstaller.
 */
class UpdateControllerTest {

    @get:Rule val tmp = TemporaryFolder()

    private val signer = "a".repeat(64)
    private val apkBytes = "pretend this is an APK".toByteArray()
    private val apkSha = sha(apkBytes)
    private val release = ReleaseInfo("r1", 263101200, "1.0+ab12cd3", apkSha, apkBytes.size.toLong(), "fixes X", signer)

    private class FakeBackend(var release: ReleaseInfo?, var link: DownloadLink?) : UpdateBackend {
        var fail: Throwable? = null
        override suspend fun latest(): Result<ReleaseInfo?> = fail?.let { Result.failure(it) } ?: Result.success(release)
        override suspend fun downloadLink(release: ReleaseInfo): Result<DownloadLink> =
            link?.let { Result.success(it) } ?: Result.failure(IllegalStateException("no link"))
    }

    private inner class FakeDownloader(var bytes: ByteArray) : ApkDownloader {
        var calls = 0
        override suspend fun download(url: String, dest: File, expectedSize: Long, onProgress: (Long) -> Unit): Result<Unit> {
            calls++
            dest.writeBytes(bytes)
            onProgress(bytes.size.toLong())
            return Result.success(Unit)
        }
    }

    private class FakeInstaller : PackageInstallerGateway {
        val installed = mutableListOf<File>()
        var fail: Throwable? = null
        override fun install(apk: File, sizeBytes: Long): Result<Unit> {
            fail?.let { return Result.failure(it) }
            installed += apk
            return Result.success(Unit)
        }
    }

    private inner class FakePlatform : UpdatePlatform {
        var installedApp = InstalledApp(263091200, "1.0+0000000", setOf(signer))
        var apkSigner = signer
        var apkAbis = setOf("arm64-v8a")
        var unmetered = true
        var busy = false
        var driving = false
        var mayInstall = true
        override val ownPackage = "com.kevin.midnightai"
        override val deviceAbis = listOf("arm64-v8a", "armeabi-v7a")
        override fun installed() = installedApp
        override fun inspect(apk: File) = ApkFacts(ownPackage, release.versionCode, setOf(apkSigner), apkAbis)
        override fun online() = true
        override fun unmetered() = unmetered
        override fun mayInstallPackages() = mayInstall
        override fun conversationBusy() = busy
        override fun driving() = driving
    }

    private class MemoryPrefs : UpdatePrefs {
        override var allowMetered = true
        override var pendingInstallAtMs = 0L
    }

    private var clock = 1_000_000L
    private val backend = FakeBackend(release, DownloadLink("https://storage.googleapis.com/x", apkSha, apkBytes.size.toLong(), release.versionCode))
    private val downloader = FakeDownloader(apkBytes)
    private val installer = FakeInstaller()
    private val platform = FakePlatform()
    private val prefs = MemoryPrefs()
    private val controller by lazy {
        UpdateController(backend, downloader, installer, platform, prefs, tmp.newFolder("updates"), now = { clock })
    }

    @Test
    fun `a newer release downloads and verifies once`() = runBlocking {
        val state = controller.check()
        assertTrue(state.toString(), state is UpdateState.Ready)
        assertNull((state as UpdateState.Ready).note)
        controller.check()
        assertEquals("the file is kept, not downloaded twice", 1, downloader.calls)
    }

    @Test
    fun `up to date, no release, a debug build and an engine failure each say so`() = runBlocking {
        backend.release = release.copy(versionCode = 263091200)
        assertTrue(controller.check() is UpdateState.UpToDate)
        backend.release = null
        assertTrue(controller.check() is UpdateState.UpToDate)
        platform.installedApp = platform.installedApp.copy(versionCode = 1)
        backend.release = release
        assertEquals(UpdateState.DebugBuild, controller.check())
        assertTrue(controller.install().sentence.contains("debug build"))
        backend.fail = IllegalStateException("HTTP 503")
        val failed = controller.check()
        assertTrue(failed.toString(), failed is UpdateState.Failed && failed.sentence.contains("Could not check"))
        assertEquals(0, downloader.calls)
    }

    @Test
    fun `the hotspot downloads by default, and Wi-Fi only holds it`() = runBlocking {
        platform.unmetered = false
        assertTrue("metered is allowed by default on the head unit", controller.check() is UpdateState.Ready)
        assertEquals(1, downloader.calls)

        File(tmp.root, "updates/headunit-${release.versionCode}.apk").delete()
        controller.allowMetered = false
        val state = controller.check()
        assertTrue(state.toString(), state is UpdateState.Available && state.why.contains("Wi-Fi only"))
        assertEquals(1, downloader.calls)
    }

    @Test
    fun `a sha256 mismatch refuses, discards the file and says so`() = runBlocking {
        downloader.bytes = "tampered".toByteArray()
        val state = controller.check()
        assertTrue(state is UpdateState.Failed && state.sentence.contains("checksum"))
        assertTrue(installer.installed.isEmpty())
        assertFalse(File(tmp.root, "updates/headunit-${release.versionCode}.apk").exists())
    }

    @Test
    fun `a signer mismatch refuses and never installs`() = runBlocking {
        platform.apkSigner = "f".repeat(64)
        val state = controller.check()
        assertTrue(state is UpdateState.Failed && state.sentence.contains("same key"))
        assertFalse(controller.install().started)
        assertTrue(installer.installed.isEmpty())
    }

    @Test
    fun `a release for another ABI refuses before Android does`() = runBlocking {
        platform.apkAbis = setOf("x86_64")
        val state = controller.check()
        assertTrue(state.toString(), state is UpdateState.Failed && state.sentence.contains("built for x86_64"))
        assertTrue(installer.installed.isEmpty())
    }

    @Test
    fun `a tap installs when nothing is in the way`() = runBlocking {
        controller.check()
        val outcome = controller.install()
        assertTrue(outcome.sentence, outcome.started)
        assertEquals(1, installer.installed.size)
        assertTrue(controller.state.value is UpdateState.Installing)
    }

    @Test
    fun `a moving car and a missing grant refuse the tap in words and keep the update ready`() = runBlocking {
        controller.check()
        platform.driving = true
        val moving = controller.install()
        assertFalse(moving.started)
        assertTrue(moving.sentence.contains("car is moving"))
        val ready = controller.state.value as UpdateState.Ready
        assertEquals("the chip shows why", moving.sentence, ready.note)
        platform.driving = false
        platform.mayInstall = false
        assertTrue(controller.install().sentence.contains("not allowed to install"))
        assertTrue(installer.installed.isEmpty())
        platform.mayInstall = true
        assertTrue(controller.install().started)
    }

    @Test
    fun `a tap mid-conversation waits and installs when the conversation ends`() = runBlocking {
        controller.check()
        platform.busy = true
        val outcome = controller.install()
        assertTrue(outcome.waiting)
        assertFalse(outcome.started)
        assertTrue(installer.installed.isEmpty())
        assertTrue(controller.state.value is UpdateState.Waiting)

        platform.busy = false
        val after = controller.onConversationIdle()!!
        assertTrue(after.sentence, after.started)
        assertEquals(1, installer.installed.size)
    }

    @Test
    fun `a held tap lapses, and driving off after the conversation refuses it`() = runBlocking {
        controller.check()
        platform.busy = true
        controller.install()
        platform.busy = false
        platform.driving = true
        val refused = controller.onConversationIdle()!!
        assertTrue(refused.sentence.contains("car is moving"))
        assertTrue(installer.installed.isEmpty())

        platform.driving = false
        platform.busy = true
        controller.install()
        platform.busy = false
        clock += UpdateRules.PENDING_INSTALL_MS + 1
        assertNull(controller.onConversationIdle())
        assertTrue(installer.installed.isEmpty())
        assertTrue(controller.state.value is UpdateState.Ready)
    }

    @Test
    fun `Android refusing the session says so, and a stale install stops blocking a check`() = runBlocking {
        controller.check()
        installer.fail = SecurityException("no grant")
        val outcome = controller.install()
        assertFalse(outcome.started)
        assertTrue(outcome.sentence.contains("Android refused"))

        installer.fail = null
        assertTrue(controller.check() is UpdateState.Ready)
        assertTrue(controller.install().started)
        assertTrue("an install in flight is left alone", controller.check() is UpdateState.Installing)
        clock += UpdateRules.INSTALL_STALE_MS + 1
        assertTrue("a confirm left open does not block forever", controller.check() is UpdateState.Ready)
        controller.onInstallFailed("INSTALL_FAILED_ABORTED")
        assertTrue((controller.state.value as UpdateState.Failed).sentence.contains("INSTALL_FAILED_ABORTED"))
    }

    @Test
    fun `whats new reads the release notes`() = runBlocking {
        assertTrue(controller.whatsNew().startsWith("No update is waiting"))
        controller.check()
        assertTrue(controller.whatsNew().contains("fixes X"))
    }

    private fun sha(bytes: ByteArray): String {
        val f = File.createTempFile("sha", ".bin").apply { writeBytes(bytes); deleteOnExit() }
        return UpdateController.sha256(f)
    }
}
