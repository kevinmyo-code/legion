package com.kevin.midnightai.update

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The engine's two release doors. */
interface UpdateBackend {
    /** The newest head unit release, or success(null) when none is published. */
    suspend fun latest(): Result<ReleaseInfo?>
    suspend fun downloadLink(release: ReleaseInfo): Result<DownloadLink>
}

/** Streams a signed URL into [dest], resuming a partial file with a Range request. */
interface ApkDownloader {
    suspend fun download(url: String, dest: File, expectedSize: Long, onProgress: (Long) -> Unit): Result<Unit>
}

/** Commits an APK to Android's PackageInstaller. The outcome arrives later, at the status receiver. */
interface PackageInstallerGateway {
    fun install(apk: File, sizeBytes: Long): Result<Unit>
}

/** What the controller needs to know about this unit right now. */
interface UpdatePlatform {
    val ownPackage: String
    val deviceAbis: List<String>
    fun installed(): InstalledApp
    fun inspect(apk: File): ApkFacts?
    fun online(): Boolean
    fun unmetered(): Boolean
    fun mayInstallPackages(): Boolean
    fun conversationBusy(): Boolean
    fun driving(): Boolean
}

/** The few facts kept between runs. */
interface UpdatePrefs {
    var allowMetered: Boolean
    var pendingInstallAtMs: Long
}

/** Where the updater is, for the Cruise chip and the Settings leaf alike. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object DebugBuild : UpdateState
    data class UpToDate(val checkedAtMs: Long) : UpdateState
    data class Available(val release: ReleaseInfo, val why: String) : UpdateState
    data class Downloading(val release: ReleaseInfo, val bytes: Long) : UpdateState
    /** Verified and waiting for a tap. [note] is why the last tap or held tap did not install. */
    data class Ready(val release: ReleaseInfo, val file: File, val note: String? = null) : UpdateState
    data class Waiting(val release: ReleaseInfo, val file: File) : UpdateState
    data class Installing(val release: ReleaseInfo, val sinceMs: Long) : UpdateState
    data class Failed(val sentence: String) : UpdateState
}

/** The result of an install request, in words. [started] is true only when PackageInstaller took it. */
data class InstallOutcome(val started: Boolean, val waiting: Boolean, val sentence: String)

/**
 * The head unit's one updater (alfred-ota ticket 08, ADR 0072): check, download, verify, offer,
 * install. A port of the phone's `com.kevin.legion.update.UpdateController`, without the voice
 * consent (the head unit's consent is a tap on the Cruise chip or the Settings leaf, both calling
 * this class, ADR 0035) and with the moving-car gate in place of Matrix and the car link. Every
 * dependency arrives through the constructor so the JVM tests use fakes.
 */
@Suppress("TooManyFunctions", "LongParameterList") // one method per step the ticket names
class UpdateController(
    private val backend: UpdateBackend,
    private val downloader: ApkDownloader,
    private val installer: PackageInstallerGateway,
    private val platform: UpdatePlatform,
    private val prefs: UpdatePrefs,
    private val downloadDir: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    var allowMetered: Boolean
        get() = prefs.allowMetered
        set(value) { prefs.allowMetered = value }

    /**
     * Ask the engine for the newest release; when it is newer, download it (if the network gate
     * allows) and verify it. Safe to call from service start, the loop and a button at once: one
     * runs, the others wait and see its result.
     */
    suspend fun check(): UpdateState = lock.withLock {
        val current = _state.value
        if (current is UpdateState.Waiting) return current
        if (current is UpdateState.Installing && now() - current.sinceMs < UpdateRules.INSTALL_STALE_MS) return current
        _state.value = UpdateState.Checking
        val installed = platform.installed()
        val next = backend.latest().fold(
            onSuccess = { release ->
                when (val offer = UpdateRules.compare(installed.versionCode, release)) {
                    UpdateRules.Offer.DebugBuild -> UpdateState.DebugBuild
                    UpdateRules.Offer.UpToDate -> UpdateState.UpToDate(now())
                    is UpdateRules.Offer.Newer -> prepare(offer.release, installed)
                }
            },
            onFailure = { UpdateState.Failed("Could not check for an update: ${it.message ?: "the engine did not answer"}") },
        )
        _state.value = next
        next
    }

    private suspend fun prepare(release: ReleaseInfo, installed: InstalledApp): UpdateState {
        val file = apkFile(release)
        if (file.exists()) {
            verify(release, file, installed)?.let { return it }
            return UpdateState.Ready(release, file)
        }
        UpdateRules.downloadRefusal(platform.online(), platform.unmetered(), prefs.allowMetered)
            ?.let { return UpdateState.Available(release, it) }
        val link = backend.downloadLink(release).getOrElse {
            return UpdateState.Available(release, "Not downloaded: ${it.message ?: "no download link"}")
        }
        if (link.sha256 != release.sha256 || link.versionCode != release.versionCode) {
            return UpdateState.Available(release, "Not downloaded: the download link names a different build.")
        }
        downloadDir.listFiles()?.filter { !it.name.startsWith(file.name) }?.forEach { it.delete() }
        _state.value = UpdateState.Downloading(release, 0L)
        val part = File(downloadDir, file.name + ".part")
        downloader.download(link.url, part, release.sizeBytes) { bytes ->
            _state.value = UpdateState.Downloading(release, bytes)
        }.onFailure {
            return UpdateState.Available(
                release,
                "Not downloaded: the download stopped (${it.message ?: "no reason given"}); it resumes next time.",
            )
        }
        if (!part.renameTo(file)) return UpdateState.Available(release, "Not downloaded: the file could not be saved.")
        verify(release, file, installed)?.let { return it }
        return UpdateState.Ready(release, file)
    }

    /** Null when [file] passes every check; otherwise the file is deleted and the refusal returned. */
    private fun verify(release: ReleaseInfo, file: File, installed: InstalledApp): UpdateState.Failed? {
        val sha = runCatching { sha256(file) }.getOrNull().orEmpty()
        val refusal = UpdateRules.verifyRefusal(
            release, sha, platform.inspect(file), installed, platform.ownPackage, platform.deviceAbis,
        ) ?: return null
        file.delete()
        return UpdateState.Failed(refusal)
    }

    /**
     * Kevin's tap. A moving car or a missing install grant refuse in words; a conversation in
     * progress holds the tap and installs when it ends.
     */
    suspend fun install(): InstallOutcome {
        // After a restart the controller starts Idle while the verified file is still on disk:
        // find it again before deciding.
        val before = _state.value
        if (before !is UpdateState.Ready && before !is UpdateState.Waiting && before !is UpdateState.Installing) check()
        return lock.withLock { installLocked() }
    }

    @Suppress("ReturnCount") // one answer per state, each in words
    private fun installLocked(): InstallOutcome {
        val ready = when (val s = _state.value) {
            is UpdateState.Ready -> s
            is UpdateState.Waiting -> return InstallOutcome(false, true, waitingSentence(s.release))
            is UpdateState.Installing -> return InstallOutcome(true, false, "${UpdateRules.APP} ${s.release.versionName} is already installing.")
            is UpdateState.Available -> return InstallOutcome(false, false, "Not installed: ${s.why.removePrefix("Not downloaded: ")}")
            is UpdateState.UpToDate -> return InstallOutcome(false, false, "Not installed: ${UpdateRules.APP} is already up to date.")
            UpdateState.DebugBuild -> return InstallOutcome(false, false, DEBUG_SENTENCE)
            is UpdateState.Failed -> return InstallOutcome(false, false, s.sentence)
            else -> return InstallOutcome(false, false, "Not installed: no verified update is waiting yet.")
        }
        UpdateRules.installRefusal(platform.driving(), platform.mayInstallPackages())?.let {
            _state.value = ready.copy(note = it)
            return InstallOutcome(false, false, it)
        }
        if (platform.conversationBusy()) {
            prefs.pendingInstallAtMs = now()
            _state.value = UpdateState.Waiting(ready.release, ready.file)
            return InstallOutcome(false, true, waitingSentence(ready.release))
        }
        return commit(ready.release, ready.file)
    }

    private fun waitingSentence(release: ReleaseInfo) =
        "Not installed yet: ${UpdateRules.describe(release)} installs as soon as this conversation ends. " +
            "${UpdateRules.APP} closes and comes back on its own."

    /**
     * The conversation ended. A tap held from it installs now if the car is still parked and the
     * tap is fresh; otherwise it lapses and the chip offers Install again.
     */
    suspend fun onConversationIdle(): InstallOutcome? = lock.withLock {
        val waiting = _state.value as? UpdateState.Waiting ?: return@withLock null
        val heldAt = prefs.pendingInstallAtMs
        prefs.pendingInstallAtMs = 0L
        _state.value = UpdateState.Ready(waiting.release, waiting.file)
        if (heldAt <= 0 || now() - heldAt > UpdateRules.PENDING_INSTALL_MS) return@withLock null
        UpdateRules.installRefusal(platform.driving(), platform.mayInstallPackages())?.let {
            _state.value = UpdateState.Ready(waiting.release, waiting.file, note = it)
            return@withLock InstallOutcome(false, false, it)
        }
        commit(waiting.release, waiting.file)
    }

    private fun commit(release: ReleaseInfo, file: File): InstallOutcome {
        val sha = runCatching { sha256(file) }.getOrNull()
        if (sha == null || !sha.equals(release.sha256, ignoreCase = true)) {
            file.delete()
            val sentence = "Not installed: the downloaded file changed since it was checked, so it was discarded."
            _state.value = UpdateState.Failed(sentence)
            return InstallOutcome(false, false, sentence)
        }
        prefs.pendingInstallAtMs = 0L
        return installer.install(file, file.length()).fold(
            onSuccess = {
                _state.value = UpdateState.Installing(release, now())
                InstallOutcome(
                    true,
                    false,
                    "Installing ${UpdateRules.describe(release)}. ${UpdateRules.APP} closes and comes back " +
                        "on its own; the first launch after it is slower.",
                )
            },
            onFailure = {
                val sentence = "Not installed: Android refused to start the install (${it.message ?: "no reason given"})."
                _state.value = UpdateState.Failed(sentence)
                InstallOutcome(false, false, sentence)
            },
        )
    }

    /** PackageInstaller came back with a failure. A success never reaches here: the process is replaced. */
    fun onInstallFailed(message: String) {
        _state.value = UpdateState.Failed("Not installed: Android said $message")
    }

    /** What the update changes, in the publisher's words, or what is installed when none waits. */
    fun whatsNew(): String {
        val installed = platform.installed()
        val release = when (val s = _state.value) {
            is UpdateState.Ready -> s.release
            is UpdateState.Waiting -> s.release
            is UpdateState.Available -> s.release
            is UpdateState.Downloading -> s.release
            is UpdateState.Installing -> s.release
            else -> null
        } ?: return "No update is waiting. This unit runs ${UpdateRules.APP} ${installed.versionName}."
        val notes = release.notes.trim().ifEmpty { "The release has no notes." }
        return "${UpdateRules.APP} ${release.versionName} (this unit runs ${installed.versionName}): $notes"
    }

    private fun apkFile(release: ReleaseInfo): File {
        downloadDir.mkdirs()
        return File(downloadDir, "headunit-${release.versionCode}.apk")
    }

    companion object {
        val DEBUG_SENTENCE =
            "Not installed: this is a debug build (version code 1), and a debug build never updates itself."

        /** The file's SHA-256, streamed: the APK is far too big for one ByteArray. */
        @Throws(IOException::class)
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
