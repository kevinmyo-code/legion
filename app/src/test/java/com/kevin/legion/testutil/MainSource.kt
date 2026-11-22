package com.kevin.legion.testutil

import org.junit.Assert.fail
import java.io.File

/**
 * Production Kotlin read as text, for the wiring tests that pin a call's shape or order in source.
 *
 * The big files are being split by `Refactor:` slices (shrink pass, 2026-11-22). Their wiring tests
 * read the whole module, the original file and every file split out of it, as one text, so an
 * assertion keeps passing when the code it names moves to a sibling file. The original file comes
 * first, so offsets inside it do not change. A slice that moves code out adds its new file to the
 * module's list; a listed file that is missing fails the test rather than reading as empty.
 */
object MainSource {

    /** `service/LiveToolbox.kt` and the toolboxes split out of it. */
    val LIVE_TOOLBOX = listOf(
        "service/LiveToolbox.kt",
        "service/MusicToolbox.kt",
        "service/MusicLibraryToolbox.kt",
        "service/MusicControlToolbox.kt",
        "service/GoalsToolbox.kt",
        "service/AdvisorToolbox.kt",
    )

    /** `service/cascade/CascadeSession.kt` and the files split out of it. */
    val CASCADE_SESSION = listOf("service/cascade/CascadeSession.kt")

    /** `app/src/main/java/com/kevin/legion`, from the repo root or from `app/`. */
    fun root(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("app/src/main/java/com/kevin/legion", "src/main/java/com/kevin/legion")
                .map { File(dir, it) }
                .firstOrNull { it.isDirectory }
                ?.let { return it }
            dir = dir.parentFile
        }
        fail("Could not locate the main source tree; a source test must not silently pass.")
        error("unreachable")
    }

    /** Every file of [module], in order, joined by a newline. */
    fun read(module: List<String>): String {
        val root = root()
        return module.joinToString("\n") { path ->
            val file = File(root, path)
            if (!file.isFile) fail("$path is listed in a MainSource module but does not exist")
            file.readText()
        }
    }
}
