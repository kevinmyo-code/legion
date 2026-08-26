package com.kevin.legion.sync

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Thin Google Drive REST client scoped to the app's own `appDataFolder` - the
 * hidden per-app folder in the DRIVER'S Drive (see [DriveAuth]). All calls are
 * blocking; [SyncEngine] runs them on Dispatchers.IO. Every method fails soft
 * (returns null/false/[UpdateResult.Failure], logs) rather than throwing, so a
 * flaky connection just skips a sync round instead of crashing.
 *
 * Six operations: list the folder, download a file, look up one file by name,
 * create a file, write (create-or-overwrite) a file's contents, and delete a
 * file by id. Files are the gzipped-NDJSON table snapshots [SyncEngine]
 * produces, or (added 2026-08-12) the gzipped whole-database backups
 * [DatabaseSnapshot] produces - this client is deliberately format-agnostic,
 * it just moves bytes.
 *
 * B20 (optimistic concurrency): Drive API v3 dropped v2's `etag` File-resource
 * field in favor of a monotonically increasing `version` counter + revision
 * IDs, and does not document server-side enforcement of an `If-Match`
 * precondition on `files.update` the way some newer Google APIs do. So
 * [DriveFile.version] is treated as a client-side re-check, not a guaranteed
 * server precondition: [update] still sends an opportunistic `If-Match`
 * header (a free win if Drive does honor it, a live 412 either way is
 * surfaced as [UpdateResult.Conflict]), but the real guard is fetching the
 * file's LIVE version immediately before writing and comparing it to the
 * version the caller last saw ([DriveConflict.versionChanged]). Either signal
 * - a fresh version mismatch or an actual 412 - is reported as a conflict so
 * [SyncEngine] can re-download, re-merge, and retry instead of forking the
 * file or clobbering the other device's write. Live two-device Drive
 * validation of the 412 path is still pending (untestable without a real
 * Drive session); the version re-check path is exercised by
 * [DriveConflictTest].
 */
class DriveClient(private val accessToken: String) {

    /**
     * **Observed failing on the A25, 2026-08-26, on the FIRST ever real restore attempt.**
     * `callTimeout` alone was set here, and it does not raise OkHttp's per-operation defaults:
     * connect, read and write each stay at 10 seconds. Downloading a whole-database backup (a few
     * MB gzipped) meant waiting on Drive to start streaming, that wait exceeded the 10 second READ
     * timeout, and the restore died with `SocketTimeoutException` out of `Http2Stream.takeHeaders`
     * while the 60 second call budget still had 50 seconds left.
     *
     * Uploads were unaffected and had been working for weeks, which is why this survived: the
     * backup half is exercised regularly and the restore half had never once been run.
     *
     * The timeouts below are sized for a multi-megabyte whole-database transfer rather than for
     * the small per-table NDJSON files ordinary sync moves. `callTimeout` stays the outer bound on
     * the whole call so a stalled transfer still cannot hang forever.
     */
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    /** A remote file's identity plus Drive's per-file revision counter (B20). */
    data class DriveFile(val id: String, val version: String)

    /** Outcome of [update]/[upsert] (B20). */
    sealed interface UpdateResult {
        data object Ok : UpdateResult
        /** The file changed remotely since the caller last read it - re-merge and retry. */
        data object Conflict : UpdateResult
        data object Failure : UpdateResult
    }

    private fun authed(builder: Request.Builder): Request.Builder =
        builder.header("Authorization", "Bearer $accessToken")

    /** name -> [DriveFile] for every file in appDataFolder (last one wins on duplicate names). */
    fun listAppData(): Map<String, DriveFile> {
        val url = "$FILES?spaces=appDataFolder&fields=files(id,name,version)&pageSize=1000"
        return try {
            client.newCall(authed(Request.Builder().url(url)).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "listAppData failed: ${resp.code}")
                    return emptyMap()
                }
                val files = JSONObject(resp.body?.string().orEmpty()).optJSONArray("files") ?: JSONArray()
                buildMap {
                    for (i in 0 until files.length()) {
                        val f = files.getJSONObject(i)
                        put(f.getString("name"), DriveFile(f.getString("id"), f.optString("version", "0")))
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "listAppData error", t)
            emptyMap()
        }
    }

    /** Raw bytes of a file, or null on error. */
    fun download(fileId: String): ByteArray? =
        try {
            client.newCall(authed(Request.Builder().url("$FILES/$fileId?alt=media")).get().build())
                .execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.bytes()
                    else { Log.w(TAG, "download $fileId failed: ${resp.code}"); null }
                }
        } catch (t: Throwable) {
            Log.w(TAG, "download error", t); null
        }

    /**
     * Live `version` of one file, fetched fresh (not from a cached
     * [listAppData] snapshot) - the B20 re-check right before a
     * conflict-sensitive write. Null on error (fails soft, like every other
     * method here; [update] treats a null live version as "can't confirm, but
     * don't block the write either").
     */
    fun currentVersion(fileId: String): String? =
        try {
            client.newCall(authed(Request.Builder().url("$FILES/$fileId?fields=version")).get().build())
                .execute().use { resp ->
                    if (resp.isSuccessful) JSONObject(resp.body?.string().orEmpty()).optString("version").ifBlank { null }
                    else { Log.w(TAG, "currentVersion $fileId failed: ${resp.code}"); null }
                }
        } catch (t: Throwable) {
            Log.w(TAG, "currentVersion error", t); null
        }

    /**
     * Looks up a single file's id by name, freshly (not from a cached
     * snapshot) - closes the B20 create-fork window: two devices racing to
     * create the same-named file for the first time would otherwise both
     * succeed, leaving `listAppData`'s "last one wins" to silently drop one.
     */
    fun findByName(name: String): String? {
        val escaped = name.replace("\\", "\\\\").replace("'", "\\'")
        val q = URLEncoder.encode("name = '$escaped' and 'appDataFolder' in parents and trashed = false", "UTF-8")
        val url = "$FILES?spaces=appDataFolder&q=$q&fields=files(id)&pageSize=1"
        return try {
            client.newCall(authed(Request.Builder().url(url)).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "findByName $name failed: ${resp.code}")
                    return null
                }
                val files = JSONObject(resp.body?.string().orEmpty()).optJSONArray("files") ?: JSONArray()
                if (files.length() > 0) files.getJSONObject(0).getString("id") else null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "findByName error", t); null
        }
    }

    /** Creates a new file in appDataFolder, returns its fileId or null. */
    fun create(name: String, bytes: ByteArray): String? {
        val metadata = JSONObject()
            .put("name", name)
            .put("parents", JSONArray().put("appDataFolder"))
            .toString()
        val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
            .addPart(metadata.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .addPart(bytes.toRequestBody(GZIP))
            .build()
        return try {
            client.newCall(
                authed(Request.Builder().url("$UPLOAD?uploadType=multipart&fields=id")).post(body).build()
            ).execute().use { resp ->
                if (resp.isSuccessful) JSONObject(resp.body?.string().orEmpty()).optString("id").ifBlank { null }
                else { Log.w(TAG, "create $name failed: ${resp.code}"); null }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "create error", t); null
        }
    }

    /**
     * Overwrites an existing file's contents. [expectedVersion] (from the
     * caller's last [listAppData]/[currentVersion] read), when given, gates
     * the write on a fresh [currentVersion] re-check (B20) - a mismatch is
     * reported as [UpdateResult.Conflict] and the PATCH is never sent. The
     * PATCH itself also carries an opportunistic `If-Match` header for the
     * same version; if Drive DOES enforce it server-side, a 412 is likewise
     * mapped to [UpdateResult.Conflict]. Null [expectedVersion] means "no
     * known prior version" (e.g. the findByName race-recovery path in
     * [upsert]) - the write proceeds unconditionally, same as before B20.
     */
    fun update(fileId: String, bytes: ByteArray, expectedVersion: String? = null): UpdateResult {
        if (expectedVersion != null) {
            val live = currentVersion(fileId)
            if (DriveConflict.versionChanged(expectedVersion, live)) {
                Log.w(TAG, "update $fileId: version changed since last read ($expectedVersion -> $live)")
                return UpdateResult.Conflict
            }
        }
        val builder = authed(Request.Builder().url("$UPLOAD/$fileId?uploadType=media"))
        if (expectedVersion != null) builder.header("If-Match", expectedVersion)
        return try {
            client.newCall(builder.patch(bytes.toRequestBody(GZIP)).build()).execute().use { resp ->
                when {
                    resp.isSuccessful -> UpdateResult.Ok
                    resp.code == DriveConflict.HTTP_PRECONDITION_FAILED -> {
                        Log.w(TAG, "update $fileId conflict (412)")
                        UpdateResult.Conflict
                    }
                    else -> { Log.w(TAG, "update $fileId failed: ${resp.code}"); UpdateResult.Failure }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "update error", t); UpdateResult.Failure
        }
    }

    /**
     * Create-or-overwrite by name, given a prior [listAppData] snapshot. On
     * the create path, re-checks by name first (B20 [findByName]) in case
     * another device created this file since [existing] was captured, so two
     * devices racing to first-create the same table never fork it.
     */
    fun upsert(name: String, bytes: ByteArray, existing: Map<String, DriveFile>): UpdateResult {
        val entry = existing[name]
        if (entry != null) return update(entry.id, bytes, entry.version)
        val raceWinner = findByName(name)
        return if (raceWinner != null) update(raceWinner, bytes, expectedVersion = null)
        else if (create(name, bytes) != null) UpdateResult.Ok
        else UpdateResult.Failure
    }

    /**
     * Deletes a file from appDataFolder by id. True on success, including when the file was
     * already gone (404 - treated as "the end state we wanted" rather than a failure, so a
     * caller pruning old backup generations doesn't retry-loop on a file it already removed).
     * [DatabaseSnapshot]'s generation-pruning is the one caller: it deletes the oldest
     * generation only AFTER a new one has uploaded successfully, never before - see that
     * class's doc comment for why the ordering is load-bearing.
     */
    fun delete(fileId: String): Boolean =
        try {
            client.newCall(authed(Request.Builder().url("$FILES/$fileId")).delete().build())
                .execute().use { resp ->
                    if (resp.isSuccessful || resp.code == 404) true
                    else { Log.w(TAG, "delete $fileId failed: ${resp.code}"); false }
                }
        } catch (t: Throwable) {
            Log.w(TAG, "delete error", t); false
        }

    private companion object {
        const val TAG = "DriveClient"
        const val FILES = "https://www.googleapis.com/drive/v3/files"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
        val GZIP = "application/gzip".toMediaType()
    }
}
