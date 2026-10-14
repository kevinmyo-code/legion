package com.kevin.midnightai.ai

import android.content.Context
import com.kevin.midnightai.vehicle.ActiveVehicle
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

/**
 * Loads and caches the companion's cached art off disk. All GENERATION was
 * removed 2026-09-27 (Kevin: "we dont need the art generation on the code or
 * unit anymore. lets generate it ourselves here and seed it because its a
 * personal app now") - the avatar and wallpaper are now bundled, hand-picked
 * assets (see [SeedArt]), generated once outside the app with
 * `art/seed/generate.py` and shipped in `res/drawable-nodpi`. [loadStates] and
 * [loadBackground] both check [SeedArt] first, so nothing in this file makes
 * a network call any more.
 *
 * What's left is what was never generation in the first place: the driver's
 * own uploaded car photo, real photos in the logbook album, and build-entry
 * polaroids. Those are genuine user data (a picked or camera-captured photo),
 * not model output, and none of it is per-car - every car shows the same
 * bundled companion and wallpaper now.
 */
object AvatarStudio {
    const val STATE_IDLE = "idle"
    const val STATE_LISTENING = "listening"
    const val STATE_THINKING = "thinking"
    const val STATE_SPEAKING = "speaking"

    private val ALL_STATES = listOf(STATE_IDLE, STATE_LISTENING, STATE_THINKING, STATE_SPEAKING)

    /** Deletes all cached avatar faces for [vehicleId] (used by the reset flows; legacy cleanup only - nothing generates these any more). */
    fun deleteAvatars(context: Context, vehicleId: String) {
        avatarDir(context, vehicleId).deleteRecursively()
    }

    /**
     * A small idle face for [vehicleId], downsampled to roughly [targetPx] on its
     * short edge. Every car shows the same bundled [SeedArt] avatar now, so
     * [vehicleId] is unused - kept as a parameter so the CARS roster (which
     * renders one row per car) doesn't need a special case.
     */
    @Suppress("UNUSED_PARAMETER")
    fun avatarThumbnail(context: Context, vehicleId: String, targetPx: Int): Bitmap? =
        SeedArt.avatar(context)

    /**
     * Uncle Moose in every state (one drawing, no per-state expressions).
     * Falls back to any legacy per-car generated states still on disk from
     * before the 2026-09-27 seed-art switch, so an existing install doesn't
     * lose its face the moment this shipped.
     */
    fun loadStates(context: Context, vehicleId: String): Map<String, Bitmap>? {
        SeedArt.avatar(context)?.let { moose -> return ALL_STATES.associateWith { moose } }
        val map = LinkedHashMap<String, Bitmap>()
        for (state in ALL_STATES) {
            val f = stateFile(context, vehicleId, state)
            if (f.exists()) BitmapFactory.decodeFile(f.absolutePath)?.let { map[state] = it }
        }
        return map.ifEmpty { null }
    }

    // --- Car photo / portrait (for the logbook polaroid) ----------------

    /** Saves the driver's original car photo (downscaled) as the polaroid source. */
    fun saveCarPhoto(context: Context, source: Bitmap) {
        saveBitmap(carPhotoFile(context), source.scaledTo(1280))
    }

    /**
     * Returns the best image of the car for the logbook polaroid: the driver's real
     * photo if they uploaded one, else a legacy generated portrait from before
     * 2026-09-27 if one is still cached on disk, else null.
     */
    fun carImage(context: Context): Bitmap? =
        loadBitmapOrNull(carPhotoFile(context)) ?: loadBitmapOrNull(carPortraitFile(context))

    fun deleteCarImages(context: Context) {
        carPhotoFile(context).delete()
        carPortraitFile(context).delete()
    }

    // --- Photo album (logbook ALBUM tab): real photos, not generated -----

    /** Photos added directly to the album. */
    private fun albumDir(context: Context): File = File(context.filesDir, "album")

    /** Saves [source] into the album (downscaled), returning its absolute path or null. */
    fun saveAlbumPhoto(context: Context, source: Bitmap): String? = runCatching {
        val file = File(albumDir(context), "${System.currentTimeMillis()}.png")
        saveBitmap(file, source.scaledTo(1280))
        file.absolutePath
    }.getOrNull()

    /** All album-owned photos, newest first. */
    fun listAlbumPhotos(context: Context): List<File> =
        albumDir(context).listFiles { f -> f.extension == "png" }
            ?.sortedByDescending { it.name } ?: emptyList()

    /** Deletes an album-owned photo by absolute path (no-op for other files). */
    fun deleteAlbumPhoto(context: Context, path: String) {
        val f = File(path)
        if (f.parentFile == albumDir(context)) f.delete()
    }

    // --- Logbook entry photos (polaroids on build entries) --------------

    /**
     * Saves a picked image (downscaled) as the polaroid for build entry [id],
     * returning its absolute path, or null on failure. Stored under
     * filesDir/build_photos/<id>.png.
     */
    fun saveLogbookPhoto(context: Context, id: Long, source: Bitmap): String? = runCatching {
        val file = logbookPhotoFile(context, id)
        saveBitmap(file, source.scaledTo(1280))
        file.absolutePath
    }.getOrNull()

    /** Loads a previously-saved logbook photo by absolute path, or null. */
    fun loadPhoto(path: String): Bitmap? = loadBitmapOrNull(File(path))

    private fun logbookPhotoFile(context: Context, id: Long): File =
        File(context.filesDir, "build_photos/$id.png")

    private fun carPhotoFile(context: Context): File = File(context.filesDir, "car/photo.png")
    private fun carPortraitFile(context: Context): File = File(context.filesDir, "car/portrait.png")

    private fun loadBitmapOrNull(file: File): Bitmap? =
        file.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }

    /**
     * Reads a monthly-recap/yearly-wrapped cover by absolute path, for any
     * recap saved before 2026-09-27 that still has one - [MonthlyRecapController]/
     * [com.kevin.midnightai.vehicle.YearlyWrappedController] no longer generate
     * new covers, so this is read-only history now.
     */
    fun loadRecapCover(path: String): Bitmap? = loadBitmapOrNull(File(path))

    /**
     * The Cruise wallpaper: [SeedArt]'s bundled summer coast road first, else
     * any legacy per-car generated wallpaper still cached on disk from before
     * the 2026-09-27 seed-art switch.
     */
    fun loadBackground(context: Context): Bitmap? = SeedArt.wallpaper(context)
        ?: backgroundFile(context).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }

    /** Clears any legacy generated wallpaper cached on disk (used by the reset flows). */
    fun deleteBackground(context: Context) {
        backgroundFile(context).delete()
    }

    /**
     * The active car's legacy wallpaper file (pre-2026-09-27 generated art),
     * for the [loadBackground]/[deleteBackground] fallback path only - nothing
     * writes this any more.
     */
    private fun backgroundFileForWrite(context: Context): File =
        File(context.filesDir, "background/companion_bg_${sanitize(ActiveVehicle.current(context))}.png")

    /**
     * The wallpaper to read a legacy fallback from: this car's own if it has
     * one, else the flat single-wallpaper file from before car profiles
     * (2026-07-16). Both predate seed art and are read-only history now.
     */
    private fun backgroundFile(context: Context): File {
        val perCar = backgroundFileForWrite(context)
        if (perCar.exists()) return perCar
        val legacy = File(context.filesDir, "background/companion_bg.png")
        return if (legacy.exists()) legacy else perCar
    }

    /** The active car's avatar id, for legacy per-car art lookups. */
    fun activeAvatarId(context: Context): String = CompanionProfile.avatarId(context)

    /**
     * The avatar id to read legacy per-car art from: this car's own faces if
     * it has any, else the flat legacy art from before car profiles. See
     * [backgroundFile]. Only matters as a fallback - [loadStates] checks
     * [SeedArt] first.
     */
    fun readAvatarId(context: Context): String {
        val perCar = CompanionProfile.avatarId(context)
        if (avatarDir(context, perCar).exists()) return perCar
        if (avatarDir(context, CompanionProfile.AVATAR_ID).exists()) return CompanionProfile.AVATAR_ID
        return perCar
    }

    // --- disk ------------------------------------------------------------

    private fun avatarDir(context: Context, vehicleId: String): File =
        File(context.filesDir, "avatars/${sanitize(vehicleId)}")

    private fun stateFile(context: Context, vehicleId: String, state: String): File =
        File(avatarDir(context, vehicleId), "$state.png")

    // OBD MACs contain colons; keep filenames filesystem-safe.
    private fun sanitize(vehicleId: String): String = vehicleId.replace(Regex("[^A-Za-z0-9]"), "_")

    private fun saveBitmap(file: File, bmp: Bitmap) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Downscales so the longest side is at most [maxDim] (keeps saved photos sane). */
    private fun Bitmap.scaledTo(maxDim: Int): Bitmap {
        val longest = maxOf(width, height)
        if (longest <= maxDim) return this
        val scale = maxDim.toFloat() / longest
        return Bitmap.createScaledBitmap(this, (width * scale).toInt(), (height * scale).toInt(), true)
    }
}
