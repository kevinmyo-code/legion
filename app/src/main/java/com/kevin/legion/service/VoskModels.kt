package com.kevin.legion.service

import org.vosk.Model
import java.io.File

/**
 * The one Vosk model in the process, shared by the wake engine and the local command gate.
 *
 * **Why one, and why never closed.** The small English model is about 70 MB of native memory, and
 * `org.vosk.Model` has no finalizer: a model dropped without `close()` is never freed. The wake
 * engine used to load a fresh one on every start and drop it on every stop (each settings toggle,
 * each service restart leaked one), and the gate loaded its own second copy. Process-lived and
 * shared, it costs one copy and cannot be freed under a recognizer that is still running.
 * Vosk recognizers on different threads may share one model.
 */
object VoskModels {
    /** Where the wake engine copies the model out of assets (Vosk needs a real filesystem path). */
    const val DIR = "vosk-model"

    private val once = OnceModel { dir -> Model(dir.absolutePath) }

    /** True once the model's files are on disk under [dir]. */
    fun isCopied(dir: File): Boolean = File(dir, "am/final.mdl").exists()

    /** The shared model, loading it from [dir] on first use. Throws when it will not load. */
    fun get(dir: File): Model = once.get(dir)
}

/** Loads a value once and keeps it; a load that throws is not kept, so the next call tries again. */
internal class OnceModel<T : Any>(private val load: (File) -> T) {
    private var value: T? = null

    @Synchronized
    fun get(dir: File): T = value ?: load(dir).also { value = it }
}
