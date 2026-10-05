package com.kevin.legion.meditations

import java.io.File
import org.junit.Assert.fail

/**
 * The real bundled file, read from the source tree so a plain JVM test needs no Context and no
 * Robolectric. Fails loudly when it cannot find it (CLAUDE.md section 4 rule 6: a test that passes
 * because it read nothing is not a test); `MeditationsAssetTest` separately proves the file is
 * actually packaged as an asset.
 */
object MeditationsFixture {

    fun dir(): File {
        var d: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (d != null) {
            for (rel in listOf("app/src/main/assets/meditations", "src/main/assets/meditations")) {
                val c = File(d, rel)
                if (c.isDirectory) return c
            }
            d = d.parentFile
        }
        fail("Could not locate assets/meditations from ${System.getProperty("user.dir")}")
        error("unreachable")
    }

    val raw: String by lazy { File(dir(), "meditations.txt").readText(Charsets.UTF_8) }
    val passages: List<Passage> by lazy { MeditationsText.parse(raw) }
    val search: MeditationsSearch by lazy { MeditationsSearch(passages) }
}
