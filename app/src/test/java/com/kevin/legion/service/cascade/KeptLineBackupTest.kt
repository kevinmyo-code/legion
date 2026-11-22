package com.kevin.legion.service.cascade

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The kept voice lines (`filesDir/scripted-tts/`, [KeptLineAudio], in app storage since 2026-11-21)
 * stay on this phone: never in a cloud backup or a device transfer. They are up to 10 MB of renders,
 * the laptop voice's among them (CC BY-NC), and any voice re-renders them on its own (review B1 of
 * 06a5ce79). Reads both Android backup rule files.
 */
class KeptLineBackupTest {
    private val store = "scripted-tts/"

    private fun res(name: String): File =
        listOf("src/main/res/xml", "app/src/main/res/xml").map { File(it, name) }.first { it.isFile }

    /** The `file` domain paths excluded under [section] (a tag name, or the root for the legacy file). */
    private fun excludedFiles(file: File, section: String?): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val root = (if (section == null) doc.documentElement else doc.getElementsByTagName(section).item(0)) as Element
        val nodes = root.getElementsByTagName("exclude")
        return (0 until nodes.length).map { nodes.item(it) as Element }
            .filter { it.getAttribute("domain") == "file" }
            .map { it.getAttribute("path") }
            .toSet()
    }

    @Test fun `the kept lines are excluded from cloud backup and device transfer`() {
        val rules = res("data_extraction_rules.xml")
        for (section in listOf("cloud-backup", "device-transfer")) {
            assertTrue(section, store in excludedFiles(rules, section))
        }
    }

    @Test fun `the kept lines are excluded from the legacy auto backup`() {
        assertTrue(store in excludedFiles(res("backup_rules.xml"), null))
    }
}
