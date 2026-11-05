package com.kevin.legion.update

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [UpdateController] end to end over fakes (alfred-ota ticket 05): check, the Wi-Fi gate,
 * download, verify, ask gating, the yes, and the install handed to PackageInstaller.
 */
class UpdateControllerTest {

    @get:Rule val tmp = TemporaryFolder()

    private val signer = "a".repeat(64)
    private val apkBytes = "pretend this is an APK".toByteArray()
    private val apkSha = sha(apkBytes)
    private val release = ReleaseInfo("r1", 263091200, "2026-11-05-ab12cd3", apkSha, apkBytes.size.toLong(), "fixes X", signer)

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
        override fun install(apk: File, sizeBytes: Long): Result<Unit> {
            installed += apk
            return Result.success(Unit)
        }
    }

    private inner class FakePlatform : UpdatePlatform {
        var installedApp = InstalledApp(263081200, "2026-11-04-0000000", setOf(signer))
        var apkSigner = signer
        var unmetered = true
        var busy = false
        var matrix = false
        var car = false
        var mayInstall = true
        override val ownPackage = "com.kevin.legion"
        override fun installed() = installedApp
        override fun inspect(apk: File) = ApkFacts(ownPackage, release.versionCode, setOf(apkSigner))
        override fun online() = true
        override fun unmetered() = unmetered
        override fun mayInstallPackages() = mayInstall
        override fun conversationBusy() = busy
        override fun matrixOn() = matrix
        override fun carConnected() = car
    }

    private class FakeNotifier : UpdateNotifier {
        val readied = mutableListOf<Long>()
        val refusals = mutableListOf<String>()
        var spoken = 0
        var speakAnswer = true
        override fun ready(release: ReleaseInfo) { readied += release.versionCode }
        override fun refused(sentence: String) { refusals += sentence }
        override fun clear() = Unit
        override suspend fun speak(release: ReleaseInfo): Boolean { spoken++; return speakAnswer }
    }

    private class MemoryPrefs : UpdatePrefs {
        override var allowMobileData = false
        override var notifiedCode = 0L
        override var announcedCode = 0L
        override var offeredAtMs = 0L
        override var pendingInstallAtMs = 0L
    }

    private var clock = 1_000_000L
    private val backend = FakeBackend(release, DownloadLink("https://storage.googleapis.com/x", apkSha, apkBytes.size.toLong(), release.versionCode))
    private val downloader = FakeDownloader(apkBytes)
    private val installer = FakeInstaller()
    private val platform = FakePlatform()
    private val notifier = FakeNotifier()
    private val prefs = MemoryPrefs()
    private val controller by lazy {
        UpdateController(backend, downloader, installer, platform, notifier, prefs, tmp.newFolder("updates"), now = { clock })
    }

    @Test
    fun `a newer release downloads, verifies and asks once`() = runBlocking {
        val state = controller.check()
        assertTrue(state.toString(), state is UpdateState.Ready)
        assertEquals(listOf(release.versionCode), notifier.readied)
        controller.check()
        assertEquals("the notification is posted once per release", 1, notifier.readied.size)
        assertEquals("the file is kept, not downloaded twice", 1, downloader.calls)
    }

    @Test
    fun `up to date, no release, a debug build and an engine failure each say so`() = runBlocking {
        backend.release = release.copy(versionCode = 263081200)
        assertTrue(controller.check() is UpdateState.UpToDate)
        backend.release = null
        assertTrue(controller.check() is UpdateState.UpToDate)
        platform.installedApp = platform.installedApp.copy(versionCode = 1)
        backend.release = release
        assertEquals(UpdateState.DebugBuild, controller.check())
        assertTrue(controller.install(Consent.TAP).sentence.contains("debug build"))
        backend.fail = IllegalStateException("HTTP 503")
        val failed = controller.check()
        assertTrue(failed.toString(), failed is UpdateState.Failed && failed.sentence.contains("Could not check"))
        assertEquals(0, downloader.calls)
    }

    @Test
    fun `mobile data does not download unless allowed`() = runBlocking {
        platform.unmetered = false
        val state = controller.check()
        assertTrue(state is UpdateState.Available && state.why.contains("Wi-Fi only"))
        assertEquals(0, downloader.calls)
        controller.allowMobileData = true
        assertTrue(controller.check() is UpdateState.Ready)
    }

    @Test
    fun `a sha256 mismatch refuses, discards the file and says so`() = runBlocking {
        downloader.bytes = "tampered".toByteArray()
        val state = controller.check()
        assertTrue(state is UpdateState.Failed && state.sentence.contains("checksum"))
        assertEquals(1, notifier.refusals.size)
        assertTrue(installer.installed.isEmpty())
        assertFalse(File(tmp.root, "updates/phone-${release.versionCode}.apk").exists())
    }

    @Test
    fun `a signer mismatch refuses and never installs`() = runBlocking {
        platform.apkSigner = "f".repeat(64)
        val state = controller.check()
        assertTrue(state is UpdateState.Failed && state.sentence.contains("same key"))
        assertFalse(controller.install(Consent.TAP).started)
        assertTrue(installer.installed.isEmpty())
    }

    @Test
    fun `a tap installs when nothing is in the way`() = runBlocking {
        controller.check()
        val outcome = controller.install(Consent.TAP)
        assertTrue(outcome.sentence, outcome.started)
        assertEquals(1, installer.installed.size)
        assertTrue(controller.state.value is UpdateState.Installing)
    }

    @Test
    fun `Matrix, the car and a missing grant refuse the install in words`() = runBlocking {
        controller.check()
        platform.matrix = true
        assertTrue(controller.install(Consent.TAP).sentence.contains("Matrix"))
        platform.matrix = false
        platform.car = true
        assertTrue(controller.install(Consent.TAP).sentence.contains("car"))
        platform.car = false
        platform.mayInstall = false
        assertTrue(controller.install(Consent.TAP).sentence.contains("not allowed to install"))
        assertTrue(installer.installed.isEmpty())
    }

    @Test
    fun `a voice yes needs the offer first, every time`() = runBlocking {
        controller.check()
        val first = controller.install(Consent.VOICE)
        assertFalse(first.started)
        assertTrue(first.sentence, first.sentence.contains("Ask: install"))
        // The offer has gone stale: a yes now is a yes to something else.
        clock += UpdateRules.OFFER_WINDOW_MS + 1
        assertFalse(controller.install(Consent.VOICE).started)
        assertTrue(installer.installed.isEmpty())
        // That refusal re-asked; a yes right after it installs.
        clock += 30_000
        assertTrue(controller.install(Consent.VOICE).started)
        assertEquals(1, installer.installed.size)
    }

    @Test
    fun `a yes mid-conversation waits and installs when the conversation ends`() = runBlocking {
        controller.check()
        controller.offered()
        platform.busy = true
        val outcome = controller.install(Consent.VOICE)
        assertTrue(outcome.waiting)
        assertFalse(outcome.started)
        assertTrue(installer.installed.isEmpty())
        assertTrue(controller.state.value is UpdateState.Waiting)

        platform.busy = false
        controller.onConversationIdle()
        assertEquals(1, installer.installed.size)
    }

    @Test
    fun `a held yes lapses, and Matrix after the conversation refuses it`() = runBlocking {
        controller.check()
        controller.offered()
        platform.busy = true
        controller.install(Consent.VOICE)
        platform.busy = false
        platform.matrix = true
        controller.onConversationIdle()
        assertTrue(installer.installed.isEmpty())
        assertTrue(notifier.refusals.single().contains("Matrix"))

        platform.matrix = false
        controller.offered()
        platform.busy = true
        controller.install(Consent.VOICE)
        platform.busy = false
        clock += UpdateRules.PENDING_INSTALL_MS + 1
        controller.onConversationIdle()
        assertTrue(installer.installed.isEmpty())
        assertTrue(controller.state.value is UpdateState.Ready)
    }

    @Test
    fun `the spoken ask never comes mid-conversation, in Matrix or in the car, and comes once`() = runBlocking {
        controller.check()
        platform.busy = true
        assertFalse(controller.maybeAnnounce())
        platform.busy = false
        platform.matrix = true
        assertFalse(controller.maybeAnnounce())
        platform.matrix = false
        platform.car = true
        assertFalse(controller.maybeAnnounce())
        platform.car = false
        assertEquals(0, notifier.spoken)
        assertTrue(controller.maybeAnnounce())
        assertFalse(controller.maybeAnnounce())
        assertEquals(1, notifier.spoken)
        assertTrue("the spoken ask opens the offer for the voice yes", controller.install(Consent.VOICE).started)
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
