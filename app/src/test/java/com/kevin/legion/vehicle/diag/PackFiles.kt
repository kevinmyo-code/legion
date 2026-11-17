package com.kevin.legion.vehicle.diag

import java.io.File

/** The bundled pack and code-table assets, read from the source tree (unit tests have no AssetManager). */
object PackFiles {
    private fun asset(name: String): File = listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name"))
        .first { it.exists() }

    fun text(): String = asset("diag_packs.json").readText()

    fun codeTable(): String = asset("dtc_sae_generic.csv").readText()
}
