package com.kevin.legion.update

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.kevin.legion.MidnightApplication
import com.kevin.legion.backend.engine.EngineFailure
import com.kevin.legion.backend.engine.EngineHttp
import com.kevin.legion.backend.engine.EngineHttpException
import com.kevin.legion.service.ConversationState
import com.kevin.legion.vehicle.ObdBluetoothManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** The engine's release doors over [EngineHttp] (`server/releases/views.py`). */
class DjangoUpdateBackend(private val http: EngineHttp) : UpdateBackend {

    override suspend fun latest(): Result<ReleaseInfo?> {
        if (!http.isUsable()) return Result.failure(IOException("this phone is not signed in to an engine"))
        return http.get("/api/releases/latest", mapOf("app" to "phone")).fold(
            onSuccess = { ok ->
                ReleaseJson.parseRelease(ok.body)?.let { Result.success(it) }
                    ?: Result.failure(IOException("the engine's release answer was not readable"))
            },
            onFailure = { e ->
                val failure = (e as? EngineHttpException)?.failure
                if (failure is EngineFailure.Refused && failure.status == NOT_FOUND) {
                    Result.success(null)
                } else {
                    Result.failure(e)
                }
            },
        )
    }

    override suspend fun downloadLink(release: ReleaseInfo): Result<DownloadLink> =
        http.get("/api/releases/${release.id}/download").mapCatching { ok ->
            ReleaseJson.parseDownload(ok.body) ?: throw IOException("the engine's download link was not readable")
        }

    private companion object {
        const val NOT_FOUND = 404
    }
}

/**
 * Streams the signed Cloud Storage URL to a file, resuming with `Range`. **Never the engine's
 * client:** the URL is Google's, so no `Authorization: Token` may go with it, and a 200 MB APK
 * must not be read into one ByteArray.
 */
class OkHttpApkDownloader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
) : ApkDownloader {

    override suspend fun download(
        url: String,
        dest: File,
        expectedSize: Long,
        onProgress: (Long) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            dest.parentFile?.mkdirs()
            if (dest.exists() && dest.length() > expectedSize) dest.delete()
            val start = if (dest.exists()) dest.length() else 0L
            if (start < expectedSize) fetch(url, dest, start, onProgress)
            if (dest.length() != expectedSize) {
                throw IOException("got ${dest.length()} of $expectedSize bytes")
            }
        }
    }

    private suspend fun fetch(url: String, dest: File, start: Long, onProgress: (Long) -> Unit) {
        val request = Request.Builder().url(url)
            .apply { if (start > 0) header("Range", "bytes=$start-") }
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("the download answered HTTP ${response.code}")
            val append = start > 0 && response.code == PARTIAL
            var written = if (append) start else 0L
            val body = response.body ?: throw IOException("the download was empty")
            FileOutputStream(dest, append).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER)
                    var lastReport = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        written += n
                        if (written - lastReport >= REPORT_EVERY) {
                            onProgress(written)
                            lastReport = written
                        }
                    }
                }
            }
            onProgress(written)
        }
    }

    private companion object {
        const val PARTIAL = 206
        const val BUFFER = 1 shl 16
        const val REPORT_EVERY = 4L shl 20
    }
}

/** The phone's facts, read live. */
class AndroidUpdatePlatform(private val app: Application) : UpdatePlatform {

    override val ownPackage: String get() = app.packageName

    override fun installed(): InstalledApp {
        val pm = app.packageManager
        val info = runCatching { packageInfo(pm, app.packageName) }.getOrNull()
        val code = info?.let { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode else legacyCode(it) } ?: 0L
        return InstalledApp(code, info?.versionName.orEmpty(), info?.let(::signers).orEmpty())
    }

    override fun inspect(apk: File): ApkFacts? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val info = app.packageManager.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return null
        return ApkFacts(info.packageName, info.longVersionCode, signers(info))
    }

    override fun online(): Boolean = caps()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    override fun unmetered(): Boolean = caps()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true

    override fun mayInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || app.packageManager.canRequestPackageInstalls()

    override fun conversationBusy(): Boolean = ConversationState.isBusy

    override fun matrixOn(): Boolean = (app as? MidnightApplication)?.dumb?.controller?.state?.value?.on == true

    override fun carConnected(): Boolean = ObdBluetoothManager.isConnected

    private fun caps(): NetworkCapabilities? {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.getNetworkCapabilities(cm.activeNetwork ?: return null)
    }

    private fun packageInfo(pm: PackageManager, name: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageInfo(name, 0)
        }

    @Suppress("DEPRECATION")
    private fun legacyCode(info: PackageInfo): Long = info.versionCode.toLong()

    /** The SHA-256 of each current signing certificate. Below Android 9 none, so nothing verifies. */
    private fun signers(info: PackageInfo): Set<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptySet()
        val signing = info.signingInfo ?: return emptySet()
        val certs: Array<Signature> = signing.apkContentsSigners ?: return emptySet()
        return certs.map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }
}

/**
 * A `PackageInstaller` session for one APK. `USER_ACTION_NOT_REQUIRED` on Android 12+, so once
 * LEGION is the installer of record no Android dialog appears and Kevin's yes is the consent. The
 * first update after an adb install still gets Android's own confirm
 * ([UpdateInstallReceiver] shows it).
 */
class AndroidPackageInstallerGateway(private val app: Application) : PackageInstallerGateway {

    override fun install(apk: File, sizeBytes: Long): Result<Unit> = runCatching {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(sizeBytes)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite(SESSION_FILE, 0, sizeBytes).use { out ->
                    apk.inputStream().use { it.copyTo(out, BUFFER) }
                    session.fsync(out)
                }
                session.commit(statusIntent(id).intentSender)
            }
        } catch (e: IOException) {
            runCatching { installer.abandonSession(id) }
            throw e
        } catch (e: SecurityException) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }

    private fun statusIntent(sessionId: Int): PendingIntent {
        val intent = Intent(app, UpdateInstallReceiver::class.java).setAction(UpdateInstallReceiver.ACTION_STATUS)
        // Mutable: PackageInstaller writes the status extras into it.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(app, sessionId, intent, flags)
    }

    internal companion object {
        const val SESSION_FILE = "legion.apk"
        const val BUFFER = 1 shl 16
    }
}

/** [UpdatePrefs] on SharedPreferences. */
class SharedUpdatePrefs(private val prefs: SharedPreferences) : UpdatePrefs {
    constructor(context: Context) : this(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))

    override var allowMobileData: Boolean
        get() = prefs.getBoolean(KEY_MOBILE, false)
        set(value) { prefs.edit().putBoolean(KEY_MOBILE, value).apply() }
    override var notifiedCode: Long
        get() = prefs.getLong(KEY_NOTIFIED, 0L)
        set(value) { prefs.edit().putLong(KEY_NOTIFIED, value).apply() }
    override var announcedCode: Long
        get() = prefs.getLong(KEY_ANNOUNCED, 0L)
        set(value) { prefs.edit().putLong(KEY_ANNOUNCED, value).apply() }
    override var offeredAtMs: Long
        get() = prefs.getLong(KEY_OFFERED, 0L)
        set(value) { prefs.edit().putLong(KEY_OFFERED, value).apply() }
    override var pendingInstallAtMs: Long
        get() = prefs.getLong(KEY_PENDING, 0L)
        set(value) { prefs.edit().putLong(KEY_PENDING, value).apply() }

    private companion object {
        const val FILE = "legion_updates"
        const val KEY_MOBILE = "allow_mobile_data"
        const val KEY_NOTIFIED = "notified_code"
        const val KEY_ANNOUNCED = "announced_code"
        const val KEY_OFFERED = "offered_at_ms"
        const val KEY_PENDING = "pending_install_at_ms"
    }
}
